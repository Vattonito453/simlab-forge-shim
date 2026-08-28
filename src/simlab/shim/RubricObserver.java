/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

/**
 * Neutral play-quality observer.
 *
 * Scores combat decisions from the LIVE game state rather than from either
 * pilot's telemetry, so a stock Forge seat and a plan-driven seat are measured
 * on exactly the same terms. That is the whole reason it exists: stock emits no
 * agent events, so any metric derived from PlanPlayerController's own logging
 * can only ever describe our agent, which makes it useless for comparison.
 *
 * Everything here is a read. The observer never influences a decision; it is
 * registered on the event bus alongside the existing zone and turn taps.
 *
 * The 0-3 block scale mirrors PlanPlayerController.blockValue so the numbers
 * are directly comparable, but it is recomputed here from live power and
 * toughness and never read from the controller.
 */
final class RubricObserver {
    /**
     * Cap on the candidate blockers considered by the declined-block
     * accounting. The scan is attackers x blockers and CombatUtil.canBlock is
     * not cheap, so an unbounded version is quadratic on exactly the boards
     * that are already slow (token swarms). Capping the pool to the biggest
     * bodies costs nothing real: past about a dozen candidates the extra ones
     * are strictly worse blockers, and `available` still reports the true
     * count.
     */
    private static final int SCAN_CAP = 16;

    private RubricObserver() {
    }

    /** 3 = kills and survives, 2 = trade, 1 = wall, 0 = chump. */
    private static int blockValue(Card blocker, Card attacker) {
        int score = 0;
        if (blocker.getNetPower() >= attacker.getNetToughness()) score += 2;
        if (blocker.getNetToughness() > attacker.getNetPower()) score += 1;
        return score;
    }

    private static int pow(Card c) {
        return Math.max(0, c.getNetPower());
    }

    /**
     * Attack-side scoring, called when attackers are declared. Answers "how
     * much of the board went in, and what was left standing at home", the axis
     * where stock Forge is known to differ from a human table: it attacks with
     * everything.
     */
    static List<String> attack(Game game, int gameIndex, int turn) {
        List<String> out = new ArrayList<>();
        Combat combat = game == null ? null : game.getCombat();
        if (combat == null) return out;
        Player atk = combat.getAttackingPlayer();
        if (atk == null) return out;
        List<Card> attackers = new ArrayList<>();
        for (Card c : combat.getAttackers()) attackers.add(c);
        if (attackers.isEmpty()) return out;

        Set<Card> attacking = new HashSet<>(attackers);
        int attackPower = 0;
        for (Card c : attackers) attackPower += pow(c);
        Set<GameEntity> defenders = new HashSet<>();
        for (Card c : attackers) {
            GameEntity d = combat.getDefenderByAttacker(c);
            if (d != null) defenders.add(d);
        }

        // held is every untapped body left at home, which is the right pool for
        // "can this seat block next turn" -- a summoning-sick creature blocks
        // fine. heldEligible is the subset that COULD have attacked, and it is
        // the only sound denominator for a commitment ratio: counting
        // summoning-sick bodies and creatures with defender as "kept home"
        // understates commitment, which is the difference between "stock
        // attacks with everything" being false and being an artifact.
        int held = 0, heldPower = 0, heldTough = 0, heldEligible = 0;
        int heldBestTough = 0;
        for (Card c : atk.getCreaturesInPlay()) {
            if (attacking.contains(c) || c.isTapped()) continue;
            held++;
            heldPower += pow(c);
            heldTough += Math.max(0, c.getNetToughness());
            // The BIGGEST body kept home, not the total. One creature blocks
            // one attacker, so summing toughness across the bodies left behind
            // says three 1/1s can handle a 2/2 when not one of them survives
            // the block.
            heldBestTough = Math.max(heldBestTough, c.getNetToughness());
            if (CombatUtil.canAttack(c)) heldEligible++;
        }

        // What can swing back next turn: every other seat's untapped creatures.
        int backBodies = 0, backPower = 0, backBiggest = 0;
        for (Player p : game.getPlayers()) {
            if (p.equals(atk)) continue;
            for (Card c : p.getCreaturesInPlay()) {
                if (c.isTapped()) continue;
                backBodies++;
                backPower += pow(c);
                backBiggest = Math.max(backBiggest, pow(c));
            }
        }

        out.add(SimShim.obj(
            SimShim.kv("rec", "rubric"),
            SimShim.kv("kind", "attack"),
            SimShim.kvRaw("game", Integer.toString(gameIndex)),
            SimShim.kvRaw("turn", Integer.toString(turn)),
            SimShim.kv("player", atk.getName()),
            SimShim.kvRaw("attackers", Integer.toString(attackers.size())),
            SimShim.kvRaw("attackPower", Integer.toString(attackPower)),
            SimShim.kvRaw("defenders", Integer.toString(defenders.size())),
            SimShim.kvRaw("held", Integer.toString(held)),
            SimShim.kvRaw("heldEligible", Integer.toString(heldEligible)),
            SimShim.kvRaw("heldPower", Integer.toString(heldPower)),
            SimShim.kvRaw("heldTough", Integer.toString(heldTough)),
            SimShim.kvRaw("heldBestTough", Integer.toString(heldBestTough)),
            SimShim.kvRaw("backBodies", Integer.toString(backBodies)),
            SimShim.kvRaw("backPower", Integer.toString(backPower)),
            SimShim.kvRaw("backBiggest", Integer.toString(backBiggest))));
        return out;
    }

