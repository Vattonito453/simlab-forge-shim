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
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());
    private static volatile int game = 0;

    static void setGame(int g) {
        game = g;
    }

    static void event(int turn, String player, String event, String detail) {
        LINES.add("{\"rec\":\"agent\",\"game\":" + game
                + ",\"turn\":" + turn
                + ",\"player\":\"" + esc(player) + "\""
                + ",\"event\":\"" + esc(event) + "\""
                + ",\"detail\":\"" + esc(detail) + "\"}");
    }

    static void drainTo(java.io.PrintStream out) {
        synchronized (LINES) {
            for (String l : LINES) out.println(l);
            LINES.clear();
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private AgentLog() {
    }
}
