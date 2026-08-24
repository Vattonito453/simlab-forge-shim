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

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.eventbus.Subscribe;

import forge.GuiDesktop;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.card.CardTypeView;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneView;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Stage 0: run an N-game Commander pod with stock Forge AI and emit the
 * typed GameLog as JSON-lines on stdout (one record per line). Progress
 * goes to stderr so stdout stays machine-readable.
 *
 * Usage:
 *   java -cp simlab-forge-shim.jar:$FORGE_JAR simlab.shim.SimShim \
 *     --decks /abs/a.dck /abs/b.dck /abs/c.dck /abs/d.dck \
 *     --games 2 --timeout 120
 *
 * --seat-pilots assigns a pilot per seat, positionally, which --plans cannot
 * do on its own because plans are keyed by deck name. Required for mirror
 * pods, where every seat plays the same decklist:
 *
 *   --plans plans.json --seat-pilots \
 *     plan:SimLabHuman,stock:Default,stock:SimLabHuman,plan:Default
 *
 * Must run with the Forge install directory as the working directory so
 * Forge finds its res/ folder (same constraint as `sim` mode).
 */
public final class SimShim {

    private static PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;
    private static final String HUMAN_PROFILE = "SimLabHuman";

    /** SimLabHuman when the shim has written it, else Forge's stock profile. */
    private static String defaultPlanProfile() {
        return new File("res/ai/" + HUMAN_PROFILE + ".ai").isFile()
                ? HUMAN_PROFILE : "Default";
    }

