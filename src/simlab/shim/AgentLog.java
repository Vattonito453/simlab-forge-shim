/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collector for the plan agent's own decisions. Forge's GameLog writes some
 * combat lines before PlanPlayerController's adjustments run, so the log
 * under-reports agent behavior — these records are the authoritative agent
 * telemetry. Drained into the JSONL stream after each game as
 * {"rec":"agent", "game":N, "turn":T, "event":..., ...}.
 */
final class AgentLog {

    /**
     * One instance per game, matching EventTap.
     *
     * This used to be a process-global with a single volatile game index, set
     * before each game. A game thread that survived the 15 s post-timeout
     * grace kept running and kept logging — into whatever index had since been
     * set, so a zombie's decisions were attributed to the NEXT game
     * (Sim Lab audit A9). Binding the log to the game at construction makes
     * that impossible: a straggler writes into its own game's buffer, which
     * has already been drained, so its late lines are dropped rather than
     * blamed on someone else.
     */
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final int game;

    AgentLog(int game) {
        this.game = game;
    }

    void event(int turn, String player, String event, String detail) {
        lines.add("{\"rec\":\"agent\",\"game\":" + game
                + ",\"turn\":" + turn
                + ",\"player\":\"" + esc(player) + "\""
                + ",\"event\":\"" + esc(event) + "\""
                + ",\"detail\":\"" + esc(detail) + "\"}");
    }

    void drainTo(java.io.PrintStream out) {
        synchronized (lines) {
            for (String l : lines) out.println(l);
            lines.clear();
        }
    }

    /**
     * Full JSON string escaping. The previous version handled quotes and
     * backslashes only, so a control character in a card name or a detail
     * string emitted a line no strict JSON parser would accept — and the
     * Python adapter drops unparseable lines, silently losing telemetry.
     */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
