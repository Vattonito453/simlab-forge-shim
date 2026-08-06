/*
 * simlab-forge-shim — GPL-3.0 (see LICENSE).
 */
package simlab.shim;

import java.util.Map;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

/**
 * LobbyPlayerAi that installs a PlanPlayerController instead of the stock
 * controller. Mirrors LobbyPlayerAi.createIngamePlayer (verified against
 * Forge 2.0.13 bytecode) minus profile rotation, which the shim never uses.
 */
final class PlanLobbyPlayerAi extends LobbyPlayerAi {

    /** Mixed into the per-seat seed so each game gets its own RNG stream. */
    private static final long GAME_STRIDE = 104729L;   // prime, unrelated to 7919

    private final DeckPlan plan;
    private final Map<String, Integer> threatIndex;
    private final long seedBase;

    // Set by SimShim before each game. The lobby player outlives the game, so
    // these carry the per-game context into the controller it builds.
    private int gameIndex = 0;
    private AgentLog log = new AgentLog(0);

    PlanLobbyPlayerAi(String name, DeckPlan plan, Map<String, Integer> threatIndex,
                      long seedBase) {
        super(name, null);
        this.plan = plan;
        this.threatIndex = threatIndex;
        this.seedBase = seedBase;
    }

    void beginGame(int gameIndex, AgentLog log) {
        this.gameIndex = gameIndex;
        this.log = log;
    }

    long seedFor(int id) {
        return seedBase + id + GAME_STRIDE * gameIndex;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        // The game index is part of the seed (Sim Lab audit A2). Without it
        // the controller was reseeded identically every game, so the first
        // splitAttacks roll at a given seat was the SAME number in every game
        // of every run: the personality dials quantized toward always/never at
        // correlated decision points instead of expressing their probabilities.
        player.setFirstController(new PlanPlayerController(
                game, player, this, plan, threatIndex, seedFor(id), log));
        return player;
    }
}