    public static void main(String[] args) throws Exception {
        List<String> deckPaths = new ArrayList<>();
        int games = 1;
        int timeoutSec = 120;
        String outPath = null;
        String plansPath = null;
        boolean allowMissingPlans = false;
        String seatPilotSpec = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--decks":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        deckPaths.add(args[++i]);
                    }
                    break;
                case "--games":
                    games = Integer.parseInt(args[++i]);
                    break;
                case "--timeout":
                    timeoutSec = Integer.parseInt(args[++i]);
                    break;
                case "--out":
                    outPath = args[++i];
                    break;
                case "--plans":
                    plansPath = args[++i];
                    break;
                case "--allow-missing-plans":
                    allowMissingPlans = true;
                    break;
                case "--seat-pilots":
                    seatPilotSpec = args[++i];
                    break;
                default:
                    ERR.println("unknown arg: " + args[i]);
                    System.exit(2);
            }
        }
        if (outPath != null) {
            // Forge occasionally prints its own lines to stdout; --out keeps
            // the JSON stream clean of them.
            OUT = new PrintStream(new java.io.FileOutputStream(outPath), true, "UTF-8");
        }
        if (deckPaths.size() < 2) {
            ERR.println("need at least 2 --decks (.dck paths)");
            System.exit(2);
        }

        // Per-seat pilot assignment. Plans are keyed by deck NAME, so a mirror
        // pod (several seats playing the same decklist) resolves to the same
        // lookup for every seat and cannot express a mixed pod at all. This
        // spec is positional and therefore can: one entry per --decks entry,
        // in order, each "plan" or "stock", optionally suffixed ":Profile".
        // Assignment only. The plan DATA still arrives via --plans, so no
        // strategy knowledge moves into this file.
        List<String> seatSpecs = null;
        if (seatPilotSpec != null) {
            seatSpecs = new ArrayList<>();
            for (String s : seatPilotSpec.split(",")) {
                seatSpecs.add(s.trim());
            }
            if (seatSpecs.size() != deckPaths.size()) {
                ERR.println("--seat-pilots has " + seatSpecs.size()
                        + " entries but --decks has " + deckPaths.size()
                        + "; they align positionally and must match");
                System.exit(2);
            }
        }

        java.util.Map<String, DeckPlan> plans = java.util.Collections.emptyMap();
        if (plansPath != null) {
            String text = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(plansPath)), "UTF-8");
            plans = DeckPlan.parseAll(text);
            ERR.println("shim: plans loaded for " + plans.keySet());
        }
        // The SimLabHuman profile makes the stock AI eager with counterspells;
        // PlanPlayerController's threat veto then decides which actually fire.
        // Must exist before FModel.initialize loads profiles. Pure config data
        // — Forge itself stays unmodified. Also written when a seat spec names
        // it, because a stock seat can now request the profile with no plans
        // file involved (that pairing is deliberately unbalanced: the profile
        // opens the floodgate and only the plan controller gates it).
        if (plansPath != null
                || (seatPilotSpec != null && seatPilotSpec.contains(HUMAN_PROFILE))) {
            writeHumanProfile();
        }
        java.util.Map<String, Integer> threatIndex = DeckPlan.threatIndex(plans);

        // Mirrors forge.view.Main's pre-sim setup for headless operation.
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        ERR.println("shim: FModel initialized");

        List<RegisteredPlayer> players = new ArrayList<>();
        List<String> playerNames = new ArrayList<>();
        // Per-seat record of what actually piloted each deck. A run used to
        // report one bulk humanized flag that was true if ANY plan loaded, so
        // a seat that quietly fell back to stock AI was invisible -- and the
        // seat that fell back was, in practice, the deck under test.
        List<String> agentTypes = new ArrayList<>();
        // Arm identity is (controller, profile), not controller alone, so the
        // profile each seat actually ran has to be on the record too.
        List<String> seatProfiles = new ArrayList<>();
        List<String> unplanned = new ArrayList<>();
        List<String> seedBases = new ArrayList<>();
        for (int i = 0; i < deckPaths.size(); i++) {
            File f = new File(deckPaths.get(i));
            if (!f.isFile()) {
                ERR.println("deck file not found: " + f);
                System.exit(2);
            }
            Deck d = DeckSerializer.fromFile(f);
            String name = "Ai(" + (i + 1) + ")-" + d.getName();
            seedBases.add(Long.toString(7919L * (i + 1)));
            RegisteredPlayer rp = RegisteredPlayer.forCommander(d);

            // Resolve this seat's pilot and profile. With no --seat-pilots the
            // behaviour is exactly as before: the plans file decides, and a
            // plan seat takes SimLabHuman when it is on disk.
            String wantPilot;
            String wantProfile;
            String spec = seatSpecs == null ? null : seatSpecs.get(i);
            if (spec == null) {
                wantPilot = plans.containsKey(d.getName()) ? "plan" : "stock";
                wantProfile = "plan".equals(wantPilot) ? defaultPlanProfile() : null;
            } else {
                int colon = spec.indexOf(':');
                wantPilot = colon < 0 ? spec : spec.substring(0, colon);
                wantProfile = colon < 0 ? null : spec.substring(colon + 1);
                if (!"plan".equals(wantPilot) && !"stock".equals(wantPilot)) {
                    ERR.println("--seat-pilots entry " + i + " is \"" + spec
                            + "\"; pilot must be \"plan\" or \"stock\"");
                    System.exit(2);
                }
                if (wantProfile == null && "plan".equals(wantPilot)) {
                    wantProfile = defaultPlanProfile();
                }
            }

            if ("plan".equals(wantPilot)) {
                DeckPlan plan = plans.get(d.getName());
                if (plan == null) {
                    // Explicitly asked for a plan agent with no plan to run.
                    // Silently seating stock AI here is the exact failure that
                    // corrupted four cells of the correlation pilot.
                    ERR.println("shim: --seat-pilots asks seat " + (i + 1)
                            + " to run a plan agent but no plan matches deck \""
                            + d.getName() + "\"\n  plan keys available: "
                            + plans.keySet());
                    System.exit(3);
                }
                PlanLobbyPlayerAi lobby = new PlanLobbyPlayerAi(
                        name, plan, threatIndex, 7919L * (i + 1));
                lobby.setAiProfile(wantProfile != null ? wantProfile : "Default");
                rp.setPlayer(lobby);
                agentTypes.add("plan");
                seatProfiles.add(lobby.getAiProfile());
                ERR.println("shim: " + name + " -> plan agent, profile "
                        + lobby.getAiProfile());
            } else {
                forge.LobbyPlayer lp = GamePlayerUtil.createAiPlayer(name, i);
                String applied = "Default";
                if (wantProfile != null && lp instanceof LobbyPlayerAi) {
                    ((LobbyPlayerAi) lp).setAiProfile(wantProfile);
                    applied = ((LobbyPlayerAi) lp).getAiProfile();
                } else if (lp instanceof LobbyPlayerAi) {
                    applied = ((LobbyPlayerAi) lp).getAiProfile();
                }
                rp.setPlayer(lp);
                agentTypes.add("stock");
                seatProfiles.add(applied);
                if (spec == null) {
                    // Only an IMPLIED stock seat is a missing-plan problem. A
                    // seat declared stock on purpose must not trip the guard.
                    unplanned.add(d.getName());
                    ERR.println("shim: " + name + " -> STOCK AI (no plan for \""
                            + d.getName() + "\")");
                } else {
                    ERR.println("shim: " + name + " -> stock AI, profile " + applied);
                }
            }
            players.add(rp);
            playerNames.add(name);
        }

        // Asking for plans and silently not getting them is never what the
        // caller meant: it swaps the experiment for a different one (a stock
        // deck against humanized opponents) and labels it humanized. Refuse.
        if (!plans.isEmpty() && !unplanned.isEmpty() && !allowMissingPlans) {
            ERR.println("shim: --plans supplied but no plan matched these decks: "
                    + unplanned + "\n  plan keys available: " + plans.keySet()
                    + "\n  Deck names must match the plan JSON keys exactly."
                    + "\n  Pass --allow-missing-plans to run a deliberately mixed pod.");
            System.exit(3);
        }

        GameRules rules = new GameRules(GameType.Commander);
        // One game per Match: see the loop below for why.
        rules.setGamesPerMatch(1);
        rules.setSimTimeout(timeoutSec);

        OUT.println(obj(
            kv("rec", "meta"),
            kv("shim", "0.4.0"),
            kv("format", "Commander"),
            kvRaw("games", Integer.toString(games)),
            // humanized means EVERY seat ran a plan agent. It used to mean
            // "at least one did", which reported a mixed pod as a humanized
            // run. `agents` carries the per-seat truth either way.
            kvRaw("humanized", Boolean.toString(
                    !agentTypes.isEmpty() && !agentTypes.contains("stock"))),
            kvList("agents", agentTypes),
            kvList("profiles", seatProfiles),
            kvList("players", playerNames),
            // Enough to reconstruct any seat's RNG stream in any game:
            // seed = seedBase[seat] + playerId + seedGameStride * gameIndex.
            // Recorded because a run whose randomness cannot be reproduced
            // cannot be debugged, and because the game term is new (audit A2).
            kvList("seedBases", seedBases),
            kvRaw("seedGameStride", "104729"),
            kvList("decks", deckPaths)));

        // A FRESH Match per game (Sim Lab audit A1). Forge's Match.startGame
        // feeds its `lastOutcome` into GameAction.startGame, which picks the
        // first turn from the earliest-seated NON-winner of the previous game
        // — and after any draw nobody is a winner, so seat 0 went first every
        // single time. Every timeout is a draw, so on a pod with a meaningful
        // timeout rate that is a large, absolute seat-1 advantage that upstream
        // seat rotation cannot cancel, because rotation moves decks between
        // seats and this bias follows the SEAT.
        //
        // match.clearGamesPlayed() is NOT sufficient and would look like it is:
        // decompiled, it clears the gameOutcomes map and restores decks but
        // leaves the `lastOutcome` field untouched, which is the field
        // startGame actually reads. A new Match has it null, so Forge chooses
        // the first player at random, which is what independent games require.
        for (int g = 0; g < games; g++) {
            Match match = new Match(rules, players, "SimLabShim");
            AgentLog log = new AgentLog(g);
            for (RegisteredPlayer rp : players) {
                if (rp.getPlayer() instanceof PlanLobbyPlayerAi) {
                    ((PlanLobbyPlayerAi) rp.getPlayer()).beginGame(g, log);
                }
            }
            runOneGame(match, g, timeoutSec, log);
        }
        OUT.flush();
        // Forge leaves non-daemon threads behind; exit explicitly.
        System.exit(0);
    }

    /**
     * Generate res/ai/SimLabHuman.ai from the shipped Default.ai with
     * always-counter chances (the controller's threat veto restores
     * selectivity). Config data in the user's Forge dir; Forge code and its
     * shipped profiles are untouched.
     */
    private static void writeHumanProfile() {
        try {
            File src = new File("res/ai/Default.ai");
            if (!src.isFile()) return; // cwd is not the forge dir; skip
            String text = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            text = text
                .replaceAll("(?m)^CHANCE_TO_COUNTER_CMC_1=.*$", "CHANCE_TO_COUNTER_CMC_1=100")
                .replaceAll("(?m)^CHANCE_TO_COUNTER_CMC_2=.*$", "CHANCE_TO_COUNTER_CMC_2=100")
                .replaceAll("(?m)^CHANCE_TO_COUNTER_CMC_3=.*$", "CHANCE_TO_COUNTER_CMC_3=100")
                .replaceAll("(?m)^MIN_SPELL_CMC_TO_COUNTER=.*$", "MIN_SPELL_CMC_TO_COUNTER=0");
            java.nio.file.Files.write(new File("res/ai/SimLabHuman.ai").toPath(),
                    text.getBytes("UTF-8"));
        } catch (Exception e) {
            ERR.println("shim: could not write SimLabHuman.ai: " + e);
        }
    }

    /**
     * Structured event capture. Forge's GameLog only records cards LEAVING
     * the battlefield; the event bus fires GameEventCardChangeZone for every
     * movement in both directions — this is the ground truth Sim Lab's board
     * reconstruction has never had. Events arrive on the game thread; the
     * buffer is drained after the game finishes.
     */
    static final class EventTap {
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final int gameIndex;
        private volatile int turn = 0;

        EventTap(int gameIndex) {
            this.gameIndex = gameIndex;
        }

        @Subscribe
        public void onTurn(GameEventTurnBegan ev) {
            turn = ev.turnNumber();
        }

        @Subscribe
        public void onZone(GameEventCardChangeZone ev) {
            String card = ev.card() == null ? null : ev.card().getName();
            int cardId = ev.card() == null ? -1 : ev.card().getId();
            lines.add(obj(
                kv("rec", "zone"),
                kvRaw("game", Integer.toString(gameIndex)),
                kvRaw("turn", Integer.toString(turn)),
                card == null ? kvRaw("card", "null") : kv("card", card),
                kvRaw("cardId", Integer.toString(cardId)),
                kv("from", zoneName(ev.from())),
                kv("to", zoneName(ev.to())),
                kv("fromPlayer", zonePlayer(ev.from())),
                kv("toPlayer", zonePlayer(ev.to())),
                kv("types", coreTypes(ev.card())),
                kv("pt", powerToughness(ev.card())),
                kvRaw("token", Boolean.toString(isToken(ev.card())))));
        }

        /**
         * Core card types as raw data, not a verdict. Sim Lab's board code
         * used to buy this from Scryfall by card name, which cannot answer for
         * a token at all ("Zombie Token" is not a card) and needs a warm cache
         * to answer at all. Forge knows it exactly, for free, at the moment of
         * the move.
         */
        private static String coreTypes(CardView c) {
            if (c == null) return "";
            try {
                CardTypeView t = c.getCurrentState().getType();
                StringBuilder sb = new StringBuilder();
                if (t.isLand()) sb.append("Land,");
                if (t.isCreature()) sb.append("Creature,");
                if (t.isArtifact()) sb.append("Artifact,");
                if (t.isEnchantment()) sb.append("Enchantment,");
                if (t.isPlaneswalker()) sb.append("Planeswalker,");
                if (t.isBattle()) sb.append("Battle,");
                if (t.isInstant()) sb.append("Instant,");
                if (t.isSorcery()) sb.append("Sorcery,");
                int n = sb.length();
                return n == 0 ? "" : sb.substring(0, n - 1);
            } catch (Throwable t) {
                return "";
            }
        }

        /**
         * Net power/toughness at the moment of the move, so a board row can
         * show 4/4 for a Zombie token that two anthems have grown. This is the
         * value AS IT MOVES: later pumps on a permanent that stays put are not
         * re-reported, so treat it as entry-time, not live.
         */
        private static String powerToughness(CardView c) {
            if (c == null) return "";
            try {
                CardView.CardStateView s = c.getCurrentState();
                if (!s.getType().isCreature()) return "";
                return s.getPower() + "/" + s.getToughness();
            } catch (Throwable t) {
                return "";
            }
        }

        private static boolean isToken(CardView c) {
            if (c == null) return false;
            try {
                return c.isToken();
            } catch (Throwable t) {
                return false;
            }
        }

        void drainTo(PrintStream out) {
            synchronized (lines) {
                for (String l : lines) {
                    out.println(l);
                }
            }
        }

        private static String zoneName(ZoneView z) {
            return z == null || z.zoneType() == null ? "None" : z.zoneType().toString();
        }

        private static String zonePlayer(ZoneView z) {
            return z == null || z.player() == null ? "" : z.player().getName();
        }
    }

    /**
     * Drain Forge's GameLog defensively (audit A10).
     *
     * GameLog's backing ArrayList is unsynchronized. After a failed unwind the
     * game thread may still be running and adding entries, and a concurrent
     * add during the array's growth can hand back a list containing a null —
     * which used to NPE on `e.type()`, kill the whole process, and take every
     * remaining game in the run with it. The truncated JSONL was then accepted
     * downstream without complaint. A snapshot copy plus per-entry guards
     * turns the worst case into a few lost lines in one game.
     */
    private static void emitLog(Game game, int index) {
        List<GameLogEntry> log;
        try {
            log = new ArrayList<>(game.getGameLog().getLogEntries(null));
        } catch (Throwable t) {
            ERR.println("shim: game " + index + " log unreadable: " + t);
            return;
        }
        Collections.reverse(log);   // getLogEntries(null) is newest-first
        int seq = 0;
        for (GameLogEntry e : log) {
            if (e == null) continue;
            String card = null;
            int cardId = -1;
            String type;
            String message;
            try {
                type = e.type() == null ? "UNKNOWN" : e.type().toString();
                message = e.message() == null ? "" : e.message();
                if (e.sourceCard() != null) {
                    card = e.sourceCard().getName();
                    cardId = e.sourceCard().getId();
                }
            } catch (Throwable t) {
                continue;           // one torn entry, not the run
            }
            StringBuilder sb = new StringBuilder(obj(
                kv("rec", "entry"),
                kvRaw("game", Integer.toString(index)),
                kvRaw("seq", Integer.toString(seq++)),
                kv("type", type),
                kv("message", message)));
            if (card != null) {
                sb.setLength(sb.length() - 1);
                sb.append(',').append(kv("card", card))
                  .append(',').append(kvRaw("cardId", Integer.toString(cardId)))
                  .append('}');
            }
            OUT.println(sb);
        }
    }

    private static void runOneGame(Match match, int index, int timeoutSec,
                                   AgentLog agentLog) {
        long started = System.currentTimeMillis();
        final Game game = match.createGame();
        final EventTap tap = new EventTap(index);
        game.subscribeToEvents(tap);

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "shim-game-" + index);
            t.setDaemon(true);
            return t;
        });
        Future<?> f = ex.submit(() -> match.startGame(game));
        boolean timedOut = false;
        String crash = null;
        try {
            f.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            timedOut = true;
            game.setGameOver(GameEndReason.Draw);
            try {
                f.get(15, TimeUnit.SECONDS); // let the game thread unwind
            } catch (Exception ignored) {
            }
        } catch (ExecutionException | InterruptedException e) {
            // A crashed game used to fall straight through to the normal
            // emission path and be published as an ordinary draw: partial log,
            // partial zones, turns:-1 that nothing downstream reads (audit A4).
            // Crashes inflated draw rates invisibly, and a crash after
            // setGameOver could even publish a plausible winner. Name it, so
            // the Python side can quarantine the game instead of counting it.
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null)
                    ? e.getCause() : e;
            crash = cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            ERR.println("shim: game " + index + " error: " + crash);
        } finally {
            ex.shutdownNow();
        }

        emitLog(game, index);
        tap.drainTo(OUT);
        agentLog.drainTo(OUT);

        // A timeout means WE decided this game is a draw (setGameOver above)
        // — Forge's own outcome object does not reliably agree once the game
        // thread is cut off mid-priority-pass, and has been observed to still
        // report a winner (consistently the last seat) instead of a draw.
        // Our decision wins regardless of what getOutcome() says afterward.
        boolean draw = timedOut || game.getOutcome() == null || game.getOutcome().isDraw();
        String winner = null;
        if (!draw && game.getOutcome().getWinningLobbyPlayer() != null) {
            winner = game.getOutcome().getWinningLobbyPlayer().getName();
        }
        int turns = game.getOutcome() == null ? -1 : game.getOutcome().getLastTurnNumber();
        // A crashed game reports no winner at all, whatever the outcome object
        // says. A crash after setGameOver can leave a plausible-looking winner
        // behind, and publishing it would put a fabricated result into the
        // corpus (audit A4).
        if (crash != null) {
            winner = null;
            draw = false;       // NOT a draw either: it is not a result
        }
        StringBuilder res = new StringBuilder(obj(
            kv("rec", "result"),
            kvRaw("game", Integer.toString(index)),
            kvRaw("draw", Boolean.toString(crash == null && (draw || winner == null))),
            winner == null ? kvRaw("winner", "null") : kv("winner", winner),
            kvRaw("turns", Integer.toString(turns)),
            kvRaw("timedOut", Boolean.toString(timedOut)),
            // Per-seat state at termination. A censored game has no winner, so
            // without this it carries no information at all and the whole game
            // is discarded — and censored games are the LONG ones, a biased
            // slice. Who was still standing at the clock is a real paired
            // outcome. Never fold it into a win rate: surviving is not winning.
            kvRaw("seats", seatStates(game)),
            kvRaw("ms", Long.toString(System.currentTimeMillis() - started))));
        if (crash != null) {
            res.setLength(res.length() - 1);
            res.append(',').append(kvRaw("error", "true"))
               .append(',').append(kv("errorClass", crash))
               .append('}');
        }
        OUT.println(res);
        ERR.println("shim: game " + (index + 1) + " done in "
                + (System.currentTimeMillis() - started) + " ms"
                + (crash != null ? " — ERRORED (" + crash + ")"
                                 : winner != null ? " — " + winner + " wins" : " — draw"));
    }

    /**
     * Per-seat state at the moment the game ended, as a JSON array. Each entry
     * carries its own seat name rather than relying on positional alignment
     * with meta.players, because getRegisteredPlayers() order is Forge's to
     * decide and a silent re-ordering would mislabel every arm.
     */
    private static String seatStates(Game game) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (forge.game.player.Player p : game.getRegisteredPlayers()) {
            if (!first) b.append(',');
            first = false;
            b.append(obj(
                kv("name", p.getName()),
                kvRaw("life", Integer.toString(p.getLife())),
                kvRaw("alive", Boolean.toString(!p.hasLost()))));
        }
        return b.append(']').toString();
    }

    // --- minimal JSON emission (no dependencies) ---

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static String kv(String k, String v) {
        return "\"" + k + "\":\"" + esc(v) + "\"";
    }

    private static String kvRaw(String k, String rawValue) {
        return "\"" + k + "\":" + rawValue;
    }

    private static String kvList(String k, List<String> items) {
        StringBuilder b = new StringBuilder("\"" + k + "\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(esc(items.get(i))).append('"');
        }
        return b.append(']').toString();
    }

    private static String obj(String... kvs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kvs.length; i++) {
            if (i > 0) b.append(',');
            b.append(kvs[i]);
        }
        return b.append('}').toString();
    }

    private SimShim() {
    }
}
