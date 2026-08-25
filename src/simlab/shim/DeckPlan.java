/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deck's play plan, parsed from the caller's plan JSON. Pure data — the
 * strategy knowledge (which cards matter, thresholds, personality) is
 * decided on the Sim Lab side; this class only carries it.
 *
 * JSON shape (per deck, keyed by Forge deck name):
 * {
 *   "mulligan":    {"minLands":2,"maxLands":5,"maxMulls":2,"keepCards":["..."]},
 *   "weights":     {"Card Name": 8, ...},
 *   "threat":      ["Card Name", ...],
 *   "personality": {"aggression":0.5,"blockiness":0.6,"splitAttacks":0.7,
 *                    "counterThreshold":5,"dangerLife":8,
 *                    "grudgeWeight":0.2,"kingmakerRatio":1.6,
 *                    "politics":0.5,"triggerMiss":0.03,"greed":0.5},
 *   "lines":       [{"cards":["Piece A","Piece B"],"produces":["..."]}],
 *   "tutors":      ["Card Name", ...]
 * }
 */
final class DeckPlan {
    final int minLands;
    final int maxLands;
    final int maxMulls;
    final Set<String> keepCards = new HashSet<>();
    final Map<String, Integer> weights = new HashMap<>();
    final Set<String> threat = new HashSet<>();

    final double aggression;
    final double blockiness;
    final double splitAttacks;
    final double counterThreshold;
    final int dangerLife;
    // Stage 4 — politics dials. Mechanisms live in PlanPlayerController;
    // these numbers arrive as data from the Sim Lab side.
    final double grudgeWeight;    // grudge points -> threat score
    final double kingmakerRatio;  // leader/focus threat ratio that re-aims attacks
    final double politics;        // how much open enemy mana raises the counter bar
    final double triggerMiss;     // P(decline) for OPTIONAL triggers only
    // Stage 5 — combo pursuit. Lines are known piece-sets (fewest pieces
    // first, as sent); tutors are the deck's nonland tutors; greed is how
    // willing the agent is to jam the last piece into open enemy mana.
    final List<Set<String>> lines = new ArrayList<>();
    final Set<String> tutors = new HashSet<>();
    final double greed;
    // Sim Lab task 20 Stage 1 — search-target values, a scale of their own
    // (weights answer "keep this hand?"; targets answer "fetch this now?").
    // Context hints are pure data; the controller checks them against its
    // own battlefield and the turn counter at search time.
    final Map<String, Integer> targets = new HashMap<>();
    final Map<String, String> targetHint = new HashMap<>();
    final Map<String, Integer> targetBeforeRound = new HashMap<>();
    final Map<String, Integer> targetMinCreatures = new HashMap<>();

    @SuppressWarnings("unchecked")
    DeckPlan(Map<String, Object> json) {
        Map<String, Object> mull = MiniJson.obj(json.get("mulligan"));
        minLands = (int) MiniJson.num(mull.get("minLands"), 2);
        maxLands = (int) MiniJson.num(mull.get("maxLands"), 5);
        maxMulls = (int) MiniJson.num(mull.get("maxMulls"), 2);
        for (Object o : MiniJson.arr(mull.get("keepCards"))) {
            if (o instanceof String) keepCards.add((String) o);
        }
        for (Map.Entry<String, Object> e : MiniJson.obj(json.get("weights")).entrySet()) {
            weights.put(e.getKey(), (int) MiniJson.num(e.getValue(), 0));
        }
        for (Object o : MiniJson.arr(json.get("threat"))) {
            if (o instanceof String) threat.add((String) o);
        }
        Map<String, Object> p = MiniJson.obj(json.get("personality"));
        aggression = MiniJson.num(p.get("aggression"), 0.5);
        blockiness = MiniJson.num(p.get("blockiness"), 0.6);
        splitAttacks = MiniJson.num(p.get("splitAttacks"), 0.7);
        counterThreshold = MiniJson.num(p.get("counterThreshold"), 5);
        dangerLife = (int) MiniJson.num(p.get("dangerLife"), 8);
        grudgeWeight = MiniJson.num(p.get("grudgeWeight"), 0.2);
        kingmakerRatio = MiniJson.num(p.get("kingmakerRatio"), 1.6);
        politics = MiniJson.num(p.get("politics"), 0.5);
        triggerMiss = MiniJson.num(p.get("triggerMiss"), 0.03);
        greed = MiniJson.num(p.get("greed"), 0.5);
        for (Object o : MiniJson.arr(json.get("lines"))) {
            Set<String> line = new HashSet<>();
            for (Object c : MiniJson.arr(MiniJson.obj(o).get("cards"))) {
                if (c instanceof String) line.add((String) c);
            }
            if (!line.isEmpty()) lines.add(line);
        }
        for (Object o : MiniJson.arr(json.get("tutors"))) {
            if (o instanceof String) tutors.add((String) o);
        }
        Map<String, Object> search = MiniJson.obj(json.get("search"));
        for (Map.Entry<String, Object> e : MiniJson.obj(search.get("targets")).entrySet()) {
            targets.put(e.getKey(), (int) MiniJson.num(e.getValue(), 0));
        }
        for (Map.Entry<String, Object> e : MiniJson.obj(search.get("context")).entrySet()) {
            Map<String, Object> c = MiniJson.obj(e.getValue());
            Object h = c.get("hint");
            if (h instanceof String) targetHint.put(e.getKey(), (String) h);
            double br = MiniJson.num(c.get("beforeRound"), -1);
            if (br > 0) targetBeforeRound.put(e.getKey(), (int) br);
            double mc = MiniJson.num(c.get("minCreatures"), -1);
            if (mc > 0) targetMinCreatures.put(e.getKey(), (int) mc);
        }
    }

    int weightOf(String cardName) {
        Integer w = weights.get(cardName);
        return w == null ? 0 : w;
    }

    /** Parse the full plans file: {"decks": {"<deck name>": {...}}}. */
    static Map<String, DeckPlan> parseAll(String jsonText) {
        Map<String, DeckPlan> out = new HashMap<>();
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(jsonText));
        for (Map.Entry<String, Object> e : MiniJson.obj(root.get("decks")).entrySet()) {
            out.put(e.getKey(), new DeckPlan(MiniJson.obj(e.getValue())));
        }
        return out;
    }

    /**
     * The table's combined threat index: card name -> the weight opponents
     * assign it when it hits the stack or battlefield. Built from every
     * seat's threat signature (public decklist knowledge — the
     * "full-decklist" familiarity level).
     */
    static Map<String, Integer> threatIndex(Map<String, DeckPlan> plans) {
        Map<String, Integer> idx = new HashMap<>();
        for (DeckPlan p : plans.values()) {
            for (String name : p.threat) {
                idx.merge(name, 8, Integer::max);
            }
            for (Map.Entry<String, Integer> w : p.weights.entrySet()) {
                if (w.getValue() >= 6) {
                    idx.merge(w.getKey(), w.getValue(), Integer::max);
                }
            }
        }
        return idx;
    }
}
