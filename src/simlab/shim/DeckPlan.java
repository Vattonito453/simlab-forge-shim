/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.HashMap;
import java.util.HashSet;
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
 *                    "counterThreshold":5,"dangerLife":8}
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
