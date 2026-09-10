/*
 * simlab-forge-shim -- GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilCombat;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

/**
 * Combat as an assignment problem (0.16.0, Sim Lab "new engine" A/B).
 *
 * Replaces the greedy pairwise passes (humanizeBlocks, holdBackBlockers) with
 * a bounded branch-and-bound search over legal assignments, scored by a board
 * utility V(S) = sum omega(my survivors) - sum omega(their survivors)
 * - lifeWeight * life I lose (+ lifeWeight * life they lose when I attack).
 * omega is an intrinsic permanent valuation; the life weight shifts with the
 * race clock ("who's the beatdown"): the player being raced prices its life
 * higher, the player racing prices damage dealt higher.
 *
 * Boundary: Forge remains the rules engine. Every legality question goes to
 * CombatUtil (canBlock, canAttack, menace minimums, validateBlocks) and every
 * lethal-damage threshold to ComputerUtilCombat. This class only chooses
 * among options Forge says are legal, and PlanPlayerController reverts to
 * Forge's own assignment whenever Forge rejects the chosen one. The dials
 * (lifeValue, combatSolver) are plan data.
 *
 * Bounded on purpose: at most SCAN_CAP bodies a side and NODE_CAP search
 * nodes per decision, with per-cluster memoisation (the doc's transposition
 * table, at the scale this search actually needs). The profile in
 * engine/SIM_PERFORMANCE.md puts the shim's combat code at 0.0% of game CPU,
 * so this is a quality change, not a speed change, and the caps keep it so.
 */
final class SeeCombat {

    static final int SCAN_CAP = 12;
    static final int NODE_CAP = 4000;
    private static final double LETHAL_PENALTY = 1000.0;
    private static final int UNKILLABLE = 1 << 20;

    private final Player me;
    private final DeckPlan plan;
    private final Map<String, Integer> threatIndex;
    private final Map<String, Double> memo = new HashMap<>();
    int nodes = 0;

    SeeCombat(Player me, DeckPlan plan, Map<String, Integer> threatIndex) {
        this.me = me;
        this.plan = plan;
        this.threatIndex = threatIndex;
    }

    // ------------------------------------------------------------------
    // Valuation
    // ------------------------------------------------------------------

    /** omega(c): mana value plus body plus what the table's threat index
     *  and the plan's own weights say about the card. Tokens are cheaper
     *  than the card they copy: a human trades a token for a card gladly. */
    double omega(Card c) {
        double w = 1.0 + c.getCMC()
                + (Math.max(0, c.getNetPower()) + Math.max(0, c.getNetToughness())) / 2.0;
        if (c.isCommander()) w += 10;
        Integer t = threatIndex.get(c.getName());
        int pw = plan.weightOf(c.getName());
        w += Math.max(t == null ? 0 : t, pw) / 2.0;
        if (c.isToken()) w *= 0.6;
        return w;
    }

    /** Turns until `life` is gone at `powerPerTurn`; a large number when
     *  nothing is coming. */
    static int clock(int life, int powerPerTurn) {
        if (powerPerTurn <= 0) return 99;
        return (int) Math.ceil(life / (double) powerPerTurn);
    }

    /** Price of one point of my life while I am the DEFENDER this combat.
     *  Base is the plan's lifeValue. Being raced (their clock on me is
     *  shorter than mine on them) doubles it: the player being raced is
     *  the control player and preserves life. Danger quadruples it. */
    double defenderLifeWeight(int incomingPower) {
        int myLife = me.getLife();
        int theirClock = clock(myLife, incomingPower);
        int myClock = clock(minOpponentLife(), myUntappedPower());
        double mu = plan.lifeValue;
        if (theirClock <= myClock) mu *= 2.0;
        if (myLife - incomingPower <= plan.dangerLife) mu *= 2.0;
        return mu;
    }

    /** Price of one point of an opponent's life while I am the ATTACKER.
     *  Being ahead in the race (my clock shorter) doubles it: the beatdown
     *  player accepts even trades to force damage through. */
    double attackerLifeWeight(int crackbackPower) {
        int myClock = clock(minOpponentLife(), myUntappedPower());
        int theirClock = clock(me.getLife(), crackbackPower);
        double lambda = plan.lifeValue;
        if (myClock < theirClock) lambda *= 2.0;
        return lambda;
    }

