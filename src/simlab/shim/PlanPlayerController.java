/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Human-like decision layer over Forge's stock AI. Every override delegates
 * to PlayerControllerAi first and then adjusts the result within the space
 * of legal options — Forge still adjudicates every rule, so rules
 * correctness is untouched. Every adjustment is guarded: any exception
 * falls back to stock behavior rather than corrupting a sim.
 *
 * All thresholds and card knowledge come from the DeckPlan (data), not from
 * this file (mechanism). See the repo README's boundary rule.
 */
final class PlanPlayerController extends PlayerControllerAi {

    private final DeckPlan plan;
    private final Map<String, Integer> threatIndex;
    // Every seat's plan, keyed by player name: public-decklist familiarity,
    // the same knowledge level as the threat index. Used ONLY to read
    // opponents' known lines against their public board. May be empty.
    private final Map<String, DeckPlan> tablePlans;
    private final Random rng;
    // Per-game instance, not the old process-global: a game thread that
    // outlives its own game must not log into the next one (audit A9).
    private final AgentLog agentLog;
    private int mullsTaken = 0;
    // Stage 4 — grudge memory: combat damage each opponent has pointed at me,
    // accumulated from PUBLIC combat declarations only. Keyed by player name
    // so it survives Forge's player-object churn between games is irrelevant
    // (one controller per game).
    private final Map<String, Double> grudge = new HashMap<>();

    PlanPlayerController(Game game, Player player, LobbyPlayer lobby,
                         DeckPlan plan, Map<String, Integer> threatIndex,
                         Map<String, DeckPlan> tablePlans, long seed,
                         AgentLog agentLog) {
        super(game, player, lobby);
        this.plan = plan;
        this.threatIndex = threatIndex;
        this.tablePlans = tablePlans;
        this.rng = new Random(seed);
        this.agentLog = agentLog;
    }

