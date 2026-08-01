/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
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
    private int mullsTaken = 0;
    // Stage 4 — grudge memory: combat damage each opponent has pointed at me,
    // accumulated from PUBLIC combat declarations only. Keyed by player name
    // so it survives Forge's player-object churn between games is irrelevant
    // (one controller per game).
    private final Map<String, Double> grudge = new HashMap<>();

    PlanPlayerController(Game game, Player player, LobbyPlayer lobby,
                         DeckPlan plan, Map<String, Integer> threatIndex, long seed) {
        super(game, player, lobby);
        this.plan = plan;
        this.threatIndex = threatIndex;
        this.rng = new Random(seed);
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
            AgentLog.event(0, getPlayer().getName(), keep ? "mull_keep" : "mull_take",
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
            AgentLog.event(turnNow(), getPlayer().getName(), "split",
                    "moved=" + moved + " onto=" + secondary.getName());
        }
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
            AgentLog.event(turnNow(), getPlayer().getName(), "kingmaker_reaim",
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
            // stock blocks stand
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
        if (unblocked.isEmpty()) return;
        boolean inDanger = me.getLife() - incoming <= plan.dangerLife;
        if (!inDanger && rng.nextDouble() > plan.blockiness * 0.4) return;

        // Available blockers: my untapped creatures not already blocking.
        Set<Card> busy = new HashSet<>();
        for (Card att : combat.getAttackers()) {
            for (Card b : combat.getBlockers(att)) busy.add(b);
        }
        List<Card> free = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !c.isTapped() && !busy.contains(c)) free.add(c);
        }
        if (free.isEmpty()) return;

        // Biggest attacker first; cheapest legal blocker onto it (chump or
        // trade — a human under pressure blocks *something*).
        unblocked.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        free.sort((a, b) -> Integer.compare(valueOf(a), valueOf(b)));
        for (Card att : unblocked) {
            if (free.isEmpty()) break;
            if (!inDanger && att.getNetPower() < 4) continue; // only chump real hits
            for (Card b : new ArrayList<>(free)) {
                if (CombatUtil.canBlock(att, b)) {
                    combat.addBlocker(att, b);
                    free.remove(b);
                    AgentLog.event(turnNow(), getPlayer().getName(), "added_block",
                            b.getName() + " blocks " + att.getName()
                            + (inDanger ? " (danger)" : ""));
                    break;
                }
            }
            if (!inDanger) break; // casual blocking stops at one
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
                AgentLog.event(turnNow(), getPlayer().getName(), "counter_fire",
                        what + " threat=" + threat + " bar=" + bar);
                return stock; // counter the win attempt
            }
            // Chaff: hold the counter (small chance to fire anyway — humans
            // get twitchy).
            if (rng.nextDouble() < 0.1) return stock;
            AgentLog.event(turnNow(), getPlayer().getName(), "counter_veto",
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

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        boolean stock = super.confirmTrigger(wrapper);
        try {
            if (stock && plan.triggerMiss > 0 && wrapper.isOptionalTrigger()
                    && rng.nextDouble() < plan.triggerMiss) {
                String what = wrapper.getHostCard() == null
                        ? "?" : wrapper.getHostCard().getName();
                AgentLog.event(turnNow(), getPlayer().getName(),
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
        if (plan.lines.isEmpty()) return null;
        Set<String> board = myNamesIn(ZoneType.Battlefield);
        Set<String> hand = myNamesIn(ZoneType.Hand);
        hand.addAll(myNamesIn(ZoneType.Command)); // a commander piece is always castable
        boolean tutorInHand = false;
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
                stockBurnsPiece = true;
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
                AgentLog.event(turn, getPlayer().getName(), "combo_hold",
                        name + " vs open enemy mana (greed=" + plan.greed + ")");
                return stock;
            }
            if (stockSa == null
                    || plan.weightOf(hostName(stockSa)) < plan.weightOf(name)) {
                castTries.merge(tryKey, 1, Integer::sum);
                AgentLog.event(turn, getPlayer().getName(), "combo_cast",
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
                    AgentLog.event(turn, getPlayer().getName(), "tutor_cast",
                            name + " seeking " + sight.missingOutside);
                    List<SpellAbility> out = new ArrayList<>();
                    out.add(castSa);
                    return out;
                }
            }
        }
        if (stockBurnsPiece) {
            AgentLog.event(turn, getPlayer().getName(), "combo_hold",
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

    /** Tutor steering: when a search of my own library resolves and the
     *  sighted line's missing piece is among the legal options, take it.
     *  Forge built the option list, so the choice is legal by construction. */
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination,
            List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
            DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
            Player decider) {
        Card stock = super.chooseSingleCardForZoneChange(destination, origin, sa,
                fetchList, delayedReveal, selectPrompt, isOptional, decider);
        try {
            Card steer = steerSearch(origin, fetchList, decider);
            if (steer != null && !steer.equals(stock)) {
                AgentLog.event(turnNow(), getPlayer().getName(), "tutor_steer",
                        steer.getName() + " over "
                        + (stock == null ? "nothing" : stock.getName()));
                return steer;
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
            Card steer = steerSearch(origin, fetchList, decider);
            if (steer != null && stock != null && !stock.contains(steer)) {
                List<Card> out = new ArrayList<>(stock);
                if (out.size() < max) out.add(steer);
                else if (!out.isEmpty()) out.set(out.size() - 1, steer);
                AgentLog.event(turnNow(), getPlayer().getName(), "tutor_steer",
                        steer.getName() + " (multi-search)");
                return out;
            }
        } catch (Exception e) {
            // steering failed — the stock pick stands
        }
        return stock;
    }

    private Card steerSearch(List<ZoneType> origin, CardCollection fetchList,
                             Player decider) {
        if (decider != null && !decider.equals(getPlayer())) return null;
        if (origin == null || !origin.contains(ZoneType.Library)) return null;
        if (fetchList == null || fetchList.isEmpty()) return null;
        Sight sight = lineOfSight();
        // The denominator for tutor-target hit rate: every library search
        // this seat resolved, and whether a line was sighted at the time.
        AgentLog.event(turnNow(), getPlayer().getName(), "search_seen",
                "options=" + fetchList.size()
                + " sighted=" + (sight != null)
                + " missing=" + (sight == null ? "-" : sight.missingOutside));
        if (sight == null || sight.missingOutside == null) return null;
        for (Card c : fetchList) {
            if (sight.missingOutside.equals(c.getName())) return c;
        }
        return null;
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