    int minOpponentLife() {
        int min = Integer.MAX_VALUE;
        for (Player o : me.getOpponents()) {
            if (!o.hasLost()) min = Math.min(min, o.getLife());
        }
        return min == Integer.MAX_VALUE ? 40 : min;
    }

    int myUntappedPower() {
        int p = 0;
        for (Card c : me.getCreaturesInPlay()) {
            if (!c.isTapped()) p += Math.max(0, c.getNetPower());
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Static Exchange Evaluation of one combat cluster
    // ------------------------------------------------------------------

    /**
     * Value, to the DEFENDER, of attacker `att` blocked by `blockers` (which
     * may be empty), in omega units, with unblocked or trampled damage priced
     * at `mu` per life point. The attacker is assumed to spend its damage on
     * the most valuable set of blockers it can kill (the same knapsack the
     * damage-assignment override uses), so this is the defender's worst case.
     * Lethal thresholds come from Forge (deathtouch, damage already marked,
     * prevention); strike timing and trample are read off Forge keywords.
     */
    double cluster(Card att, List<Card> blockers, double mu) {
        String key = att.getId() + ":" + ids(blockers) + "@" + mu;
        Double hit = memo.get(key);
        if (hit != null) return hit;
        nodes++;
        double v = clusterUncached(att, blockers, mu);
        memo.put(key, v);
        return v;
    }

    private double clusterUncached(Card att, List<Card> blockers, double mu) {
        int power = Math.max(0, att.getNetCombatDamage());
        if (blockers.isEmpty()) return -mu * power;
        boolean attIndestructible = att.hasKeyword(Keyword.INDESTRUCTIBLE);
        int lethalToAtt = attIndestructible ? UNKILLABLE
                : Math.max(1, ComputerUtilCombat.getDamageToKill(att, false));
        boolean attFS = att.hasFirstStrike() || att.hasDoubleStrike();
        boolean attDT = att.hasKeyword(Keyword.DEATHTOUCH);

        int n = blockers.size();
        int[] need = new int[n];
        double[] w = new double[n];
        boolean[] bFS = new boolean[n];
        int budget = power * (att.hasDoubleStrike() ? 2 : 1);
        int fsBack = 0;
        boolean fsDeathtouch = false;
        for (int i = 0; i < n; i++) {
            Card b = blockers.get(i);
            w[i] = omega(b);
            bFS[i] = b.hasFirstStrike() || b.hasDoubleStrike();
            int lethal = ComputerUtilCombat.getEnoughDamageToKill(b, budget, att, true);
            if (attDT) lethal = Math.min(lethal, 1);
            need[i] = b.hasKeyword(Keyword.INDESTRUCTIBLE) ? UNKILLABLE : Math.max(1, lethal);
            int p = Math.max(0, b.getNetCombatDamage());
            if (bFS[i]) {
                fsBack += p * (b.hasDoubleStrike() ? 2 : 1);
                if (p > 0 && b.hasKeyword(Keyword.DEATHTOUCH)) fsDeathtouch = true;
            }
        }
        // First-strike blockers kill a non-first-strike attacker before it
        // deals damage: the whole cluster is a free kill.
        if (!attFS && !attIndestructible && (fsBack >= lethalToAtt || fsDeathtouch)) {
            return omega(att);
        }
        // The attacker's damage: max-omega subset it can kill within budget.
        int killedMask = knapsack(need, w, budget);
        double lost = 0;
        for (int i = 0; i < n; i++) {
            if ((killedMask & (1 << i)) != 0) lost += w[i];
        }
        // Damage back at the attacker. If the attacker has first strike and
        // a blocker does not, that blocker only strikes if it survived.
        int back = 0;
        boolean dt = false;
        for (int i = 0; i < n; i++) {
            boolean killedFirst = attFS && !bFS[i] && (killedMask & (1 << i)) != 0;
            if (killedFirst) continue;
            Card b = blockers.get(i);
            int p = Math.max(0, b.getNetCombatDamage());
            back += p * (b.hasDoubleStrike() ? 2 : 1);
            if (p > 0 && b.hasKeyword(Keyword.DEATHTOUCH)) dt = true;
        }
        boolean attDies = !attIndestructible && (back >= lethalToAtt || dt);
        // Trample: lethal to every blocker, the rest at my face.
        double excess = 0;
        if (att.hasKeyword(Keyword.TRAMPLE)) {
            long needAll = 0;
            for (int i = 0; i < n; i++) {
                needAll += need[i] == UNKILLABLE
                        ? Math.max(1, blockers.get(i).getNetToughness()) : need[i];
            }
            if (budget > needAll) {
                excess = budget - needAll;
                lost = 0;
                for (int i = 0; i < n; i++) {
                    if (need[i] != UNKILLABLE) lost += w[i];
                }
            }
        }
        return (attDies ? omega(att) : 0.0) - lost - mu * excess;
    }

    /** Subset of blockers with total lethal-damage need within budget that
     *  maximises total omega. n is at most SCAN_CAP, so exhaustive. */
    static int knapsack(int[] need, double[] w, int budget) {
        int n = need.length;
        int best = 0;
        double bestW = 0;
        for (int m = 1; m < (1 << n); m++) {
            long tot = 0;
            double ww = 0;
            for (int i = 0; i < n; i++) {
                if ((m & (1 << i)) != 0) {
                    tot += need[i];
                    ww += w[i];
                }
            }
            if (tot <= budget && ww > bestW) {
                bestW = ww;
                best = m;
            }
        }
        return best;
    }

    private static String ids(List<Card> cs) {
        int[] a = new int[cs.size()];
        for (int i = 0; i < a.length; i++) a[i] = cs.get(i).getId();
        Arrays.sort(a);
        return Arrays.toString(a);
    }

    // ------------------------------------------------------------------
    // Block allocation: branch and bound over blocker -> attacker
    // ------------------------------------------------------------------

    static final class BlockPlan {
        final Map<Card, Card> assign = new LinkedHashMap<>(); // blocker -> attacker (absent = no block)
        double value = -Double.MAX_VALUE;
        int nodes;
        boolean capped;
    }

    /**
     * Best legal assignment of `blockers` (my free, movable bodies) to
     * `attackers` (the ones aimed at me), given `fixed` blocks that stay as
     * Forge made them. Value counts every attacker aimed at me, blocked or
     * not, so leaving one unblocked is priced.
     */
    BlockPlan solveBlocks(Combat combat, List<Card> attackers, List<Card> blockers,
                          Map<Card, List<Card>> fixed, double mu) {
        int na = attackers.size();
        int nb = blockers.size();
        boolean[][] legal = new boolean[nb][na];
        int[] minBlockers = new int[na];
        for (int j = 0; j < na; j++) {
            Card att = attackers.get(j);
            try {
                minBlockers[j] = Math.max(1, CombatUtil.getMinNumBlockersForAttacker(att, me));
            } catch (Exception e) {
                minBlockers[j] = 1;
            }
            for (int i = 0; i < nb; i++) {
                try {
                    legal[i][j] = CombatUtil.canBlock(att, blockers.get(i), combat);
                } catch (Exception e) {
                    legal[i][j] = false;
                }
            }
        }
        // Standalone gain per blocker: its best single block over no block.
        // Used for the bound and for the branch order. Not a strict upper
        // bound when two blockers together do what neither does alone, so
        // it is padded; a missed optimum costs quality, never legality.
        double[] gain = new double[nb];
        Integer[] order = new Integer[nb];
        for (int i = 0; i < nb; i++) {
            order[i] = i;
            double g = 0;
            for (int j = 0; j < na; j++) {
                if (!legal[i][j]) continue;
                List<Card> one = new ArrayList<>(fixed.getOrDefault(attackers.get(j), Collections.emptyList()));
                one.add(blockers.get(i));
                List<Card> base = fixed.getOrDefault(attackers.get(j), Collections.emptyList());
                if (one.size() < minBlockers[j]) continue;
                g = Math.max(g, cluster(attackers.get(j), one, mu) - cluster(attackers.get(j), base, mu));
            }
            gain[i] = g * 1.5;
        }
        Arrays.sort(order, (x, y) -> Double.compare(gain[y], gain[x]));
        double[] suffix = new double[nb + 1];
        for (int k = nb - 1; k >= 0; k--) suffix[k] = suffix[k + 1] + gain[order[k]];

        BlockPlan best = new BlockPlan();
        int[] choice = new int[nb];
        Arrays.fill(choice, -1);
        nodes = 0;
        dfsBlocks(0, choice, order, legal, minBlockers, attackers, blockers, fixed, mu, suffix, best);
        best.nodes = nodes;
        best.capped = nodes >= NODE_CAP;
        return best;
    }

    private void dfsBlocks(int k, int[] choice, Integer[] order, boolean[][] legal, int[] minBlockers,
                           List<Card> attackers, List<Card> blockers, Map<Card, List<Card>> fixed,
                           double mu, double[] suffix, BlockPlan best) {
        if (nodes >= NODE_CAP) return;
        if (k == blockers.size()) {
            double v = evaluateBlocks(choice, legal, minBlockers, attackers, blockers, fixed, mu, true);
            if (v > best.value) {
                best.value = v;
                best.assign.clear();
                for (int i = 0; i < blockers.size(); i++) {
                    if (choice[i] >= 0) best.assign.put(blockers.get(i), attackers.get(choice[i]));
                }
            }
            return;
        }
        // Bound: what is committed so far, evaluated as if the rest stay
        // home, plus the padded standalone gains of the rest. WITHOUT the
        // lethal penalty: the rest may yet block enough to lift it, and a
        // bound that includes it prunes every branch after the first leaf
        // (measured on the first smoke run: best -1115 against stock -131).
        if (best.value > -Double.MAX_VALUE) {
            double partial = evaluateBlocks(choice, legal, minBlockers, attackers, blockers, fixed, mu, false);
            if (partial + suffix[k] <= best.value) return;
        }
        int i = order[k];
        // Try the blocks first, best standalone attacker first, then no block.
        Integer[] atts = new Integer[attackers.size()];
        for (int j = 0; j < atts.length; j++) atts[j] = j;
        Card b = blockers.get(i);
        Arrays.sort(atts, (x, y) -> Double.compare(single(attackers.get(y), b, fixed, mu),
                                                     single(attackers.get(x), b, fixed, mu)));
        for (int j : atts) {
            if (!legal[i][j]) continue;
            choice[i] = j;
            dfsBlocks(k + 1, choice, order, legal, minBlockers, attackers, blockers, fixed, mu, suffix, best);
        }
        choice[i] = -1;
        dfsBlocks(k + 1, choice, order, legal, minBlockers, attackers, blockers, fixed, mu, suffix, best);
    }

    private double single(Card att, Card b, Map<Card, List<Card>> fixed, double mu) {
        List<Card> one = new ArrayList<>(fixed.getOrDefault(att, Collections.emptyList()));
        one.add(b);
        return cluster(att, one, mu);
    }

    /** V(S) for a full or partial assignment; unassigned blockers stay home.
     *  A cluster short of its menace minimum is illegal and sinks the node. */
    private double evaluateBlocks(int[] choice, boolean[][] legal, int[] minBlockers,
                                  List<Card> attackers, List<Card> blockers,
                                  Map<Card, List<Card>> fixed, double mu, boolean withLethal) {
        double total = 0;
        double unblockedDamage = 0;
        for (int j = 0; j < attackers.size(); j++) {
            Card att = attackers.get(j);
            List<Card> bs = new ArrayList<>(fixed.getOrDefault(att, Collections.emptyList()));
            for (int i = 0; i < blockers.size(); i++) {
                if (choice[i] == j) bs.add(blockers.get(i));
            }
            if (!bs.isEmpty() && bs.size() < minBlockers[j]) return -LETHAL_PENALTY * 10;
            double v = cluster(att, bs, mu);
            total += v;
            if (bs.isEmpty()) unblockedDamage += Math.max(0, att.getNetCombatDamage());
        }
        if (withLethal && me.getLife() - unblockedDamage <= 0 && me.canLoseLife()) total -= LETHAL_PENALTY;
        return total;
    }

    // ------------------------------------------------------------------
    // Attack declaration: branch and bound over the attack set
    // ------------------------------------------------------------------

    static final class AttackPlan {
        final Map<Card, GameEntity> attack = new LinkedHashMap<>(); // attacker -> defender
        double value = -Double.MAX_VALUE;
        int nodes;
        boolean capped;
        double gains;
        double exposure;
    }

    /**
     * Best subset of `candidates` (each with a fixed defender) to attack
     * with. An attacker's gain assumes the defender's best legal single
     * response from its untapped creatures, blockers not reused (a greedy
     * matching, largest attacker first). Bodies left home defend against
     * the crack-back: each keeper covers the biggest incoming creature it
     * can legally block, and what is not covered is priced at `mu`.
     */
    AttackPlan solveAttacks(Combat combat, List<Card> candidates, Map<Card, GameEntity> target,
                            double lambda, double mu) {
        int n = candidates.size();
        // Defender blocker pools and incoming crack-back, public board only.
        Map<Player, List<Card>> pools = new HashMap<>();
        List<Card> incoming = new ArrayList<>();
        for (Player o : me.getOpponents()) {
            if (o.hasLost()) continue;
            List<Card> pool = new ArrayList<>();
            for (Card c : o.getCreaturesInPlay()) {
                if (!c.isTapped()) {
                    pool.add(c);
                    incoming.add(c);
                }
            }
            pool.sort((a, b) -> Double.compare(omega(b), omega(a)));
            pools.put(o, cap(pool));
        }
        incoming.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        incoming = cap(incoming);
        // My non-attacking bodies that could block next turn.
        List<Card> mine = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (!c.isTapped() || c.hasKeyword(Keyword.VIGILANCE)) mine.add(c);
        }

        // Standalone gain per candidate: unblocked damage, or the defender's
        // best single response if that is worse for me.
        double[] alone = new double[n];
        for (int i = 0; i < n; i++) {
            Card a = candidates.get(i);
            GameEntity d = target.get(a);
            double unblocked = lambda * Math.max(0, a.getNetCombatDamage());
            double g = unblocked;
            if (d instanceof Player) {
                for (Card b : pools.getOrDefault(d, Collections.emptyList())) {
                    if (!canBlock(a, b)) continue;
                    g = Math.min(g, attackerGain(a, Collections.singletonList(b), lambda));
                }
            }
            alone[i] = g;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(alone[y], alone[x]));
        double[] suffix = new double[n + 1];
        for (int k = n - 1; k >= 0; k--) suffix[k] = suffix[k + 1] + Math.max(0, alone[order[k]]);

        AttackPlan best = new AttackPlan();
        boolean[] in = new boolean[n];
        nodes = 0;
        dfsAttacks(0, in, order, candidates, target, pools, incoming, mine, lambda, mu, suffix, best);
        best.nodes = nodes;
        best.capped = nodes >= NODE_CAP;
        return best;
    }

    private void dfsAttacks(int k, boolean[] in, Integer[] order, List<Card> cands,
                            Map<Card, GameEntity> target, Map<Player, List<Card>> pools,
                            List<Card> incoming, List<Card> mine, double lambda, double mu,
                            double[] suffix, AttackPlan best) {
        if (nodes >= NODE_CAP) return;
        if (k == cands.size()) {
            double[] parts = new double[2];
            double v = evaluateAttack(in, cands, target, pools, incoming, mine, lambda, mu, parts);
            if (v > best.value) {
                best.value = v;
                best.gains = parts[0];
                best.exposure = parts[1];
                best.attack.clear();
                for (int i = 0; i < cands.size(); i++) {
                    if (in[i]) best.attack.put(cands.get(i), target.get(cands.get(i)));
                }
            }
            return;
        }
        if (best.value > -Double.MAX_VALUE) {
            // Committed attackers' value plus the rest's positive standalone
            // gains; the rest staying home can only help the defence term.
            double partial = evaluateAttack(in, cands, target, pools, incoming, mine, lambda, mu, null);
            if (partial + suffix[k] <= best.value) return;
        }
        int i = order[k];
        in[i] = true;
        dfsAttacks(k + 1, in, order, cands, target, pools, incoming, mine, lambda, mu, suffix, best);
        in[i] = false;
        dfsAttacks(k + 1, in, order, cands, target, pools, incoming, mine, lambda, mu, suffix, best);
    }

    /** Attack-set value: gains after the defenders' greedy responses, minus
     *  the crack-back my keepers cannot cover, priced at mu. */
    private double evaluateAttack(boolean[] in, List<Card> cands, Map<Card, GameEntity> target,
                                  Map<Player, List<Card>> pools, List<Card> incoming,
                                  List<Card> mine, double lambda, double mu, double[] parts) {
        nodes++;
        // Attackers by defender, biggest first: the defender answers those first.
        Map<GameEntity, List<Card>> byDef = new HashMap<>();
        for (int i = 0; i < cands.size(); i++) {
            if (in[i]) byDef.computeIfAbsent(target.get(cands.get(i)), x -> new ArrayList<>()).add(cands.get(i));
        }
        double gains = 0;
        for (Map.Entry<GameEntity, List<Card>> e : byDef.entrySet()) {
            List<Card> atts = e.getValue();
            atts.sort((a, b) -> Integer.compare(b.getNetCombatDamage(), a.getNetCombatDamage()));
            List<Card> pool = new ArrayList<>(pools.getOrDefault(e.getKey(), Collections.emptyList()));
            for (Card a : atts) {
                double g = lambda * Math.max(0, a.getNetCombatDamage());
                Card pick = null;
                for (Card b : pool) {
                    if (!canBlock(a, b)) continue;
                    double gb = attackerGain(a, Collections.singletonList(b), lambda);
                    if (gb < g) {
                        g = gb;
                        pick = b;
                    }
                }
                if (pick != null) pool.remove(pick);
                gains += g;
            }
        }
        // Crack-back exposure: keepers cover the biggest things they can block.
        List<Card> keepers = new ArrayList<>();
        for (Card c : mine) {
            int idx = cands.indexOf(c);
            if (idx < 0 || !in[idx] || c.hasKeyword(Keyword.VIGILANCE)) keepers.add(c);
        }
        double exposure = 0;
        for (Card threat : incoming) {
            Card cover = null;
            for (Card kp : keepers) {
                if (canBlock(threat, kp)) {
                    if (cover == null || omega(kp) < omega(cover)) cover = kp;
                }
            }
            if (cover != null) keepers.remove(cover);
            else exposure += Math.max(0, threat.getNetPower());
        }
        double penalty = mu * exposure;
        if (me.getLife() - exposure <= 0 && me.canLoseLife()) penalty += LETHAL_PENALTY;
        if (parts != null) {
            parts[0] = gains;
            parts[1] = exposure;
        }
        return gains - penalty;
    }

    /** The attacker's view of one cluster: what I gain if `att` is blocked
     *  by `blockers`. The mirror of cluster(): their losses are my gains,
     *  my attacker dying is my loss, trample damage is priced at lambda. */
    double attackerGain(Card att, List<Card> blockers, double lambda) {
        return -cluster(att, blockers, lambda);
    }

    boolean canBlock(Card attacker, Card blocker) {
        try {
            return CombatUtil.canBlock(attacker, blocker);
        } catch (Exception e) {
            return false;
        }
    }

    static <T> List<T> cap(List<T> xs) {
        return xs.size() <= SCAN_CAP ? xs : new ArrayList<>(xs.subList(0, SCAN_CAP));
    }

    // ------------------------------------------------------------------
    // Damage distribution: knapsack over blockers, no assignment order
    // ------------------------------------------------------------------

    /**
     * Split `damage` from `attacker` across `blockers` to kill the most
     * omega, then dump the rest on the most valuable survivor (or, when the
     * caller allows trample and everything dies, return the surplus for the
     * defender). Returns null when the stock split should stand.
     */
    Map<Card, Integer> splitDamage(Card attacker, List<Card> blockers, int damage, int[] surplusOut) {
        int n = blockers.size();
        if (n < 2 || damage <= 0) return null;
        int[] need = new int[n];
        double[] w = new double[n];
        boolean dt = attacker.hasKeyword(Keyword.DEATHTOUCH);
        for (int i = 0; i < n; i++) {
            Card b = blockers.get(i);
            w[i] = omega(b);
            int lethal = ComputerUtilCombat.getEnoughDamageToKill(b, damage, attacker, true);
            if (dt) lethal = Math.min(lethal, 1);
            need[i] = Math.max(1, lethal);
        }
        int mask = knapsack(need, w, damage);
        Map<Card, Integer> out = new LinkedHashMap<>();
        int spent = 0;
        for (int i = 0; i < n; i++) {
            int d = (mask & (1 << i)) != 0 ? need[i] : 0;
            out.put(blockers.get(i), d);
            spent += d;
        }
        int rest = damage - spent;
        boolean allDead = mask == (1 << n) - 1;
        if (rest > 0) {
            if (allDead && surplusOut != null) {
                surplusOut[0] = rest;
            } else {
                // Onto the most valuable survivor, or the biggest body if all died.
                Card sink = null;
                for (int i = 0; i < n; i++) {
                    boolean dead = (mask & (1 << i)) != 0;
                    if (allDead || !dead) {
                        if (sink == null || w[i] > omega(sink)) sink = blockers.get(i);
                    }
                }
                if (sink == null) sink = blockers.get(0);
                out.put(sink, out.get(sink) + rest);
            }
        }
        return out;
    }
}