    /**
     * Block-side scoring, called once per combat after blockers are locked in.
     * Emits one record per attacked seat, so a seat that declared no blocks at
     * all still produces a record. That case is the measurement, not a gap.
     */
    static List<String> blocks(Game game, int gameIndex, int turn) {
        List<String> out = new ArrayList<>();
        Combat combat = game == null ? null : game.getCombat();
        if (combat == null) return out;
        List<Card> attackers = new ArrayList<>();
        for (Card c : combat.getAttackers()) attackers.add(c);
        if (attackers.isEmpty()) return out;
        Set<Card> attacking = new HashSet<>(attackers);

        Map<Player, List<Card>> byDefender = new LinkedHashMap<>();
        for (Card a : attackers) {
            Player d = combat.getDefenderPlayerByAttacker(a);
            if (d == null) continue;
            byDefender.computeIfAbsent(d, k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Player, List<Card>> e : byDefender.entrySet()) {
            Player def = e.getKey();
            List<Card> incoming = e.getValue();
            Set<Card> busy = new HashSet<>();
            int blocked = 0, v3 = 0, v2 = 0, v1 = 0, v0 = 0;
            int incomingPower = 0, lifeTaken = 0, freeTaken = 0;
            List<Card> unblocked = new ArrayList<>();
            for (Card a : incoming) {
                incomingPower += pow(a);
                List<Card> bs = new ArrayList<>();
                if (combat.getBlockers(a) != null) {
                    for (Card b : combat.getBlockers(a)) bs.add(b);
                }
                if (bs.isEmpty()) {
                    unblocked.add(a);
                    lifeTaken += pow(a);
                    continue;
                }
                blocked++;
                boolean free = false;
                for (Card b : bs) {
                    busy.add(b);
                    int v = blockValue(b, a);
                    if (v == 3) {
                        v3++;
                        free = true;
                    } else if (v == 2) {
                        v2++;
                    } else if (v == 1) {
                        v1++;
                    } else {
                        v0++;
                    }
                }
                if (free) freeTaken++;
            }

            // Bodies that could still have blocked: untapped, not already
            // blocking, not attacking.
            List<Card> pool = new ArrayList<>();
            for (Card c : def.getCreaturesInPlay()) {
                if (c.isTapped() || busy.contains(c) || attacking.contains(c)) continue;
                pool.add(c);
            }
            int available = pool.size();

            // Greedy assignment, biggest threat first. A body can only block
            // once, so scoring each unblocked attacker against the whole pool
            // independently would over-count the blocks left on the table.
            unblocked.sort((a, b) -> Integer.compare(pow(b), pow(a)));
            List<Card> scan = new ArrayList<>(pool);
            scan.sort((a, b) -> Integer.compare(
                    pow(b) + b.getNetToughness(), pow(a) + a.getNetToughness()));
            if (scan.size() > SCAN_CAP) scan = new ArrayList<>(scan.subList(0, SCAN_CAP));
            List<Card> freePool = new ArrayList<>(scan);
            List<Card> safePool = new ArrayList<>(scan);
            int freeMissed = 0, safeMissed = 0, legalMissed = 0;
            for (Card a : unblocked) {
                // Any legal block at all, profitable or not. Doubles as the
                // denominator for "took the damage instead of chumping" and as
                // the sanity check that canBlock is answering at this phase --
                // a broken predicate would zero the two metrics below in a way
                // indistinguishable from genuinely having no option.
                for (Card b : scan) {
                    if (CombatUtil.canBlock(a, b)) {
                        legalMissed++;
                        break;
                    }
                }
                Card pick = null;
                for (Card b : freePool) {
                    if (!CombatUtil.canBlock(a, b)) continue;
                    if (b.getNetPower() >= a.getNetToughness()
                            && b.getNetToughness() > a.getNetPower()) {
                        pick = b;
                        break;
                    }
                }
                if (pick != null) {
                    freeMissed++;
                    freePool.remove(pick);
                }
                Card safe = null;
                for (Card b : safePool) {
                    if (!CombatUtil.canBlock(a, b)) continue;
                    if (b.getNetToughness() > a.getNetPower()) {
                        safe = b;
                        break;
                    }
                }
                if (safe != null) {
                    safeMissed++;
                    safePool.remove(safe);
                }
            }

            out.add(SimShim.obj(
                SimShim.kv("rec", "rubric"),
                SimShim.kv("kind", "block"),
                SimShim.kvRaw("game", Integer.toString(gameIndex)),
                SimShim.kvRaw("turn", Integer.toString(turn)),
                SimShim.kv("player", def.getName()),
                SimShim.kvRaw("incoming", Integer.toString(incoming.size())),
                SimShim.kvRaw("incomingPower", Integer.toString(incomingPower)),
                SimShim.kvRaw("blocked", Integer.toString(blocked)),
                SimShim.kvRaw("v3", Integer.toString(v3)),
                SimShim.kvRaw("v2", Integer.toString(v2)),
                SimShim.kvRaw("v1", Integer.toString(v1)),
                SimShim.kvRaw("v0", Integer.toString(v0)),
                SimShim.kvRaw("available", Integer.toString(available)),
                SimShim.kvRaw("freeTaken", Integer.toString(freeTaken)),
                SimShim.kvRaw("freeMissed", Integer.toString(freeMissed)),
                SimShim.kvRaw("safeMissed", Integer.toString(safeMissed)),
                SimShim.kvRaw("legalMissed", Integer.toString(legalMissed)),
                SimShim.kvRaw("lifeTaken", Integer.toString(lifeTaken)),
                SimShim.kvRaw("life", Integer.toString(def.getLife()))));
        }
        return out;
    }
}
