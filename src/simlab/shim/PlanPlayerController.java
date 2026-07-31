/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
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
            // stock declaration stands
        }
    }

    private void humanizeAttacks(Combat combat) {
        CardCollection attackers = combat.getAttackers();
        if (attackers.size() < 2 || rng.nextDouble() > plan.splitAttacks) {
            return;
        }
        // Player defenders only, scored by public threat.
        List<Player> defenders = new ArrayList<>();
        for (GameEntity e : combat.getDefenders()) {
            if (e instanceof Player) defenders.add((Player) e);
        }
        if (defenders.size() < 2) return;
        defenders.sort((a, b) -> Double.compare(threatOf(b), threatOf(a)));
        Player primary = defenders.get(0);
        Player secondary = defenders.get(1);

        // Send roughly a third of attackers (weakest first) at the #2 threat.
        List<Card> byPower = new ArrayList<>(attackers);
        byPower.sort((a, b) -> Integer.compare(a.getNetPower(), b.getNetPower()));
        int toMove = Math.max(1, attackers.size() / 3);
        for (Card c : byPower) {
            if (toMove <= 0) break;
            GameEntity current = combat.getDefenderByAttacker(c);
            if (current == null || current.equals(secondary)) continue;
            if (!(current instanceof Player)) continue; // leave walker attacks alone
            if (CombatUtil.canAttack(c, secondary)) {
                combat.removeFromCombat(c);
                combat.addAttacker(c, secondary);
                toMove--;
            }
        }
        // Unused for now but kept for later personality work.
        if (primary == null) return;
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
            if (stock == null || stock.isEmpty()) return stock;
            SpellAbility sa = stock.get(0);
            if (sa.getApi() != ApiType.Counter) return stock;
            SpellAbility target = sa.getTargets() == null
                    ? null : sa.getTargets().getFirstTargetedSpell();
            if (target == null) return stock;
            Player caster = target.getActivatingPlayer();
            if (caster == null || !caster.isOpponentOf(getPlayer())) return stock;
            double threat = threatOfSpell(target);
            if (threat >= plan.counterThreshold) return stock; // counter the win attempt
            // Chaff: hold the counter (small chance to fire anyway — humans
            // get twitchy).
            return rng.nextDouble() < 0.1 ? stock : null;
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
    // Stage 4 (v1) — table threat assessment, from PUBLIC zones only:
    // board power, threat-signature permanents, life. Never reads hands
    // or libraries.
    // ------------------------------------------------------------------

    private double threatOf(Player p) {
        double score = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) score += Math.max(0, c.getNetPower()) * 0.5;
            Integer t = threatIndex.get(c.getName());
            if (t != null) score += t * 0.75;
        }
        score += Math.max(0, p.getLife() - 20) * 0.15; // healthiest player draws heat
        return score;
    }

    private static int valueOf(Card c) {
        return c.getNetPower() + c.getCMC() + (c.isCommander() ? 20 : 0);
    }
}
