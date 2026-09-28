/*
 * simlab-forge-shim — programmatic Forge match driver for Sim Lab.
 *
 * Copyright (C) 2026 Vincent Attonito
 *
 * This program links Forge (https://github.com/Card-Forge/forge) and is
 * therefore licensed under the GNU General Public License v3.0 or later.
 * See the LICENSE file.
 *
 * BOUNDARY RULE (see Sim Lab's CLAUDE.md "Legal posture"): this shim is a
 * thin adapter. Strategy knowledge — deck plans, personality parameters,
 * combo lines, heuristic weights — must arrive as data from the caller and
 * never be encoded in Java here.
 */
package simlab.shim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * --scenario (0.17.1, Sim Lab repair plan WS3): seed a board with Forge's
 * own {@link GameState}, the state format and loader Forge's puzzle mode
 * uses, then let the game play on from it.
 *
 * Thin by construction. The file is Forge's state text, written by the
 * caller (Sim Lab's studies/scenarios/writer.py); this class reads it,
 * hands it to GameState once per game and reports what Forge applied. It
 * makes no decision, names no card and scores nothing: every card, zone,
 * life total, phase and turn comes from the file.
 *
 * When: the hook is Forge's own startGameHook (Match.startGame(game, hook)),
 * the entry point puzzle mode uses. Forge runs it on the game thread inside
 * PhaseHandler.setupFirstTurn: after opening hands and mulligans, after the
 * first turn's untap step began, before any player receives priority. So
 * the state lands once, before any decision, for every pilot alike (a stock
 * seat has no Sim Lab controller to call back into).
 *
 * How: GameState.applyToGame routes through GameAction.invoke, which runs
 * inline only on a thread whose name starts with "Game" and otherwise POSTS
 * the work to Forge's own game-thread pool and returns at once. The shim's
 * game thread is named shim-game-N, so applyToGame would race the game it
 * seeds. The hook already runs on the game thread, so it calls the
 * protected applyGameOnThread directly (the subclass below exists only for
 * that access). The same routing applies inside GameState to a mana pool
 * line (manapool, persistentmana): Sim Lab's writer never emits one.
 */
final class Scenario {

    final String fileName;
    final String sha256;
    private final List<String> lines;

    private Scenario(String fileName, String sha256, List<String> lines) {
        this.fileName = fileName;
        this.sha256 = sha256;
        this.lines = lines;
    }

    /** The state file's lines, from the same bytes its SHA-256 was taken
     *  of. Blank lines are dropped because GameState's line splitter reads
     *  charAt(0) and throws on an empty line. */
    static Scenario load(String path, byte[] raw, String sha256) throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        String text = new String(raw, "UTF-8");
        List<String> kept = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (!line.trim().isEmpty()) kept.add(line);
        }
        return new Scenario(p.getFileName().toString(), sha256, kept);
    }

    /** GameState with its game-thread apply reachable (protected in Forge). */
    private static final class State extends GameState {
        void applyHere(Game game) {
            applyGameOnThread(game);
        }
    }

    /**
     * The startGameHook for one game. It fills {@code record} with the
     * scenario record (applied or not) and rethrows a failed apply, so the
     * game ends as an errored result instead of playing on from a board the
     * scenario did not describe.
     */
    Runnable hook(Game game, int gameIndex, long gameStarted,
                  AtomicReference<String> record, Runnable afterApply) {
        return () -> {
            PhaseHandler ph = game.getPhaseHandler();
            String before = ph.getTurn() + " " + ph.getPhase();
            long t0 = System.currentTimeMillis();
            try {
                State st = new State();
                st.parse(lines);
                st.applyHere(game);
            } catch (RuntimeException e) {
                record.set(SimShim.obj(
                    SimShim.kv("rec", "scenario"),
                    SimShim.kvRaw("game", Integer.toString(gameIndex)),
                    SimShim.kv("file", fileName),
                    SimShim.kv("sha256", sha256),
                    SimShim.kvRaw("applied", "false"),
                    SimShim.kv("before", before),
                    SimShim.kv("error", e.toString())));
                throw e;
            }
            String restored = restoreLife(game);
            long t1 = System.currentTimeMillis();
            afterApply.run();
            Player active = ph.getPlayerTurn();
            Player prio = ph.getPriorityPlayer();
            record.set(SimShim.obj(
                SimShim.kv("rec", "scenario"),
                SimShim.kvRaw("game", Integer.toString(gameIndex)),
                SimShim.kv("file", fileName),
                SimShim.kv("sha256", sha256),
                SimShim.kvRaw("applied", "true"),
                // Where the game stood when the hook ran (turn 1, untap) and
                // where the state put it; atMs is time since the game began.
                SimShim.kv("before", before),
                SimShim.kvRaw("turn", Integer.toString(ph.getTurn())),
                SimShim.kv("phase", String.valueOf(ph.getPhase())),
                SimShim.kv("active", active == null ? "" : active.getName()),
                SimShim.kv("priority", prio == null ? "" : prio.getName()),
                SimShim.kvRaw("atMs", Long.toString(t0 - gameStarted)),
                SimShim.kvRaw("applyMs", Long.toString(t1 - t0)),
                SimShim.kvRaw("lifeRestored", restored),
                SimShim.kvRaw("seats", seats(game))));
        };
    }

    /**
     * Put each seat's life back to the file's value where the apply moved
     * it. GameState sets life BEFORE it moves battlefield cards in, and each
     * move runs the card's enters-the-battlefield replacement effects: a
     * shock land asks its controller whether to pay 2 life (measured on Sim
     * Lab's smoke scenario: the seat paid in 8 of 8 trials, stock and plan
     * pilots alike, and started at 38 of the file's 40). The card was
     * already on the battlefield in the
     * scenario, so that payment is an artifact of the load, not of the game.
     * GameState itself re-sets life after the apply, but only for life of 0
     * or less; this extends that to every value, with triggers suppressed as
     * they are for GameState's own setLife. Returns what changed, as JSON.
     */
    private String restoreLife(Game game) {
        Map<Integer, Integer> want = new java.util.HashMap<>();
        for (String line : lines) {
            String l = line.trim().toLowerCase();
            int eq = l.indexOf('=');
            if (l.startsWith("#") || eq < 0 || !l.substring(0, eq).endsWith("life")) continue;
            String who = l.substring(0, eq - 4);
            // GameState.getPlayerState: human = 0, ai = 1, p<digit> = that index.
            Integer idx = who.equals("human") ? Integer.valueOf(0) : who.equals("ai") ? Integer.valueOf(1)
                    : (who.length() == 2 && who.charAt(0) == 'p' && Character.isDigit(who.charAt(1)))
                    ? Integer.valueOf(who.charAt(1) - '0') : null;
            if (idx == null) continue;
            try {
                want.put(idx, Integer.parseInt(l.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                // GameState itself will have thrown on this line
            }
        }
        List<String> changed = new ArrayList<>();
        List<Player> players = game.getPlayers();
        game.getTriggerHandler().setSuppressAllTriggers(true);
        try {
            for (Map.Entry<Integer, Integer> e : want.entrySet()) {
                if (e.getKey() >= players.size() || e.getValue() <= 0) continue;
                Player p = players.get(e.getKey());
                if (p.getLife() == e.getValue()) continue;
                changed.add(SimShim.obj(SimShim.kv("seat", p.getName()),
                        SimShim.kvRaw("from", Integer.toString(p.getLife())),
                        SimShim.kvRaw("to", Integer.toString(e.getValue()))));
                p.setLife(e.getValue(), null);
            }
        } finally {
            game.getTriggerHandler().setSuppressAllTriggers(false);
        }
        return "[" + String.join(",", changed) + "]";
    }

    private static final ZoneType[] ZONES = {
        ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
        ZoneType.Exile, ZoneType.Command, ZoneType.Library,
    };

    /** Every seat's zones as Forge holds them after the apply: the proof of
     *  what was seeded. Library is in Forge's order, top card first. */
    private static String seats(Game game) {
        StringBuilder b = new StringBuilder("[");
        boolean firstSeat = true;
        for (Player p : game.getPlayers()) {
            if (!firstSeat) b.append(',');
            firstSeat = false;
            List<String> cmd = new ArrayList<>();
            for (Card c : p.getCommanders()) cmd.add(Integer.toString(c.getId()));
            b.append('{')
             .append(SimShim.kv("name", p.getName())).append(',')
             .append(SimShim.kvRaw("life", Integer.toString(p.getLife()))).append(',')
             .append(SimShim.kvRaw("poison", Integer.toString(p.getPoisonCounters()))).append(',')
             .append(SimShim.kvRaw("landsPlayed", Integer.toString(p.getLandsPlayedThisTurn()))).append(',')
             .append(SimShim.kvRaw("commanderIds", "[" + String.join(",", cmd) + "]"));
            for (ZoneType zt : ZONES) {
                b.append(',').append('"').append(zt.toString()).append("\":[");
                boolean firstCard = true;
                for (Card c : p.getCardsIn(zt)) {
                    if (!firstCard) b.append(',');
                    firstCard = false;
                    b.append(card(c, zt == ZoneType.Battlefield));
                }
                b.append(']');
            }
            b.append('}');
        }
        return b.append(']').toString();
    }

    /** A counter type's name as a JSON key (kvRaw writes keys unescaped). */
    private static String key(String name) {
        return name.replace('"', '_').replace('\\', '_');
    }

    private static String card(Card c, boolean onBattlefield) {
        List<String> kvs = new ArrayList<>();
        kvs.add(SimShim.kvRaw("id", Integer.toString(c.getId())));
        kvs.add(SimShim.kv("card", c.getName()));
        if (onBattlefield) {
            kvs.add(SimShim.kvRaw("tapped", Boolean.toString(c.isTapped())));
            // sick: the summoning-sickness flag the state set; sickNow:
            // Forge's hasSickness(), which haste turns off.
            kvs.add(SimShim.kvRaw("sick", Boolean.toString(c.isFirstTurnControlled())));
            kvs.add(SimShim.kvRaw("sickNow", Boolean.toString(c.hasSickness())));
            if (c.isToken()) kvs.add(SimShim.kvRaw("token", "true"));
            if (c.isFaceDown()) kvs.add(SimShim.kvRaw("faceDown", "true"));
            if (c.getDamage() > 0) kvs.add(SimShim.kvRaw("damage", Integer.toString(c.getDamage())));
            GameEntity to = c.getEntityAttachedTo();
            if (to != null) kvs.add(SimShim.kvRaw("attachedTo", Integer.toString(to.getId())));
            Map<CounterType, Integer> counters = c.getCounters();
            if (counters != null && !counters.isEmpty()) {
                List<String> cs = new ArrayList<>();
                for (Map.Entry<CounterType, Integer> e : counters.entrySet()) {
                    // toString is the name the state format uses (P1P1,
                    // LORE), as GameState writes and reads it back.
                    cs.add(SimShim.kvRaw(key(e.getKey().toString()), Integer.toString(e.getValue())));
                }
                kvs.add(SimShim.kvRaw("counters", "{" + String.join(",", cs) + "}"));
            }
        }
        if (c.isCommander()) kvs.add(SimShim.kvRaw("commander", "true"));
        return SimShim.obj(kvs.toArray(new String[0]));
    }
}
