/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import forge.LobbyPlayer;
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
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
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
                         DeckPlan plan, Map<String, Integer> threatIndex, long seed,
                         AgentLog agentLog) {
        super(game, player, lobby);
        this.plan = plan;
        this.threatIndex = threatIndex;
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

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
        try {
            humanizeAttacks(combat);
        } catch (Exception e) {
            System.err.println("shim: humanizeAttacks fell back to stock: " + e);
        }
    }

    private void humanizeAttacks(Combat combat) {
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
        holdBackBlockers(combat);

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

    /** Stage 4 — move the attack off the table's weakest seat when a clear
     *  leader exists. Threat ratio and the dial come from the plan. */
    private void kingmakerReaim(Combat combat, CardCollection attackers,
                                List<Player> defendersByThreat) {
        if (plan.kingmakerRatio <= 0) return;
        Player leader = defendersByThreat.get(0);
        Player weakest = defendersByThreat.get(defendersByThreat.size() - 1);
        if (leader.equals(weakest)) return;
        double leaderThreat = threatOf(leader);
        double weakestThreat = Math.max(1.0, threatOf(weakest));
        if (leaderThreat < plan.kingmakerRatio * weakestThreat) return;
        int moved = 0;
        for (Card c : new ArrayList<>(attackers)) {
            GameEntity current = combat.getDefenderByAttacker(c);
            if (!(current instanceof Player) || !current.equals(weakest)) continue;
            if (CombatUtil.canAttack(c, leader)) {
                combat.removeFromCombat(c);
                combat.addAttacker(c, leader);
                moved++;
            }
        }
        if (moved > 0) {
            agentLog.event(turnNow(), getPlayer().getName(), "kingmaker_reaim",
                    "moved=" + moved + " off=" + weakest.getName()
                    + " onto=" + leader.getName());
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        super.declareBlockers(defender, combat);
        try {
            humanizeBlocks(combat);
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

    private double threatOf(Player p) {
        double score = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) score += Math.max(0, c.getNetPower()) * 0.5;
            Integer t = threatIndex.get(c.getName());
            if (t != null) score += t * 0.75;
        }
        score += Math.max(0, p.getLife() - 20) * 0.15; // healthiest player draws heat
        score += grudge.getOrDefault(p.getName(), 0.0) * plan.grudgeWeight;
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