    // ------------------------------------------------------------------
    // Stage 1 — mulligans. Stock AI keeps 7 in ~97% of hands; humans keep
    // hands that have lands in range AND a reason (a plan card).
    // ------------------------------------------------------------------

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        try {
            CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
            int lands = 0;
            boolean planCard = false;
            for (Card c : hand) {
                if (c.isLand()) lands++;
                if (plan.keepCards.contains(c.getName()) || plan.weightOf(c.getName()) >= 5) {
                    planCard = true;
                }
            }
            int effective = hand.size() - Math.max(0, cardsToReturn);
            if (mullsTaken >= plan.maxMulls || effective <= 5) {
                return true; // deep enough — keep what we have
            }
            boolean landsOk = lands >= plan.minLands && lands <= plan.maxLands;
            // A 7-card keep needs lands in range and a reason; after the free
            // Commander mulligan the reason requirement relaxes.
            boolean keep = mullsTaken == 0 ? (landsOk && (planCard || (lands >= 3 && lands <= 4)))
                                           : landsOk;
            if (!keep) mullsTaken++;
            agentLog.event(0, getPlayer().getName(), keep ? "mull_keep" : "mull_take",
                    "lands=" + lands + " planCard=" + planCard + " size=" + effective);
            return keep;
        } catch (Exception e) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView cards, int amount) {
        try {
            // London bottoming: shed excess lands beyond 3, then the most
            // expensive non-plan cards.
            List<Card> pool = new ArrayList<>();
            for (Card c : cards) pool.add(c);
            CardCollection tuck = new CardCollection();
            int lands = (int) pool.stream().filter(Card::isLand).count();
            while (tuck.size() < amount && lands > 3) {
                for (Card c : pool) {
                    if (c.isLand() && !tuck.contains(c)) { tuck.add(c); lands--; break; }
                }
            }
            pool.sort((a, b) -> {
                int pa = plan.weightOf(a.getName()) - a.getCMC();
                int pb = plan.weightOf(b.getName()) - b.getCMC();
                return Integer.compare(pa, pb); // worst first
            });
            for (Card c : pool) {
                if (tuck.size() >= amount) break;
                if (!c.isLand() && !tuck.contains(c)
                        && !plan.keepCards.contains(c.getName())) {
                    tuck.add(c);
                }
            }
            for (Card c : pool) { // last resort: anything
                if (tuck.size() >= amount) break;
                if (!tuck.contains(c)) tuck.add(c);
            }
            return tuck;
        } catch (Exception e) {
            return super.tuckCardsViaMulligan(cards, amount);
        }
    }

    // ------------------------------------------------------------------
    // Stage 2 — combat. Stock AI focuses all attackers on one defender
    // (measured 244/244) and blocks 14% of the time.
    // ------------------------------------------------------------------

    private int attackAskTurn = -1;
    private int attackAsks = 0;

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
        // Forge re-asks while the declaration fails its attack requirements
        // (PhaseHandler loops on validateAttackers). Measured 2026-09-09 on
        // the engine A/B and the 0.15.0 cohort arm: kingmakerReaim moved an
        // attacker onto a player it was not allowed to attack, Forge
        // rejected the set, the stock AI declared again, the re-aim fired
        // again, 3,970 times in one turn until the clock killed the game.
        // Two guards: every adjusted declaration is validated and reverted
        // to Forge's own when it fails, and a second ask in the same turn
        // gets Forge's declaration untouched.
        int turn = turnNow();
        if (turn != attackAskTurn) {
            attackAskTurn = turn;
            attackAsks = 0;
        }
        attackAsks++;
        if (attackAsks > 1) {
            if (attackAsks == 2) {
                agentLog.event(turn, getPlayer().getName(), "attack_reask",
                        "Forge re-asked; leaving its declaration untouched");
            }
            return;
        }
        Map<Card, GameEntity> stockDecl = new HashMap<>();
        for (Card c : combat.getAttackers()) {
            GameEntity d = combat.getDefenderByAttacker(c);
            if (d != null) stockDecl.put(c, d);
        }
        boolean solved = false;
        if (useSolver()) {
            try {
                solved = seeAttacks(combat);
            } catch (Exception e) {
                agentLog.event(turn, getPlayer().getName(), "see_error",
                        "attack " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        try {
            humanizeAttacks(combat, solved);
        } catch (Exception e) {
            System.err.println("shim: humanizeAttacks fell back to stock: " + e);
        }
        boolean valid;
        try {
            valid = CombatUtil.validateAttackers(combat);
        } catch (Exception e) {
            valid = false;
        }
        if (!valid) {
            for (Card c : new ArrayList<>(combat.getAttackers())) combat.removeFromCombat(c);
            for (Map.Entry<Card, GameEntity> e : stockDecl.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            agentLog.event(turn, getPlayer().getName(), "attack_reverted",
                    "adjusted declaration failed Forge's requirements; stock restored");
        }
    }

    // ------------------------------------------------------------------
    // 0.16.0 -- SeeCombat: combat as a bounded assignment search (Sim Lab
    // engine A/B, the Gemini architecture note's phase 2, built inside the
    // shim boundary). Off unless the plan's combatSolver dial says so.
    // ------------------------------------------------------------------

    /** One roll per game: a seat is either on the new engine or not, so a
     *  game's records describe one pilot. */
    private Boolean solverOn = null;

    private boolean useSolver() {
        if (plan.combatSolver <= 0) return false;
        if (solverOn == null) solverOn = rng.nextDouble() < plan.combatSolver;
        return solverOn;
    }

    private Boolean gatesOn = null;

    private boolean useGates() {
        if (plan.priorityGates <= 0) return false;
        if (gatesOn == null) gatesOn = rng.nextDouble() < plan.priorityGates;
        return gatesOn;
    }

    /** Choose the attack SET by branch and bound over my possible attackers;
     *  each candidate keeps the defender Forge picked for it, or the
     *  highest-threat opponent it can attack when Forge left it home. The
     *  targeting passes (kingmaker, split, open target) still run after.
     *  Returns true when the solver made the decision. */
    private boolean seeAttacks(Combat combat) {
        Player me = getPlayer();
        CardCollection stockAtt = new CardCollection(combat.getAttackers());
        // A kill is never called off.
        if (attackIsLethal(combat)) return false;
        List<Player> defenders = new ArrayList<>();
        for (Player o : me.getOpponents()) {
            if (!o.hasLost()) defenders.add(o);
        }
        if (defenders.isEmpty()) return false;
        defenders.sort((a, b) -> Double.compare(threatOf(b), threatOf(a)));

        List<Card> cands = new ArrayList<>();
        Map<Card, GameEntity> target = new HashMap<>();
        Map<Card, GameEntity> stockTarget = new HashMap<>();
        List<Card> pool = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (c.hasKeyword(forge.game.keyword.Keyword.DEFENDER)) continue;
            if (stockAtt.contains(c) || CombatUtil.canAttack(c)) pool.add(c);
        }
        pool.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        for (Card c : SeeCombat.cap(pool)) {
            GameEntity d = combat.getDefenderByAttacker(c);
            if (d != null) stockTarget.put(c, d);
            if (d == null) {
                for (Player o : defenders) {
                    if (CombatUtil.canAttack(c, o)) { d = o; break; }
                }
            }
            if (d == null) continue;
            cands.add(c);
            target.put(c, d);
        }
        if (cands.isEmpty()) return false;

        SeeCombat see = new SeeCombat(me, plan, threatIndex);
        int crackback = 0;
        for (Player o : defenders) {
            for (Card c : o.getCreaturesInPlay()) {
                if (!c.isTapped()) crackback += Math.max(0, c.getNetPower());
            }
        }
        double lambda = see.attackerLifeWeight(crackback);
        double mu = see.defenderLifeWeight(crackback);
        SeeCombat.AttackPlan best = see.solveAttacks(combat, cands, target, lambda, mu);
        if (best.attack.isEmpty() && best.value == -Double.MAX_VALUE) return false;

        // Apply: stock attackers the plan drops come out, plan attackers
        // Forge left home go in. Anything outside the candidate slice keeps
        // Forge's decision.
        int removed = 0;
        int added = 0;
        for (Card c : cands) {
            boolean want = best.attack.containsKey(c);
            boolean has = combat.isAttacking(c);
            if (has && !want) {
                combat.removeFromCombat(c);
                removed++;
            } else if (!has && want) {
                GameEntity d = best.attack.get(c);
                if (CombatUtil.canAttack(c, d)) {
                    combat.addAttacker(c, d);
                    added++;
                }
            }
        }
        if (!CombatUtil.validateAttackers(combat)) {
            // Forge rejects the set: restore its own declaration exactly.
            for (Card c : new ArrayList<>(combat.getAttackers())) combat.removeFromCombat(c);
            for (Card c : stockAtt) {
                GameEntity d = stockTarget.get(c);
                if (d != null) combat.addAttacker(c, d);
            }
            agentLog.event(turnNow(), me.getName(), "see_attack",
                    "REVERTED invalid set removed=" + removed + " added=" + added);
            return false;
        }
        agentLog.event(turnNow(), me.getName(), "see_attack",
                "attackers=" + combat.getAttackers().size() + " stock=" + stockAtt.size()
                + " removed=" + removed + " added=" + added
                + " value=" + Math.round(best.value * 10) / 10.0
                + " gains=" + Math.round(best.gains * 10) / 10.0
                + " exposure=" + Math.round(best.exposure)
                + " lambda=" + lambda + " mu=" + mu
                + " nodes=" + best.nodes + (best.capped ? " capped" : ""));
        return true;
    }

    /** Block allocation by branch and bound. Forge's own assignment is one
     *  candidate; the search's best replaces it only when it scores higher
     *  under V(S) and Forge validates the result. Blockers with a block
     *  cost or a must-block requirement keep Forge's decision. */
    private void seeBlocks(Combat combat) {
        Player me = getPlayer();
        List<Card> attackers = new ArrayList<>();
        int incoming = 0;
        for (Card att : combat.getAttackers()) {
            GameEntity d = combat.getDefenderByAttacker(att);
            if (!(d instanceof Player) || !d.equals(me)) continue;
            Player owner = att.getController();
            if (owner != null) {
                grudge.merge(owner.getName(), Math.max(0, att.getNetPower()) * 0.5, Double::sum);
            }
            attackers.add(att);
            incoming += Math.max(0, att.getNetCombatDamage());
        }
        if (attackers.isEmpty()) { blockSkip("no-attacker-at-me"); return; }
        attackers.sort((a, b) -> Integer.compare(b.getNetCombatDamage(), a.getNetCombatDamage()));
        attackers = SeeCombat.cap(attackers);

        // Forge's assignment, split into fixed (cost or requirement) and
        // movable blockers.
        Map<Card, List<Card>> fixed = new HashMap<>();
        Map<Card, Card> stockAssign = new HashMap<>();
        List<Card> movable = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (c.isTapped()) continue;
            Card blocking = null;
            for (Card att : combat.getAttackers()) {
                if (combat.isBlocking(c, att)) { blocking = att; break; }
            }
            boolean fixedBody = false;
            try {
                if (blocking != null && (CombatUtil.getBlockCost(getGame(), c, blocking) != null
                        || CombatUtil.mustBlockAnAttacker(c, combat, null))) {
                    fixedBody = true;
                }
            } catch (Exception e) {
                fixedBody = blocking != null;
            }
            if (fixedBody || (blocking != null && !attackers.contains(blocking))) {
                if (blocking != null) fixed.computeIfAbsent(blocking, x -> new ArrayList<>()).add(c);
                continue;
            }
            if (blocking != null) stockAssign.put(c, blocking);
            movable.add(c);
        }
        if (movable.isEmpty()) { blockSkip("no-movable-blocker"); return; }
        movable.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        movable = SeeCombat.cap(movable);

        // Lift Forge's movable blocks off the combat before searching: the
        // combat-aware legality test reports a body that is already blocking
        // as unable to block, so with them in place the search could never
        // even reproduce Forge's own assignment (measured on the first smoke
        // run: best -14.4 against a stock -9.8 with one stock block).
        for (Map.Entry<Card, Card> e : stockAssign.entrySet()) {
            combat.removeBlockAssignment(e.getValue(), e.getKey());
        }
        SeeCombat see = new SeeCombat(me, plan, threatIndex);
        double mu = see.defenderLifeWeight(incoming);
        SeeCombat.BlockPlan best = see.solveBlocks(combat, attackers, movable, fixed, mu);
        // Score Forge's own assignment on the same scale.
        Map<Card, List<Card>> stockClusters = new HashMap<>();
        for (Map.Entry<Card, List<Card>> e : fixed.entrySet()) {
            stockClusters.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<Card, Card> e : stockAssign.entrySet()) {
            stockClusters.computeIfAbsent(e.getValue(), x -> new ArrayList<>()).add(e.getKey());
        }
        double stockValue = 0;
        double unblocked = 0;
        for (Card att : attackers) {
            List<Card> bs = stockClusters.getOrDefault(att, new ArrayList<>());
            stockValue += see.cluster(att, bs, mu);
            if (bs.isEmpty()) unblocked += Math.max(0, att.getNetCombatDamage());
        }
        if (me.getLife() - unblocked <= 0 && me.canLoseLife()) stockValue -= 1000.0;

        if (best.value <= stockValue + 0.01) {
            for (Map.Entry<Card, Card> e : stockAssign.entrySet()) {
                combat.addBlocker(e.getValue(), e.getKey());
            }
            agentLog.event(turnNow(), me.getName(), "see_block",
                    "kept stock blocks=" + stockAssign.size() + " value=" + Math.round(stockValue * 10) / 10.0
                    + " best=" + Math.round(best.value * 10) / 10.0 + " mu=" + mu + " nodes=" + best.nodes);
            return;
        }
        // Apply the plan, then let Forge validate it.
        for (Map.Entry<Card, Card> e : best.assign.entrySet()) {
            combat.addBlocker(e.getValue(), e.getKey());
        }
        String problem = null;
        try {
            problem = CombatUtil.validateBlocks(combat, me);
        } catch (Exception e) {
            problem = e.getClass().getSimpleName();
        }
        if (problem != null) {
            for (Map.Entry<Card, Card> e : best.assign.entrySet()) {
                combat.removeBlockAssignment(e.getValue(), e.getKey());
            }
            for (Map.Entry<Card, Card> e : stockAssign.entrySet()) {
                combat.addBlocker(e.getValue(), e.getKey());
            }
            agentLog.event(turnNow(), me.getName(), "see_block", "REVERTED " + problem);
            return;
        }
        agentLog.event(turnNow(), me.getName(), "see_block",
                "blocks=" + best.assign.size() + " stock=" + stockAssign.size()
                + " value=" + Math.round(best.value * 10) / 10.0
                + " stockValue=" + Math.round(stockValue * 10) / 10.0
                + " mu=" + mu + " nodes=" + best.nodes + (best.capped ? " capped" : ""));
    }

    /** Damage across several blockers: kill the most value, no assignment
     *  order (the Foundations rule). Trample surplus stays with Forge's
     *  split, because Forge's map carries the defender's share in a shape
     *  this override does not reproduce; the blocker portion is re-split. */
    @Override
    public java.util.Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
                                                           CardCollectionView remaining, int damage,
                                                           GameEntity defender, boolean overrideOrder) {
        java.util.Map<Card, Integer> stock = super.assignCombatDamage(attacker, blockers, remaining,
                                                                      damage, defender, overrideOrder);
        if (!useSolver() || stock == null || blockers == null || blockers.size() < 2) return stock;
        try {
            List<Card> bs = new ArrayList<>();
            int toBlockers = 0;
            for (Card b : blockers) {
                Integer d = stock.get(b);
                if (d != null) toBlockers += d;
                bs.add(b);
            }
            if (toBlockers <= 0 || bs.size() > SeeCombat.SCAN_CAP) return stock;
            SeeCombat see = new SeeCombat(getPlayer(), plan, threatIndex);
            java.util.Map<Card, Integer> split = see.splitDamage(attacker, bs, toBlockers, null);
            if (split == null) return stock;
            int sum = 0;
            for (int v : split.values()) sum += v;
            if (sum != toBlockers) return stock;
            java.util.Map<Card, Integer> out = new HashMap<>(stock);
            out.putAll(split);
            agentLog.event(turnNow(), getPlayer().getName(), "see_damage",
                    attacker.getName() + " dmg=" + toBlockers + " over=" + bs.size());
            return out;
        } catch (Exception e) {
            return stock;
        }
    }

    private void humanizeAttacks(Combat combat, boolean solved) {
        CardCollection attackers = combat.getAttackers();
        if (attackers.isEmpty()) return;
        // Candidate defenders are my living opponents (combat.getDefenders()
        // may only hold the entity the stock AI already focused).
        List<Player> defenders = new ArrayList<>();
        for (Player o : getPlayer().getOpponents()) {
            if (!o.hasLost()) defenders.add(o);
        }
        if (defenders.size() < 2) return;
        defenders.sort((a, b) -> Double.compare(threatOf(b), threatOf(a)));

        // Stage 4 — kingmaker avoidance: if the stock AI focused the weakest
        // seat while a runaway leader exists, re-aim the attack at the leader.
        // Beating down the loser while someone else wins is the classic
        // kingmaking mistake.
        kingmakerReaim(combat, attackers, defenders);
        splitAttack(combat, attackers, defenders);
        // The solver already decided who stays home.
        if (!solved) holdBackBlockers(combat);
        // Last, so no earlier pass can put an attacker back in front of a
        // blocker after this one moved it off (the split pass picks its
        // secondary by threat alone and would otherwise do exactly that).
        preferOpenTarget(combat, combat.getAttackers(), defenders);
    }

    /** Stage 2 -- send part of the attack at a second opponent. */
    private void splitAttack(Combat combat, CardCollection attackers, List<Player> defenders) {
        if (attackers.size() < 2 || rng.nextDouble() > plan.splitAttacks) {
            return;
        }
        Player secondary = defenders.get(0);
        // The stock AI's focus target keeps most attackers; the split goes to
        // the highest-threat OTHER opponent.
        GameEntity focus = combat.getDefenderByAttacker(attackers.get(0));
        if (secondary.equals(focus)) secondary = defenders.get(1);

        // Send roughly a third of attackers (weakest first) at the split target.
        List<Card> byPower = new ArrayList<>(attackers);
        byPower.sort((a, b) -> Integer.compare(a.getNetPower(), b.getNetPower()));
        int toMove = Math.max(1, attackers.size() / 3);
        int moved = 0;
        for (Card c : byPower) {
            if (toMove <= 0) break;
            GameEntity current = combat.getDefenderByAttacker(c);
            if (current == null || current.equals(secondary)) continue;
            if (!(current instanceof Player)) continue; // leave walker attacks alone
            if (CombatUtil.canAttack(c, secondary)) {
                combat.removeFromCombat(c);
                combat.addAttacker(c, secondary);
                toMove--;
                moved++;
            }
        }
        if (moved > 0) {
            agentLog.event(turnNow(), getPlayer().getName(), "split",
                    "moved=" + moved + " onto=" + secondary.getName());
        }
    }

    /** Pull some attackers back to defend.
     *
     *  Forge attacks with everything. The measured consequence is that the
     *  top reasons a block never happens are "cannot-block" and
     *  "no-untapped-creature" -- there is simply nobody home -- rather than
     *  the blocking policy.
     *
     *  The reason a human does not do this is a rules asymmetry that is much
     *  stronger in multiplayer than in a duel: an attacker TAPS and commits
     *  to ONE opponent, while an untapped creature can block whichever of the
     *  three opponents actually comes at you. Strategy sources add the
     *  political half -- attacking mostly earns retaliation, and racing ahead
     *  makes you the archenemy, so you attack when the target cannot punish
     *  you or when it is the table's real threat, and otherwise keep bodies
     *  home.
     *
     *  Mechanism only: it reads untapped creatures on public battlefields and
     *  compares power to toughness. How MUCH to hold back is plan data.
     */
    private void holdBackBlockers(Combat combat) {
        if (plan.holdBackRatio <= 0 || plan.holdBackPerThreat <= 0) return;
        CardCollection attackers = combat.getAttackers();
        if (attackers.size() < 2) return;
        // Never call off an attack that actually finishes someone.
        if (attackIsLethal(combat)) return;

        List<Card> incoming = new ArrayList<>();
        for (Player o : getPlayer().getOpponents()) {
            if (o.hasLost()) continue;
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (c.isCreature() && !c.isTapped()) incoming.add(c);
            }
        }
        if (incoming.isEmpty()) return;
        incoming.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        incoming = topSlice(incoming);

        int mine = 0;
        for (Card c : getPlayer().getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) mine++;
        }
        int want = (int) Math.min(Math.ceil(incoming.size() * plan.holdBackPerThreat),
                                  Math.floor(mine * plan.holdBackRatio));
        // Always keep attacking with something: holding back is a tax on the
        // attack, not a refusal to have a board presence.
        want = Math.min(want, attackers.size() - 1);
        if (want <= 0) return;

        List<Card> pool = new ArrayList<>(attackers);
        pool.sort((a, b) -> Integer.compare(valueOf(a), valueOf(b)));
        pool = new ArrayList<>(topSlice(pool));
        int held = 0;
        for (Card threat : incoming) {
            if (held >= want || pool.isEmpty()) break;
            Card keep = null;
            // The smallest body that still answers the biggest threat: swing
            // with the 12/12, leave the 3/3 home. A mana creature is pulled
            // first at equal value -- it should be making mana, not attacking.
            for (Card c : pool) {
                if (!CombatUtil.canBlock(threat, c)) continue;
                if (blockValue(c, threat) < 1) continue;
                if (keep == null || betterKeeper(c, keep)) keep = c;
            }
            if (keep == null) {
                // Nothing blocks it profitably, so keep a chump. This is the
                // forty-1/1-tokens case: half attack, half stay home.
                for (Card c : pool) {
                    if (!CombatUtil.canBlock(threat, c)) continue;
                    if (keep == null || betterKeeper(c, keep)) keep = c;
                }
            }
            if (keep == null) continue;
            combat.removeFromCombat(keep);
            pool.remove(keep);
            held++;
            agentLog.event(turnNow(), getPlayer().getName(), "hold_back",
                    "vs=" + threat.getNetPower() + "/" + threat.getNetToughness()
                    + " kept=" + keep.getName());
        }
    }

    /** Prefer the cheapest keeper, and a mana creature over a beater. */
    private boolean betterKeeper(Card candidate, Card current) {
        boolean cm = plan.manaCreatures.contains(candidate.getName());
        boolean rm = plan.manaCreatures.contains(current.getName());
        if (cm != rm) return cm;
        return valueOf(candidate) < valueOf(current);
    }

    /** Stage 4, 0.14.0 -- do not feed a blocker when a near-peer threat is
     *  wide open.
     *
     *  Measured need (sim_20260902_145933, game 1, turn 17): kingmakerReaim
     *  moved a 2/2 Mutavault and a 1/1 Mutable Explorer onto the seat it
     *  scored as leader (threat 16), whose only creature was an untapped 4/4
     *  commander; the Mutavault died for two damage. The seat two threat
     *  points back had Iron Maiden and Spiteful Visions on the table and no
     *  creature at all. Every human at the table attacks the open punisher.
     *
     *  Mechanism only, public zones only: for each attacker aimed at a player
     *  who has an untapped creature that can legally block it, look for the
     *  highest-threat OTHER opponent with no such blocker whose threat is at
     *  least openThreatShare of the current target's, and re-aim there. Never
     *  called off a kill. The share is plan data. */
    private void preferOpenTarget(Combat combat, CardCollection attackers,
                                  List<Player> defendersByThreat) {
        if (plan.openThreatShare <= 0) return;
        if (attackIsLethal(combat)) return;
        int moved = 0;
        String onto = null;
        for (Card c : new ArrayList<>(attackers)) {
            GameEntity current = combat.getDefenderByAttacker(c);
            if (!(current instanceof Player)) continue;
            Player cur = (Player) current;
            if (!hasUntappedBlockerFor(cur, c)) continue;   // already a free swing
            double bar = plan.openThreatShare * Math.max(1.0, threatOf(cur));
            for (Player o : defendersByThreat) {            // highest threat first
                if (o.equals(cur) || o.hasLost()) continue;
                if (threatOf(o) < bar) break;               // sorted: nothing below clears
                if (hasUntappedBlockerFor(o, c) || !CombatUtil.canAttack(c, o)) continue;
                combat.removeFromCombat(c);
                combat.addAttacker(c, o);
                moved++;
                onto = o.getName();
                break;
            }
        }
        if (moved > 0) {
            agentLog.event(turnNow(), getPlayer().getName(), "open_reaim",
                    "moved=" + moved + " onto=" + onto);
        }
    }

    /** Does this player have an untapped creature that could block `attacker`
     *  right now? Public battlefield only; uses Forge's own legality test so
     *  flying, menace, protection and the like are Forge's call, not ours. */
    private boolean hasUntappedBlockerFor(Player p, Card attacker) {
        // Forge's canBlock already refuses a tapped blocker, and honours the
        // effects that let one block anyway; the scan is capped like every
        // other combat scan here (COMBAT_SCAN_CAP), biggest bodies first.
        List<Card> pool = new ArrayList<>(p.getCreaturesInPlay());
        pool.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        for (Card b : topSlice(pool)) {
            try {
                if (CombatUtil.canBlock(attacker, b)) return true;
            } catch (Exception e) {
                return true;                                // unsure: assume defended
            }
        }
        return false;
    }

    /** Would this attack take a defender to zero? Public life totals only. */
    private boolean attackIsLethal(Combat combat) {
        Map<String, Integer> dmg = new HashMap<>();
        Map<String, Player> who = new HashMap<>();
        for (Card a : combat.getAttackers()) {
            GameEntity d = combat.getDefenderByAttacker(a);
            if (!(d instanceof Player)) continue;
            Player p = (Player) d;
            dmg.merge(p.getName(), Math.max(0, a.getNetPower()), Integer::sum);
            who.put(p.getName(), p);
        }
        for (Map.Entry<String, Integer> e : dmg.entrySet()) {
            Player p = who.get(e.getKey());
            if (p != null && e.getValue() >= p.getLife()) return true;
        }
        return false;
    }

    /** Stage 4, generalized for task 23 — aim the attack at the table's
     *  leader whenever the leader decisively out-threatens whoever the stock
     *  AI actually targeted, not only when it picked the WEAKEST seat.
     *
     *  Measured need (run sim_20260829_220838, 8 games): attacks received
     *  were Living Energy 49, Skrat 34, Kilo 31, Ur-Dragon 20 -- and
     *  Ur-Dragon won 4 of 8. Pairs fed grudge feuds while the scariest deck
     *  ate the fewest attacks. The weakest-seat-only rule fired 4 times in
     *  8 games; it could not see a feud, because a feud partner is never the
     *  weakest seat (grudge keeps its threat score up).
     *
     *  Threat ratio and the dial stay plan data. The lethal guard stays: a
     *  kill is never called off. */
    private void kingmakerReaim(Combat combat, CardCollection attackers,
                                List<Player> defendersByThreat) {
        if (plan.kingmakerRatio <= 0) return;
        // Never re-aim away from a kill. holdBackBlockers has carried this
        // guard since 0.7.0; this path lacked it, so a lethal swing at the
        // weakest seat could be redirected onto the leader and the kill
        // given up -- the one attack a human never calls off.
        if (attackIsLethal(combat)) return;
        Player leader = defendersByThreat.get(0);
        double leaderThreat = threatOf(leader);
        int moved = 0;
        for (Card c : new ArrayList<>(attackers)) {
            GameEntity current = combat.getDefenderByAttacker(c);
            if (!(current instanceof Player) || current.equals(leader)) continue;
            double t = Math.max(1.0, threatOf((Player) current));
            if (leaderThreat < plan.kingmakerRatio * t) continue;
            if (CombatUtil.canAttack(c, leader)) {
                combat.removeFromCombat(c);
                combat.addAttacker(c, leader);
                moved++;
            }
        }
        if (moved > 0) {
            agentLog.event(turnNow(), getPlayer().getName(), "kingmaker_reaim",
                    "moved=" + moved + " onto=" + leader.getName()
                    + " leaderThreat=" + Math.round(leaderThreat));
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        super.declareBlockers(defender, combat);
        try {
            if (useSolver()) seeBlocks(combat);
            else humanizeBlocks(combat);
        } catch (Exception e) {
            agentLog.event(turnNow(), getPlayer().getName(), "block_error",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void humanizeBlocks(Combat combat) {
        Player me = getPlayer();
        List<Card> unblocked = new ArrayList<>();
        int incoming = 0;
        for (Card att : combat.getAttackers()) {
            GameEntity d = combat.getDefenderByAttacker(att);
            if (!(d instanceof Player) || !d.equals(me)) continue;
            // Stage 4 — grudge memory: remember who points damage at me.
            // Public combat declarations only; hands and libraries stay unread.
            Player owner = att.getController();
            if (owner != null) {
                grudge.merge(owner.getName(),
                        Math.max(0, att.getNetPower()) * 0.5, Double::sum);
            }
            if (combat.getBlockers(att).isEmpty()) {
                unblocked.add(att);
                incoming += Math.max(0, att.getNetPower());
            }
        }
        if (unblocked.isEmpty()) { blockSkip("no-unblocked-attacker"); return; }
        boolean inDanger = me.getLife() - incoming <= plan.dangerLife;
        // blockiness is now used DIRECTLY as P(engage). It used to be scaled
        // by 0.4, which with the 0.6 default meant the agent declined to block
        // at all in 76% of combats. Measured consequence: Forge blocks 14.7%
        // of attacking creatures over 2128 decisions, so ~85% of attackers
        // walk through. A human table blocks far more than that, and the gap
        // inflates every deck that wins by attacking.
        if (!inDanger && rng.nextDouble() > plan.blockiness) { blockSkip("blockiness-roll"); return; }

        Set<Card> busy = new HashSet<>();
        for (Card att : combat.getAttackers()) {
            for (Card b : combat.getBlockers(att)) busy.add(b);
        }
        List<Card> free = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !c.isTapped() && !busy.contains(c)) free.add(c);
        }
        if (free.isEmpty()) { blockSkip("no-untapped-creature"); return; }

        // Biggest attacker first, and block as many as the plan allows rather
        // than exactly one.
        unblocked.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        // Cheapest first, so the top slice holds the bodies worth spending.
        free.sort((a, b) -> Integer.compare(valueOf(a), valueOf(b)));
        unblocked = topSlice(unblocked);
        free = topSlice(free);
        int made = 0;
        for (Card att : unblocked) {
            if (free.isEmpty() || made >= plan.blockMax) break;
            if (!inDanger && att.getNetPower() < plan.blockPowerFloor) continue;
            Card best = null;
            int bestScore = -1;
            for (Card b : free) {
                if (!CombatUtil.canBlock(att, b)) continue;
                int score = blockValue(b, att);
                // Cheapest among equals: never spend a bomb where a bear does.
                if (score > bestScore
                        || (score == bestScore && best != null
                            && valueOf(b) < valueOf(best))) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best == null) { blockSkip("cannot-block:" + att.getName()); continue; }
            // A block that neither kills nor survives is a chump. Humans do it
            // under pressure and occasionally otherwise; the rate is data.
            if (bestScore == 0 && !inDanger && rng.nextDouble() > plan.chumpiness) {
                continue;
            }
            combat.addBlocker(att, best);
            free.remove(best);
            made++;
            agentLog.event(turnNow(), getPlayer().getName(), "added_block",
                    "value=" + bestScore + (inDanger ? " danger" : "")
                    + " blocker=" + best.getName() + " on=" + att.getName());
        }
    }

    // ------------------------------------------------------------------
    // Stage 3 — interaction. The SimLabHuman profile makes stock AI *want*
    // to counter everything; this veto lets only real threats through, so
    // counterspells are held for spells that matter.
    // ------------------------------------------------------------------

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> stock = super.chooseSpellAbilityToPlay();
        try {
            stock = comboPriority(stock);
        } catch (Exception e) {
            // pursuit is an upgrade, never a requirement — stock stands
        }
        try {
            stock = finisherDiscipline(stock);
        } catch (Exception e) {
            // same posture: a failed gate lets the stock pick stand
        }
        try {
            stock = instantDiscipline(stock);
        } catch (Exception e) {
            // same posture: a failed gate lets the stock pick stand
        }
        try {
            stock = protectionDiscipline(stock);
        } catch (Exception e) {
            // same posture: a failed gate lets the stock pick stand
        }
        try {
            if (stock == null || stock.isEmpty()) return stock;
            SpellAbility sa = stock.get(0);
            if (sa.getApi() != ApiType.Counter) return stock;
            SpellAbility target = sa.getTargets() == null
                    ? null : sa.getTargets().getFirstTargetedSpell();
            if (target == null) return stock;
            Player caster = target.getActivatingPlayer();
            if (caster == null || !caster.isOpponentOf(getPlayer())) return stock;
            double threat = threatOfSpell(target);
            String what = target.getHostCard() == null ? "?" : target.getHostCard().getName();
            // Stage 4 — politics: in a pod, let someone else spend their
            // interaction first. Another opponent with open mana raises the
            // bar for firing mine — unless the caster is the table's leader,
            // whose win attempt I answer regardless.
            double bar = plan.counterThreshold;
            if (plan.politics > 0 && !isTableLeader(caster)
                    && othersHoldOpenMana(caster)) {
                bar += plan.politics * 2.0;
            }
            if (threat >= bar) {
                agentLog.event(turnNow(), getPlayer().getName(), "counter_fire",
                        what + " threat=" + threat + " bar=" + bar);
                return stock; // counter the win attempt
            }
            // Chaff: hold the counter (small chance to fire anyway — humans
            // get twitchy).
            if (rng.nextDouble() < 0.1) return stock;
            agentLog.event(turnNow(), getPlayer().getName(), "counter_veto",
                    what + " threat=" + threat);
            return null;
        } catch (Exception e) {
            return stock;
        }
    }

    private double threatOfSpell(SpellAbility target) {
        Card host = target.getHostCard();
        if (host == null) return plan.counterThreshold; // unknown: allow
        Integer idx = threatIndex.get(host.getName());
        double score = idx != null ? idx : Math.min(4, host.getCMC());
        // A known threat from a developed board is scarier.
        Player caster = target.getActivatingPlayer();
        if (idx != null && caster != null && threatOf(caster) > 12) score += 2;
        // The spell that COMPLETES the visible part of its caster line is the
        // win attempt itself: the exact case the counter veto exists to
        // answer (interrupt the player executing their gameplan too well).
        // Lines are the caster plan data; the board read is public.
        if (caster != null && tablePlans != null) {
            DeckPlan theirs = tablePlans.get(caster.getName());
            if (theirs != null && !theirs.lines.isEmpty()) {
                Set<String> board = new HashSet<>();
                for (Card c : caster.getCardsIn(ZoneType.Battlefield)) {
                    board.add(c.getName());
                }
                for (Set<String> line : theirs.lines) {
                    if (line.size() < 2 || !line.contains(host.getName())) continue;
                    boolean rest = true;
                    for (String piece : line) {
                        if (!piece.equals(host.getName()) && !board.contains(piece)) {
                            rest = false;
                            break;
                        }
                    }
                    if (rest) {
                        agentLog.event(turnNow(), getPlayer().getName(),
                                "line_completion_seen", caster.getName()
                                + " casting " + host.getName());
                        return Math.max(score, 10);
                    }
                }
            }
        }
        return score;
    }

    // ------------------------------------------------------------------
    // Stage 4 — optional-trigger imperfection. Humans miss triggers; the
    // agent models that in CHOICES only. The isOptionalTrigger() guard is
    // the hard rule: a mandatory trigger can never be declined, so no
    // illegal games. The stock answer stands unless it was a yes we can
    // legally turn into a no.
    // ------------------------------------------------------------------

    /** Every card named by any line in the plan, flattened once. */
    private Set<String> lineCards() {
        if (lineCards == null) {
            Set<String> all = new HashSet<>();
            for (Set<String> line : plan.lines) all.addAll(line);
            lineCards = all;
        }
        return lineCards;
    }

    private Set<String> lineCards;

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        boolean stock = super.confirmTrigger(wrapper);
        try {
            if (stock && plan.triggerMiss > 0 && wrapper.isOptionalTrigger()
                    && rng.nextDouble() < plan.triggerMiss) {
                String what = wrapper.getHostCard() == null
                        ? "?" : wrapper.getHostCard().getName();
                // A miss roll on a card the plan names as a combo piece is not
                // human imperfection, it is a fizzle. An iterating "you may"
                // loop re-asks this question every iteration, so a per-check
                // 3% miss halts the loop after a median ~23 iterations, every
                // game: the deck assembles its win and then stops. Nobody
                // piloting a combo forgets their own loop mid-loop. The dial
                // still applies to every other optional trigger.
                if (lineCards().contains(what)) {
                    agentLog.event(turnNow(), getPlayer().getName(),
                            "trigger_protected", what);
                    return stock;
                }
                agentLog.event(turnNow(), getPlayer().getName(),
                        "trigger_miss", what);
                return false;
            }
        } catch (Exception e) {
            // fall through to the stock answer
        }
        return stock;
    }

    // ------------------------------------------------------------------
    // Stage 5 — gated combo pursuit. The line-of-sight gate is the design
    // rule: pursuit activates only when a known line is nearly done (every
    // piece on my battlefield or in MY OWN hand, or exactly one piece short
    // with a tutor in hand). Otherwise the agent plays its normal game.
    // Combat code paths are untouched — pursuit never trades damage or
    // blocks for pieces. Reads my battlefield, my hand, my command zone:
    // all legal knowledge for the player; opponents' hidden zones stay
    // unread.
    // ------------------------------------------------------------------

    /** One nearly-complete line, or null when no line has line of sight. */
    private static final class Sight {
        Set<String> line;                 // the piece names
        Set<String> onBoard;              // pieces already on my battlefield
        Set<String> owned;                // pieces in hand / command zone
        String missingOutside;            // the one piece not owned, or null
    }

    private int holdTurn = -1;            // turn we chose to hold the last piece
    private final Map<String, Integer> castTries = new HashMap<>();

    private Set<String> myNamesIn(ZoneType z) {
        Set<String> names = new HashSet<>();
        for (Card c : getPlayer().getCardsIn(z)) names.add(c.getName());
        return names;
    }

    private Sight lineOfSight() {
        return lineOfSight(false);
    }

    /**
     * @param searchInFlight a library search THIS seat controls is resolving
     *     right now. It satisfies the same condition a tutor in hand does, and
     *     has to be passed in: by the time a search resolves its own card has
     *     left the hand for the stack, so recomputing the gate from zones alone
     *     reads it as closed. That is what made tutor steering dead code —
     *     164 searches observed, 0 steers — because the only gate that opens
     *     one-piece-short pursuit is exactly the one a resolving tutor closes.
     */
    private Sight lineOfSight(boolean searchInFlight) {
        if (plan.lines.isEmpty()) return null;
        Set<String> board = myNamesIn(ZoneType.Battlefield);
        Set<String> hand = myNamesIn(ZoneType.Hand);
        hand.addAll(myNamesIn(ZoneType.Command)); // a commander piece is always castable
        boolean tutorInHand = searchInFlight;
        for (String t : plan.tutors) {
            if (hand.contains(t)) { tutorInHand = true; break; }
        }
        Sight best = null;
        int bestOutside = Integer.MAX_VALUE;
        int bestToCast = Integer.MAX_VALUE;
        for (Set<String> line : plan.lines) {
            Set<String> onBoard = new HashSet<>();
            Set<String> owned = new HashSet<>();
            List<String> outside = new ArrayList<>();
            for (String piece : line) {
                if (board.contains(piece)) onBoard.add(piece);
                else if (hand.contains(piece)) owned.add(piece);
                else outside.add(piece);
            }
            if (onBoard.size() == line.size()) continue; // assembled — done here
            boolean clear = outside.isEmpty()
                    || (outside.size() == 1 && tutorInHand);
            if (!clear) continue;
            int toCast = line.size() - onBoard.size();
            if (outside.size() < bestOutside
                    || (outside.size() == bestOutside && toCast < bestToCast)) {
                best = new Sight();
                best.line = line;
                best.onBoard = onBoard;
                best.owned = owned;
                best.missingOutside = outside.isEmpty() ? null : outside.get(0);
                bestOutside = outside.size();
                bestToCast = toCast;
            }
        }
        return best;
    }

    /** Prefer casting a piece of the sighted line when the stock choice is
     *  idle or lower-weight. Legality and cost stay Forge's: only abilities
     *  that canPlay() and canPayCost() are ever substituted. */
    private List<SpellAbility> comboPriority(List<SpellAbility> stock) {
        // Pursuit only acts on an empty stack: whatever the stock AI wants
        // to do in response to a spell (protect the board, counter, trick)
        // always stands.
        if (!getGame().getStackZone().isEmpty()) return stock;
        Sight sight = lineOfSight();
        if (sight == null) return stock;
        SpellAbility stockSa = (stock == null || stock.isEmpty()) ? null : stock.get(0);
        int turn = turnNow();
        boolean stockBurnsPiece = false;
        if (stockSa != null) {
            // Never pre-empt a land drop or interaction.
            if (stockSa.isLandAbility() || stockSa.getApi() == ApiType.Counter) return stock;
            Card host = stockSa.getHostCard();
            if (host != null && sight.line.contains(host.getName())) {
                boolean completes = sight.missingOutside == null
                        && sight.onBoard.size() + 1 == sight.line.size();
                if (host.isPermanent() || completes) return stock; // developing or firing
                // Stock wants to burn a one-shot piece early (measured: it
                // casts Rite of Replication as a value play with Scourge
                // still in hand). Line discipline: veto, look for a better
                // cast below, else pass this window and keep the piece.
                //
                // ONLY when the rest of the line is actually owned. Many line
                // pieces are premium value spells in their own right (measured
                // on 31 cEDH games: every one of the 31 early-burn vetoes was
                // Tainted Pact or Jeska's Will), and holding one for a line
                // whose other pieces are still somewhere in the library trades
                // real value now for a speculative combo later. A human holds
                // Tainted Pact when Thassa's Oracle is IN HAND, and casts it
                // as an answer when the line is not close.
                stockBurnsPiece = sight.missingOutside == null;
            }
        }
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) {
            String name = c.getName();
            if (!sight.line.contains(name) || sight.onBoard.contains(name)) continue;
            // A card that keeps failing to actually play this turn is stuck
            // (odd cost, timing edge) — stop re-choosing it.
            String tryKey = turn + ":" + name;
            if (castTries.getOrDefault(tryKey, 0) >= 2) continue;
            boolean completes = sight.missingOutside == null
                    && sight.onBoard.size() + 1 == sight.line.size();
            // A permanent piece can develop early; an instant/sorcery piece
            // is a one-shot and only fires when it completes the line —
            // casting it sooner burns the piece for nothing.
            if (!c.isPermanent() && !completes) continue;
            SpellAbility castSa = castableSpell(c);
            if (castSa == null) continue;
            if (completes && shouldHoldLastPiece(turn)) {
                agentLog.event(turn, getPlayer().getName(), "combo_hold",
                        name + " vs open enemy mana (greed=" + plan.greed + ")");
                return stock;
            }
            if (stockSa == null
                    || plan.weightOf(hostName(stockSa)) < plan.weightOf(name)) {
                castTries.merge(tryKey, 1, Integer::sum);
                agentLog.event(turn, getPlayer().getName(), "combo_cast",
                        name + " (" + sight.onBoard.size() + "/" + sight.line.size()
                        + " online)");
                List<SpellAbility> out = new ArrayList<>();
                out.add(castSa);
                return out;
            }
        }
        // One piece short with a tutor in hand: the stock AI sits on generic
        // tutors (measured: Diabolic Tutor drawn, never cast), so getting the
        // missing piece means casting the tutor is the plan. steerSearch()
        // then picks the piece when the search resolves.
        if (sight.missingOutside != null) {
            for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) {
                String name = c.getName();
                if (!plan.tutors.contains(name)) continue;
                String tryKey = turn + ":" + name;
                if (castTries.getOrDefault(tryKey, 0) >= 2) continue;
                SpellAbility castSa = castableSpell(c);
                if (castSa == null) continue;
                if (stockSa == null
                        || plan.weightOf(hostName(stockSa)) < plan.weightOf(name)) {
                    castTries.merge(tryKey, 1, Integer::sum);
                    agentLog.event(turn, getPlayer().getName(), "tutor_cast",
                            name + " seeking " + sight.missingOutside);
                    List<SpellAbility> out = new ArrayList<>();
                    out.add(castSa);
                    return out;
                }
            }
        }
        if (stockBurnsPiece) {
            agentLog.event(turn, getPlayer().getName(), "combo_hold",
                    hostName(stockSa) + " kept for the line (early burn vetoed)");
            return null; // pass this window rather than waste the piece
        }
        return stock;
    }

    /** Stage 3, 0.14.0 -- hold a finisher until the board it needs exists.
     *
     *  The plan already says which spells are finishers and how many
     *  creatures they want (search.context: {"hint":"finisher",
     *  "minCreatures":3}); until now the shim read that only when steering a
     *  library search. Stock Forge casts Triumph of the Hordes as a pump spell
     *  (measured, sim_20260902_145933: four casts, boards of one or two
     *  creatures, best case five poison). A human holds it for the swing that
     *  wins. Mechanism: when the stock pick is a one-shot finisher-hinted
     *  spell and my creature count is below the plan's minimum, cast the best
     *  other spell in hand instead, else pass this window. A lethal-looking
     *  board (my power on the table >= some opponent's life) always casts. */
    private List<SpellAbility> finisherDiscipline(List<SpellAbility> stock) {
        if (stock == null || stock.isEmpty()) return stock;
        if (!getGame().getStackZone().isEmpty()) return stock;
        // My own combat with attackers declared: a pump on attackers that are
        // already past blockers is the one window a finisher exists for.
        Combat combat = getGame().getCombat();
        if (combat != null && getPlayer().equals(combat.getAttackingPlayer())
                && !combat.getAttackers().isEmpty()) return stock;
        SpellAbility sa = stock.get(0);
        if (sa.isLandAbility() || !sa.isSpell()) return stock;
        Card host = sa.getHostCard();
        if (host == null || host.isPermanent()) return stock;
        String name = host.getName();
        if (!"finisher".equals(plan.targetHint.get(name))) return stock;
        Integer need = plan.targetMinCreatures.get(name);
        if (need == null) return stock;
        int have = 0, power = 0;
        for (Card c : getPlayer().getCreaturesInPlay()) {
            have++;
            power += Math.max(0, c.getNetPower());
        }
        if (have >= need) return stock;
        for (Player o : getPlayer().getOpponents()) {
            if (!o.hasLost() && power >= o.getLife()) return stock; // it wins now
        }
        int turn = turnNow();
        SpellAbility other = null;
        int otherW = -1;
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(name)) continue;
            if ("finisher".equals(plan.targetHint.get(c.getName()))) continue;
            String tryKey = turn + ":" + c.getName();
            if (castTries.getOrDefault(tryKey, 0) >= 2) continue;
            SpellAbility castSa = castableSpell(c);
            if (castSa == null) continue;
            int w = plan.weightOf(c.getName());
            if (other == null || w > otherW) {
                other = castSa;
                otherW = w;
            }
        }
        agentLog.event(turn, getPlayer().getName(), "finisher_hold",
                name + " creatures=" + have + " need=" + need
                + (other == null ? " pass" : " instead=" + hostName(other)));
        if (other == null) return null;
        castTries.merge(turn + ":" + hostName(other), 1, Integer::sum);
        List<SpellAbility> out = new ArrayList<>();
        out.add(other);
        return out;
    }

    private SpellAbility castableSpell(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (!sa.isSpell()) continue;
            try {
                sa.setActivatingPlayer(getPlayer());
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, getPlayer(), false)) {
                    return sa;
                }
            } catch (Exception e) {
                // this ability misbehaved; try the next one
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Sim Lab task 21 Half 1, 0.15.0 -- instant-speed discipline. Stock
    // Forge casts an instant-speed answer in its own main phase as soon as a
    // target clears its threshold, like a sorcery: measured on the 66-precon
    // cohort, 78% of instants go on the caster's own turn and 2.7% of all
    // spells are cast off-turn. An answer that was never dumped is still in
    // hand when a window arrives, so Half 1 is only the hold: on my own
    // turn, with an empty stack, keep the answer and cast the best other
    // spell instead (else pass the window). What counts as an answer is
    // read off Forge's own ability type at pick time; how often to hold and
    // until when are plan data. Half 2 (recognising the moment to spend it)
    // is not here; the stock AI still decides when to fire off-turn.
    // ------------------------------------------------------------------

    /** Ability types that answer an opponent's permanent. Counter is
     *  deliberately absent: a counterspell cannot be cast into an empty
     *  stack, and the Stage 3/4 veto already governs it. */
    private static final Set<ApiType> ANSWER_APIS = EnumSet.of(
            ApiType.Destroy, ApiType.DestroyAll, ApiType.DealDamage,
            ApiType.DamageAll, ApiType.Debuff, ApiType.Sacrifice,
            ApiType.ChangeZone, ApiType.ChangeZoneAll);

    private final Map<String, Boolean> holdRolls = new HashMap<>();
    private final Set<String> holdLogged = new HashSet<>();
    private int holdRollTurn = -1;

    /** Instant speed (an instant, or flash for this caster) with an
     *  answer-shaped effect. Target-free, so it can also screen the
     *  candidates for a replacement cast before their targets exist. */
    private boolean isAnswerShaped(SpellAbility sa) {
        if (sa == null || sa.isLandAbility() || !sa.isSpell()) return false;
        Card host = sa.getHostCard();
        if (host == null) return false;
        if (!host.isInstant() && !sa.withFlash(host, getPlayer())) return false;
        ApiType api = sa.getApi();
        return api != null && ANSWER_APIS.contains(api);
    }

    /** Does the chosen pick point at an opponent's permanent? Face burn
     *  (a player target) is aggression, not an answer, and is left alone.
     *  An untargeted mass effect counts as aimed at the table. */
    private boolean aimedAtOpponent(SpellAbility sa) {
        if (!sa.usesTargeting()) return true;
        TargetChoices tc = sa.getTargets();
        if (tc == null) return false;
        for (Card c : tc.getTargetCards()) {
            Player ctl = c.getController();
            if (ctl != null && ctl.isOpponentOf(getPlayer())) return true;
        }
        return false;
    }

    /** Could some opponent's creatures kill me on their next swing? Then
     *  the answer is needed now, not held. Public board only. */
    private boolean lethalOnBoard() {
        int life = getPlayer().getLife();
        for (Player o : getPlayer().getOpponents()) {
            if (o.hasLost()) continue;
            int power = 0;
            for (Card c : o.getCreaturesInPlay()) power += Math.max(0, c.getNetPower());
            if (power >= life) return true;
        }
        return false;
    }

    /** Is the top of the stack an opponent's spell or ability? On my own
     *  turn that is the only "in response" that is a real window; my own
     *  triggers resolving in my upkeep are not. */
    private boolean opponentOnStack() {
        try {
            SpellAbilityStackInstance top = getGame().getStack().peek();
            Player who = top == null ? null : top.getActivatingPlayer();
            return who != null && who.isOpponentOf(getPlayer());
        } catch (Exception e) {
            return false;
        }
    }

    /** In my own declare-blockers step, does this pick kill a blocker that
     *  would otherwise kill one of my attackers? That saves a creature and
     *  is instant speed used as intended; killing a blocker for any other
     *  reason on my own turn is the leak the hold exists to close (measured
     *  on the first 0.15.0 validation run: 5 of 12 answers went at the
     *  caster's own declare-blockers step). */
    private boolean savesMyAttacker(SpellAbility sa) {
        Combat combat = getGame().getCombat();
        if (combat == null || !getPlayer().equals(combat.getAttackingPlayer())) return false;
        TargetChoices tc = sa.getTargets();
        if (tc == null) return false;
        for (Card blocker : tc.getTargetCards()) {
            if (!combat.isBlocking(blocker)) continue;
            for (Card mine : combat.getAttackersBlockedBy(blocker)) {
                if (blocker.getNetPower() >= mine.getNetToughness()) return true;
            }
        }
        return false;
    }

    /** Table round from Forge's player-turn counter. Approximate once a
     *  seat has been eliminated, which is fine for a cutoff. */
    private int roundNow() {
        int turn = turnNow();
        int seats = getGame().getPlayers().size();
        return seats <= 1 || turn < 1 ? turn : (turn - 1) / seats + 1;
    }

    private List<SpellAbility> instantDiscipline(List<SpellAbility> stock) {
        if (stock == null || stock.isEmpty()) return stock;
        if (plan.holdInstants <= 0 && !useGates()) return stock;
        SpellAbility sa = stock.get(0);
        if (!isAnswerShaped(sa) || !aimedAtOpponent(sa)) return stock;
        String name = hostName(sa);
        String me = getPlayer().getName();
        int turn = turnNow();
        PhaseHandler ph = getGame().getPhaseHandler();
        PhaseType phase = ph.getPhase();
        boolean myTurn = ph.isPlayerTurn(getPlayer());
        boolean stackEmpty = getGame().getStackZone().isEmpty();
        boolean response = !stackEmpty && (!myTurn || opponentOnStack());
        boolean saves = myTurn && phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                && savesMyAttacker(sa);
        int round = roundNow();
        int maxHand = getPlayer().getMaxHandSize();
        // Every answer the stock pick gets to cast is recorded with the
        // reason, so own-turn spending is auditable from agent records
        // alone: the windows the hold exists to preserve (off-turn, in
        // response to an opponent, saving an attacker) and the guards that
        // let the stock pick stand (own win line, past the cutoff round,
        // the answer is needed now, the card would be discarded anyway).
        // The hand-size guard waits for main 2: measured, it fired in the
        // draw step on a hand of eight, before the main phase in which the
        // agent would have cast something else instead.
        String why = !myTurn ? "offTurn"
                : response ? "inResponse"
                : saves ? "savesAttacker"
                : (lineCards().contains(name) || plan.tutors.contains(name)) ? "ownLine"
                : (plan.holdInstantUntilRound > 0 && round > plan.holdInstantUntilRound) ? "pastCutoff"
                : getPlayer().getLife() <= plan.dangerLife ? "danger"
                : lethalOnBoard() ? "lethalOnBoard"
                : (maxHand >= 0 && getPlayer().getCardsIn(ZoneType.Hand).size() > maxHand
                        && (phase == PhaseType.MAIN2 || phase == PhaseType.END_OF_TURN)) ? "handSize"
                : null;
        // 0.16.0 priority gates (task 21 Half 2 as the Gemini note frames
        // it): off-turn, the answer waits for the opponent's end step or a
        // red-zone window; anywhere, a target under the threat floor is not
        // worth the card. A forced hold skips the P(hold) roll.
        String forced = null;
        if (useGates()) {
            String g = gateWindow(sa, why, myTurn, response, phase);
            if (g != null && g.startsWith("hold:")) {
                forced = g.substring(5);
                why = null;
            } else if (g != null) {
                why = g;
            }
        }
        if (why != null) {
            Player whose = ph.getPlayerTurn();
            agentLog.event(turn, me, "instant_window", name + " phase=" + phase
                    + (myTurn ? " ownTurn" : " turnOf=" + (whose == null ? "?" : whose.getName()))
                    + " why=" + why);
            return stock;
        }
        // One roll per card per turn: a per-priority re-roll would leak the
        // card out within a few windows at any dial below 1.
        if (holdRollTurn != turn) {
            holdRolls.clear();
            holdLogged.clear();
            holdRollTurn = turn;
        }
        Boolean hold = holdRolls.get(name);
        if (forced != null) {
            hold = Boolean.TRUE;
        } else if (hold == null) {
            hold = rng.nextDouble() < plan.holdInstants;
            holdRolls.put(name, hold);
        }
        if (!hold) {
            if (holdLogged.add(name + "@roll")) {
                agentLog.event(turn, me, "instant_window", name + " phase=" + phase
                        + " ownTurn why=roll");
            }
            return stock;
        }
        SpellAbility other = bestOtherSpell(name, turn);
        // Forge re-asks several times per phase (measured: six holds of one
        // card inside one draw step); one record per card per phase says
        // the same thing.
        if (holdLogged.add(name + "@" + phase)) {
            Player whose = ph.getPlayerTurn();
            agentLog.event(turn, me, "instant_hold", name + " phase=" + phase + " round=" + round
                    + (myTurn ? "" : " turnOf=" + (whose == null ? "?" : whose.getName()))
                    + (forced == null ? "" : " gate=" + forced)
                    + (other == null ? " pass" : " instead=" + hostName(other)));
        }
        if (other == null) return null;
        castTries.merge(turn + ":" + hostName(other), 1, Integer::sum);
        List<SpellAbility> out = new ArrayList<>();
        out.add(other);
        return out;
    }

    // ------------------------------------------------------------------
    // 0.16.0 -- stack and priority gates (Sim Lab engine A/B; the Gemini
    // architecture note's phase 3, built inside the shim boundary). Off
    // unless the plan's priorityGates dial says so. Forge still decides
    // WHAT it would cast; these decide only WHETHER the window is right.
    // ------------------------------------------------------------------

    /** Decide the window for an instant-speed answer the stock AI wants to
     *  cast. Returns an allow reason, or "hold:<reason>" to hold. `why` is
     *  the 0.15.0 chain's verdict (null = it would hold on its own turn). */
    private String gateWindow(SpellAbility sa, String why, boolean myTurn, boolean response,
                              PhaseType phase) {
        // Reasons that must never be second-guessed.
        if ("inResponse".equals(why) || "savesAttacker".equals(why) || "ownLine".equals(why)
                || "danger".equals(why) || "lethalOnBoard".equals(why)) {
            return why;
        }
        if (!myTurn) {
            // The End-Step Rule and the Red-Zone Interception Rule.
            String window = response ? "inResponse"
                    : phase == PhaseType.END_OF_TURN ? "endStep"
                    : (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && redZone(sa)) ? "redZone"
                    : getPlayer().getLife() <= plan.dangerLife ? "danger"
                    : lethalOnBoard() ? "lethalOnBoard"
                    : null;
            if (window == null) return "hold:untilEndStep";
            if ("endStep".equals(window) && belowThreatFloor(sa)) return "hold:lowThreat";
            return window;
        }
        // Own turn: the 0.15.0 chain already holds unless a guard fired.
        if (why == null) return null;
        // A guard let it through (pastCutoff, handSize, roll): the target
        // still has to be worth the card.
        if (belowThreatFloor(sa)) return "hold:lowThreat";
        return why;
    }

    /** Composite threat of the pick's target against the plan's floor.
     *  Untargeted (mass) effects and player targets are never floored. */
    private boolean belowThreatFloor(SpellAbility sa) {
        if (plan.removalThreatFloor <= 0) return false;
        Double s = threatScore(sa);
        if (s == null) return false;
        return s < plan.removalThreatFloor;
    }

    /** S_threat(T): body, the table's threat index for the card (engines,
     *  punishers, commanders and payoffs live there), mana tempo of the
     *  exchange, plus a little for the table leader's things. Null when
     *  the pick has no card target of an opponent's. */
    private Double threatScore(SpellAbility sa) {
        if (!sa.usesTargeting()) return null;
        TargetChoices tc = sa.getTargets();
        if (tc == null) return null;
        int spellCmc = sa.getHostCard() == null ? 0 : sa.getHostCard().getCMC();
        try {
            if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null) {
                spellCmc = sa.getPayCosts().getTotalMana().getCMC();
            }
        } catch (Exception e) {
            // keep the printed mana value
        }
        Double best = null;
        for (Card t : tc.getTargetCards()) {
            Player ctl = t.getController();
            if (ctl == null || !ctl.isOpponentOf(getPlayer())) continue;
            double s = t.isCreature() ? Math.max(0, t.getNetPower()) : 0;
            Integer idx = threatIndex.get(t.getName());
            if (idx != null) s += idx;
            s += plan.threatTempoWeight * (t.getCMC() - spellCmc);
            if (t.isCommander()) s += 3;
            if (t.isPlaneswalker()) s += 2;
            if (isTableLeader(ctl)) s += 2;
            if (best == null || s > best) best = s;
        }
        return best;
    }

    /** Red zone: on an opponent's declare-blockers step, does this pick
     *  answer an attacker aimed at me that my blocks do not already handle
     *  (unblocked, or blocked only by bodies that die to it)? */
    private boolean redZone(SpellAbility sa) {
        Combat combat = getGame().getCombat();
        if (combat == null) return false;
        TargetChoices tc = sa.getTargets();
        if (tc == null) return false;
        Player me = getPlayer();
        for (Card t : tc.getTargetCards()) {
            if (!combat.isAttacking(t)) continue;
            GameEntity d = combat.getDefenderByAttacker(t);
            if (!(d instanceof Player) || !d.equals(me)) continue;
            CardCollection bs = combat.getBlockers(t);
            if (bs.isEmpty()) return true;
            boolean handled = false;
            for (Card b : bs) {
                try {
                    if (!ComputerUtilCombat.blockerWouldBeDestroyed(me, b, combat)) handled = true;
                } catch (Exception e) {
                    handled = true;
                }
            }
            if (!handled) return true;
        }
        return false;
    }

    /** Protection, regeneration, phasing, damage prevention, fog, and pumps
     *  or bounce aimed at my own permanents: the Response-Gated Protection
     *  Rule holds them unless something hostile is on the stack or a body
     *  of mine is in a combat it would lose. */
    private static final Set<ApiType> PROTECT_APIS = EnumSet.of(
            ApiType.Protection, ApiType.ProtectionAll, ApiType.Regenerate,
            ApiType.Phases, ApiType.PreventDamage, ApiType.Fog);
    private static final Set<ApiType> SELF_AIMED_APIS = EnumSet.of(
            ApiType.Pump, ApiType.PumpAll, ApiType.ChangeZone);

    private boolean isProtectShaped(SpellAbility sa) {
        if (sa == null || sa.isLandAbility() || !sa.isSpell()) return false;
        Card host = sa.getHostCard();
        if (host == null) return false;
        if (!host.isInstant() && !sa.withFlash(host, getPlayer())) return false;
        ApiType api = sa.getApi();
        if (api == null) return false;
        if (PROTECT_APIS.contains(api)) return true;
        if (!SELF_AIMED_APIS.contains(api)) return false;
        // Pumps and bounce count only when pointed at my own permanent
        // (a Giant Growth on my creature, a blink on my commander).
        if (!sa.usesTargeting()) return api == ApiType.PumpAll;
        TargetChoices tc = sa.getTargets();
        if (tc == null) return false;
        for (Card c : tc.getTargetCards()) {
            if (getPlayer().equals(c.getController())) return true;
        }
        return false;
    }

    private final Set<String> protectLogged = new HashSet<>();

    private List<SpellAbility> protectionDiscipline(List<SpellAbility> stock) {
        if (!useGates() || stock == null || stock.isEmpty()) return stock;
        SpellAbility sa = stock.get(0);
        if (!isProtectShaped(sa)) return stock;
        String name = hostName(sa);
        if (lineCards().contains(name) || plan.tutors.contains(name)) return stock;
        Player me = getPlayer();
        PhaseHandler ph = getGame().getPhaseHandler();
        PhaseType phase = ph.getPhase();
        Combat combat = getGame().getCombat();
        String why = null;
        if (hostileOnStackAtMine()) {
            why = "inResponse";
        } else if (combat != null && (phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE)) {
            if (sa.getApi() == ApiType.Fog) {
                int incoming = 0;
                for (Card att : combat.getAttackers()) {
                    GameEntity d = combat.getDefenderByAttacker(att);
                    if (d instanceof Player && d.equals(me) && combat.getBlockers(att).isEmpty()) {
                        incoming += Math.max(0, att.getNetCombatDamage());
                    }
                }
                if (me.getLife() - incoming <= plan.dangerLife) why = "fogDanger";
            } else if (myBodyInCombat(sa, combat)) {
                why = "combatSave";
            }
        }
        int turn = turnNow();
        if (why != null) {
            agentLog.event(turn, me.getName(), "protect_window", name + " phase=" + phase
                    + " api=" + sa.getApi() + " why=" + why);
            return stock;
        }
        if (protectLogged.add(turn + ":" + name + "@" + phase)) {
            agentLog.event(turn, me.getName(), "protect_hold", name + " phase=" + phase
                    + " api=" + sa.getApi() + (ph.isPlayerTurn(me) ? " ownTurn" : " offTurn"));
        }
        if (!ph.isPlayerTurn(me)) return null;
        SpellAbility other = bestOtherSpell(name, turn);
        if (other == null) return null;
        castTries.merge(turn + ":" + hostName(other), 1, Integer::sum);
        List<SpellAbility> out = new ArrayList<>();
        out.add(other);
        return out;
    }

    /** Is the top of the stack an opponent's spell or ability pointed at
     *  one of my permanents (or untargeted, which could be a sweeper)? */
    private boolean hostileOnStackAtMine() {
        try {
            SpellAbilityStackInstance top = getGame().getStack().peek();
            if (top == null) return false;
            Player who = top.getActivatingPlayer();
            if (who == null || !who.isOpponentOf(getPlayer())) return false;
            SpellAbility sa = top.getSpellAbility();
            if (sa == null || !sa.usesTargeting()) return true;
            TargetChoices tc = sa.getTargets();
            if (tc == null) return true;
            for (Card c : tc.getTargetCards()) {
                if (getPlayer().equals(c.getController())) return true;
            }
            for (Player p : tc.getTargetPlayers()) {
                if (getPlayer().equals(p)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;                                    // unsure: let it cast
        }
    }

    /** Does the pick touch a creature of mine that is attacking or blocking
     *  right now? Untargeted protection counts when I have any body in. */
    private boolean myBodyInCombat(SpellAbility sa, Combat combat) {
        Player me = getPlayer();
        List<Card> mine = new ArrayList<>();
        if (sa.usesTargeting() && sa.getTargets() != null) {
            for (Card c : sa.getTargets().getTargetCards()) {
                if (me.equals(c.getController())) mine.add(c);
            }
        } else {
            mine.addAll(me.getCreaturesInPlay());
        }
        for (Card c : mine) {
            if (combat.isAttacking(c) || combat.isBlocking(c)) return true;
        }
        return false;
    }

    /** The heaviest plan-weighted spell in hand that Forge's own AI would
     *  play now, excluding the held card, other answers (holding one to dump
     *  another is no hold) and finishers (finisherDiscipline's call). Asking
     *  the AI (canPlaySa) rather than only the rules (canPlay) is what sets
     *  the candidate's targets and keeps a "cast instead" from being a
     *  spell the AI had reasons not to cast. */
    private SpellAbility bestOtherSpell(String skip, int turn) {
        SpellAbility other = null;
        int otherW = -1;
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) {
            String cn = c.getName();
            if (cn.equals(skip)) continue;
            if ("finisher".equals(plan.targetHint.get(cn))) continue;
            if (castTries.getOrDefault(turn + ":" + cn, 0) >= 2) continue;
            for (SpellAbility cand : c.getSpellAbilities()) {
                if (!cand.isSpell() || isAnswerShaped(cand)) continue;
                // Holding one gated card to dump another gated card is no hold.
                if (useGates() && isProtectShaped(cand)) continue;
                try {
                    cand.setActivatingPlayer(getPlayer());
                    if (getAi().canPlaySa(cand) != AiPlayDecision.WillPlay) continue;
                    if (!ComputerUtilCost.canPayCost(cand, getPlayer(), false)) continue;
                } catch (Exception e) {
                    continue; // this ability misbehaved; try the next one
                }
                int w = plan.weightOf(cn);
                if (other == null || w > otherW) {
                    other = cand;
                    otherW = w;
                }
                break;
            }
        }
        return other;
    }

    /** Low greed waits out open enemy mana before jamming the last piece;
     *  the decision holds for the rest of the turn, then re-rolls. */
    private boolean shouldHoldLastPiece(int turn) {
        if (holdTurn == turn) return true;
        boolean openMana = false;
        for (Player o : getPlayer().getOpponents()) {
            if (o.hasLost()) continue;
            int open = 0;
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (c.isLand() && !c.isTapped()) open++;
            }
            if (open >= 2) { openMana = true; break; }
        }
        if (openMana && rng.nextDouble() > plan.greed) {
            holdTurn = turn;
            return true;
        }
        return false;
    }

    private static String hostName(SpellAbility sa) {
        Card host = sa.getHostCard();
        return host == null ? "" : host.getName();
    }

    /** Tutor steering: when a search of my own library resolves, take the
     *  sighted line's missing piece (combo keeps absolute priority), else the
     *  plan's top-ranked target when it beats what stock chose. Forge built
     *  the option list, so every choice is legal by construction.
     *
     *  Two things this deliberately does NOT do. It never steers on keep
     *  weights ({@code targetsMode} false): that fallback exists so a
     *  pre-Stage-1 plan still yields a measurement, and Sim Lab task 20
     *  Stage 0 measured those weights choosing WORSE than stock, ranking mana
     *  rocks over payoffs. And it carries no allow-list of "good"
     *  destinations. An earlier draft had one, but for MY OWN library a
     *  higher-valued card is what I want wherever the effect puts it, and a
     *  Hand/Battlefield list silently excluded 23% of library searches
     *  including every top-of-library tutor (Vampiric, Mystical, Enlightened)
     *  and the graveyard tutors a reanimator deck is built on. Which zones
     *  are good is a property of the deck, so if it ever needs saying, it
     *  belongs in the plan JSON, not here. The ownership gate in
     *  {@link #rankSearch} is what actually keeps the dangerous searches out. */
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination,
            List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
            DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
            Player decider) {
        Card stock = super.chooseSingleCardForZoneChange(destination, origin, sa,
                fetchList, delayedReveal, selectPrompt, isOptional, decider);
        try {
            SearchRank rank = rankSearch(destination, origin, sa, fetchList, decider,
                    stock == null ? Collections.emptyList()
                                  : Collections.singletonList(stock));
            if (rank != null && stock != null) {
                String over = stock.getName();
                // Both paths require a stock pick, and the combo path's older
                // "steer over nothing" behavior is gone with it. A null answer
                // is not an absent opinion: for a ChangeNum>1 search Forge
                // runs THIS method in a loop and reads null as "stop taking
                // cards", so overriding it appends a card the search never
                // asked for. Measured cost of removing it: zero. Stock
                // declined on 0 of the 142 searches logged across Stage 2.
                if (rank.combo != null && !rank.combo.equals(stock)) {
                    logSteer(rank, "combo", rank.combo, over, rank.bestStock);
                    return rank.combo;
                }
                // Stage 2: the plan ranking acts only where combo pursuit has
                // nothing to say, and only when it is STRICTLY better than the
                // stock answer on the same scale. A tie is not a reason to
                // override an engine that sees the board.
                //
                if (rank.combo == null && rank.targetsMode
                        && rank.plan != null && !rank.plan.equals(stock)
                        && rank.planValue > rank.bestStock) {
                    logSteer(rank, "plan", rank.plan, over, rank.bestStock);
                    return rank.plan;
                }
            }
        } catch (Exception e) {
            // steering failed — the stock pick stands
        }
        return stock;
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination,
            List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
            int min, int max, DelayedReveal delayedReveal, String selectPrompt,
            Player decider) {
        List<Card> stock = super.chooseCardsForZoneChange(destination, origin, sa,
                fetchList, min, max, delayedReveal, selectPrompt, decider);
        try {
            SearchRank rank = rankSearch(destination, origin, sa, fetchList, decider,
                    stock == null ? Collections.emptyList() : stock);
            if (rank != null && stock != null) {
                if (rank.combo != null && !stock.contains(rank.combo)) {
                    logSteer(rank, "combo", rank.combo, "multi-search", rank.bestStock);
                    return withSteer(stock, rank.combo, max, -1);
                }
                // The plan path SWAPS, never grows: it replaces the weakest
                // card stock chose and only when strictly better than that
                // card. Adding a card because the search had room would
                // change how MANY cards a search takes, which is outside
                // "the agent's own search choices" — and on a pile effect
                // (Gifts Ungiven, Intuition, both live in this pod) forcing
                // your single best card into a pile the OPPONENT splits is
                // the classic way to lose with it.
                if (rank.combo == null && rank.targetsMode && rank.plan != null
                        && !stock.isEmpty() && rank.worstStockIdx >= 0
                        && !stock.contains(rank.plan)
                        && rank.planValue > rank.worstStock) {
                    logSteer(rank, "plan", rank.plan, "multi-search", rank.worstStock);
                    return withSteer(stock, rank.plan, max, rank.worstStockIdx);
                }
            }
        } catch (Exception e) {
            // steering failed — the stock pick stands
        }
        return stock;
    }

    /** {@code replaceIdx >= 0} swaps that entry, keeping the number of cards
     *  the search takes exactly as stock chose it. A negative index is combo
     *  steering's older behavior: add when there is room, else replace the
     *  last entry. */
    private static List<Card> withSteer(List<Card> stock, Card steer, int max,
                                        int replaceIdx) {
        List<Card> out = new ArrayList<>(stock);
        if (replaceIdx >= 0 && replaceIdx < out.size()) {
            out.set(replaceIdx, steer);
        } else if (out.size() < max) {
            out.add(steer);
        } else if (!out.isEmpty()) {
            out.set(out.size() - 1, steer);
        }
        return out;
    }

    private void logSteer(SearchRank rank, String mode, Card steer, String over,
                          int stockValue) {
        // Single-token fields first, names last: card names contain spaces,
        // and " over=" is the split point a parser can rely on. sid ties this
        // decision to its own search_seen — (game, turn, player) does not,
        // because a turn can resolve several searches.
        agentLog.event(turnNow(), getPlayer().getName(), "tutor_steer",
                "sid=" + rank.sid + " mode=" + mode + " value=" + rank.planValue
                + " stockValue=" + stockValue
                + " steer=" + steer.getName() + " over=" + over);
    }

    /** One search decision, computed once so the log and the choice cannot
     *  diverge. Null when this is not a library search this seat decides. */
    private static final class SearchRank {
        int sid;               // joins this decision to its own search_seen
        Card combo;            // sighted line's missing piece, absolute priority
        Card plan;             // top-ranked legal option, or null for no opinion
        int planValue;
        int bestStock;         // best value among the stock picks
        int worstStock;        // weakest of them: what a multi-steer displaces
        int worstStockIdx = -1;
        boolean targetsMode;   // ranked by plan targets, not by keep weights
    }

    /** Per-controller search counter. One seat decides its own searches on the
     *  game thread, so a plain int is enough. */
    private int searchSeq = 0;

    private SearchRank rankSearch(ZoneType destination, List<ZoneType> origin,
                                  SpellAbility sa, CardCollection fetchList,
                                  Player decider, List<Card> stockPicks) {
        if (decider != null && !decider.equals(getPlayer())) return null;
        if (origin == null || !origin.contains(ZoneType.Library)) return null;
        if (fetchList == null || fetchList.isEmpty()) return null;
        // Deciding is not owning. Forge picks the decider and the library
        // independently (ChangeZoneEffect keeps them in separate locals), so
        // "I am the chooser" happily means "of someone else's library":
        // Bribery and Acquire put an OPPONENT's creature onto my battlefield,
        // and an Intuition cast at me makes me choose from the CASTER's
        // library into the CASTER's hand. Ranking those by my own deck plan
        // is nonsense at best and hands the opponent their best card at
        // worst — and it fires easily, because a plan values none of their
        // cards, so the stock pick scores 0 and anything of mine beats it.
        // Every option must come out of my own library. This gate sits ahead
        // of combo pursuit too, which has had the same hole since 0.3.0.
        // Logged, not silent: this returns before search_seen is emitted, so
        // without a record the gate is unfalsifiable — you cannot tell it from
        // "that search never happened", and you cannot see it suppressing a
        // legitimate steer either.
        for (Card c : fetchList) {
            if (!getPlayer().equals(c.getOwner())) {
                agentLog.event(turnNow(), getPlayer().getName(), "search_skipped",
                        "reason=foreign-library options=" + fetchList.size()
                        + " owner=" + (c.getOwner() == null ? "-" : c.getOwner().getName())
                        + " src=" + (sa == null || sa.getHostCard() == null
                                     ? "-" : sa.getHostCard().getName()));
                return null;
            }
        }
        // searchInFlight=true: we are inside the resolution of a library search
        // this seat controls, so the "one piece short with a way to find it"
        // condition holds by construction, whatever zone the search card is in.
        Sight sight = lineOfSight(true);
        // The ranking uses the plan's search-target values with their context
        // gates (mode=targets), falling back to keep weights for pre-Stage-1
        // plans (mode=weights, measurement only). agree=na means no option
        // scored above the implicit floor of 1, so a ranking could not have
        // differed. Scales, gates, and tie rules are plan data; only the
        // argmax is computed here.
        SearchRank rank = new SearchRank();
        rank.sid = ++searchSeq;
        rank.targetsMode = !plan.targets.isEmpty();
        int planTop = 1;
        Card planCard = null;
        Set<String> rankedNames = new HashSet<>();
        for (Card c : fetchList) {
            String n = c.getName();
            int w = rank.targetsMode ? targetValue(n) : plan.weightOf(n);
            if (w <= 1) continue;
            rankedNames.add(n);
            if (w > planTop || (w == planTop && planCard != null
                    && n.compareTo(planCard.getName()) < 0)) {
                planTop = w;
                planCard = c;
            }
        }
        rank.plan = planCard;
        rank.planValue = planCard == null ? 0 : planTop;
        rank.worstStock = Integer.MAX_VALUE;
        StringBuilder picked = new StringBuilder();
        for (int i = 0; i < stockPicks.size(); i++) {
            Card c = stockPicks.get(i);
            if (c == null) continue;
            if (picked.length() > 0) picked.append('|');
            picked.append(c.getName());
            int w = rank.targetsMode ? targetValue(c.getName())
                                     : plan.weightOf(c.getName());
            rank.bestStock = Math.max(rank.bestStock, w);
            if (w < rank.worstStock) {
                rank.worstStock = w;
                rank.worstStockIdx = i;
            }
        }
        if (rank.worstStock == Integer.MAX_VALUE) rank.worstStock = 0;
        // A sighted line only steers if the piece it still needs is actually
        // on offer. It usually is not: a Finale of Devastation shows only
        // creatures while the missing piece is an artifact. Logging the
        // resolved pick (not just `missing`) is what lets an analyzer tell
        // "combo kept priority" apart from "combo had nothing to take".
        if (sight != null && sight.missingOutside != null) {
            for (Card c : fetchList) {
                if (sight.missingOutside.equals(c.getName())) {
                    rank.combo = c;
                    break;
                }
            }
        }
        String agree = planCard == null
                ? "na" : Boolean.toString(rank.bestStock >= planTop);
        // The denominator for tutor-target hit rate: every library search
        // this seat resolved, and whether a line was sighted at the time.
        // Names go last (they contain spaces); single-token fields first.
        agentLog.event(turnNow(), getPlayer().getName(), "search_seen",
                "sid=" + rank.sid
                + " options=" + fetchList.size()
                + " sighted=" + (sight != null)
                + " mode=" + (rank.targetsMode ? "targets" : "weights")
                + " ranked=" + rankedNames.size()
                + " agree=" + agree
                + " pickedW=" + rank.bestStock
                + " planW=" + rank.planValue
                + " dest=" + (destination == null ? "-" : destination.name())
                + " comboPick=" + (rank.combo == null ? "-" : "yes")
                + " missing=" + (sight == null ? "-" : sight.missingOutside)
                + " picked=" + (picked.length() == 0 ? "-" : picked)
                + " planPick=" + (planCard == null ? "-" : planCard.getName())
                + " src=" + (sa == null || sa.getHostCard() == null
                             ? "-" : sa.getHostCard().getName()));
        return rank;
    }

    /** A search option's value under the plan's target policy. 0 = the plan
     *  never listed it; 1 = listed but its context gate is closed right now
     *  (ramp after round beforeRound, board payoff without a board). The
     *  values and gate parameters are plan data; this only evaluates them
     *  against my own battlefield and the turn counter. */
    private int targetValue(String name) {
        Integer v = plan.targets.get(name);
        if (v == null) return 0;
        String hint = plan.targetHint.get(name);
        if ("ramp".equals(hint)) {
            Integer before = plan.targetBeforeRound.get(name);
            if (before != null && currentRound() >= before) return 1;
        } else if ("finisher".equals(hint)) {
            Integer minC = plan.targetMinCreatures.get(name);
            if (minC != null && myCreatureCount() < minC) return 1;
        }
        return v;
    }

    /** Table round: Forge's turn counter counts player turns. */
    private int currentRound() {
        try {
            int seats = Math.max(1, getGame().getRegisteredPlayers().size());
            return (Math.max(1, turnNow()) - 1) / seats + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private int myCreatureCount() {
        int n = 0;
        for (Card c : getPlayer().getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Stage 4 — table threat assessment, from PUBLIC zones only:
    // board power, threat-signature permanents, life, and grudge memory.
    // Never reads hands or libraries.
    // ------------------------------------------------------------------

    /** How many of the biggest board items a threat read counts. A human
     *  eyeballs the top of a board, not the token count: measured on the
     *  user pod, summed-width scoring made a 20-token swarm out-threat three
     *  huge dragons, and the deck that actually won (4 of 8, then 10 of 16)
     *  was attacked LEAST at the table. Same altitude as COMBAT_SCAN_CAP. */
    private static final int THREAT_EYEBALL_CAP = 5;

    private double threatOf(Player p) {
        double score = 0;
        List<Integer> pows = new ArrayList<>();
        List<Integer> idxVals = new ArrayList<>();
        Set<String> board = tablePlans != null && tablePlans.containsKey(p.getName())
                ? new HashSet<>() : null;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) pows.add(Math.max(0, c.getNetPower()));
            Integer t = threatIndex.get(c.getName());
            if (t != null) idxVals.add(t);
            if (board != null) board.add(c.getName());
        }
        // Top-of-board only, both terms: width must not out-shout quality.
        pows.sort(Collections.reverseOrder());
        for (int i = 0; i < Math.min(THREAT_EYEBALL_CAP, pows.size()); i++) {
            score += pows.get(i) * 0.5;
        }
        idxVals.sort(Collections.reverseOrder());
        for (int i = 0; i < Math.min(THREAT_EYEBALL_CAP + 3, idxVals.size()); i++) {
            score += idxVals.get(i) * 0.75;
        }
        // Opponent-line proximity: all but one piece of one of THEIR lines
        // visible on their own board is a table alarm regardless of body
        // count. Lines and the bump size are plan data.
        if (board != null && plan.lineProximity > 0) {
            DeckPlan theirs = tablePlans.get(p.getName());
            for (Set<String> line : theirs.lines) {
                if (line.size() < 2) continue;
                int have = 0;
                for (String piece : line) {
                    if (board.contains(piece)) have++;
                }
                if (have >= line.size() - 1) score += plan.lineProximity;
            }
        }
        score += Math.max(0, p.getLife() - 20) * 0.15; // healthiest player draws heat
        // Task 23 -- feud breaker. Grudge is human-real (you remember who hit
        // you) but uncapped it self-reinforces: the feud partner's threat
        // stays inflated, so the leader never clears the re-aim ratio and the
        // ping-pong continues while the real threat free-rides. grudgeCap
        // bounds grudge's contribution to a fraction of the BOARD-derived
        // score; it is plan data, default off (-1) so shipped behavior only
        // changes when the caller sends a value.
        double g = grudge.getOrDefault(p.getName(), 0.0) * plan.grudgeWeight;
        if (plan.grudgeCap >= 0) g = Math.min(g, score * plan.grudgeCap);
        score += g;
        return score;
    }

    /** Is this caster the highest-threat opponent at the table right now? */
    private boolean isTableLeader(Player caster) {
        double casterThreat = threatOf(caster);
        for (Player o : getPlayer().getOpponents()) {
            if (!o.hasLost() && !o.equals(caster) && threatOf(o) > casterThreat) {
                return false;
            }
        }
        return true;
    }

    /** Does another opponent (not the caster) hold open mana — i.e. could
     *  plausibly answer this spell instead of me? Public zones only. */
    private boolean othersHoldOpenMana(Player caster) {
        for (Player o : getPlayer().getOpponents()) {
            if (o.hasLost() || o.equals(caster)) continue;
            int open = 0;
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (c.isLand() && !c.isTapped()) open++;
            }
            if (open >= 2) return true;
        }
        return false;
    }

    /** Work cap for combat scans.
     *
     *  humanizeBlocks and holdBackBlockers both compare every candidate
     *  against every threat, and CombatUtil.canBlock is not cheap, so the
     *  cost is quadratic in board size. Measured on the cEDH pods: the agent
     *  runs 5.4 s/turn against stock's 2.7, and the games it loses to the
     *  clock average 63.8 s/turn -- big boards, not long games. That censors
     *  studies and triples the median game a user waits for.
     *
     *  Ranking first and then considering only the top slice costs nothing in
     *  quality: the biggest threats and the cheapest blockers are exactly the
     *  ones these routines were already going to pick.
     */
    private static final int COMBAT_SCAN_CAP = 12;

    private static <T> List<T> topSlice(List<T> xs) {
        return xs.size() <= COMBAT_SCAN_CAP ? xs : xs.subList(0, COMBAT_SCAN_CAP);
    }

    private void blockSkip(String why) {
        agentLog.event(turnNow(), getPlayer().getName(), "block_skip", why);
    }

    /** How good a block is, from PUBLIC board state only: 2 for killing the
     *  attacker, 1 for surviving it, 3 for both, 0 for a chump. Comparing
     *  power and toughness is mechanism; whether the agent WANTS a given
     *  quality of block is plan data (chumpiness, blockPowerFloor, blockMax). */
    private static int blockValue(Card blocker, Card attacker) {
        int score = 0;
        if (blocker.getNetPower() >= attacker.getNetToughness()) score += 2;
        if (blocker.getNetToughness() > attacker.getNetPower()) score += 1;
        return score;
    }

    private static int valueOf(Card c) {
        return c.getNetPower() + c.getCMC() + (c.isCommander() ? 20 : 0);
    }

    private int turnNow() {
        try {
            return getGame().getPhaseHandler().getTurn();
        } catch (Exception e) {
            return -1;
        }
    }
}
