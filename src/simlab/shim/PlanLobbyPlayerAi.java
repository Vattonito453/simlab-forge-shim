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

    private final DeckPlan plan;
    private final Map<String, Integer> threatIndex;
    private final long seedBase;

    PlanLobbyPlayerAi(String name, DeckPlan plan, Map<String, Integer> threatIndex,
                      long seedBase) {
        super(name, null);
        this.plan = plan;
        this.threatIndex = threatIndex;
        this.seedBase = seedBase;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlanPlayerController(
                game, player, this, plan, threatIndex, seedBase + id));
        return player;
    }
}
