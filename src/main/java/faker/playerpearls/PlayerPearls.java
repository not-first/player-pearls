package faker.playerpearls;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerPearls implements ModInitializer {
    public static final String MOD_ID = "player-pearls";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final EventHandler eventHandler;

    public PlayerPearls() {
        PlayerStateManager playerStateManager = new PlayerStateManager();
        TeleportHandler teleportHandler = new TeleportHandler(playerStateManager);
        this.eventHandler = new EventHandler(playerStateManager, teleportHandler);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Player Pearls initialized");
        eventHandler.register(); // register all the event listeners
    }
}