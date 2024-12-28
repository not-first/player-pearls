package faker.playerpearls;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import java.util.UUID;

public class EventHandler {

    public static final float LOOKING_UP_PITCH = -90.0F;
    public static final float LOOKING_DOWN_PITCH = 90.0F;

    public static final double MOVEMENT_THRESHOLD = 2.0; // 2 block limit

    public static final double DRAIN_RATE = 0.005; // 0.009 of the full bar every tick
    public static final int MAX_XP_LOSS = 5; // maximum loss of 5 xp levels

    private final PlayerStateManager playerStateManager;
    private final TeleportHandler teleportHandler;

    public EventHandler(PlayerStateManager playerStateManager, TeleportHandler teleportHandler) {
        this.playerStateManager = playerStateManager;
        this.teleportHandler = teleportHandler;
    }

    // register all the event listeners
    public void register() {
        registerUseItemCallback();
        registerServerTickEvents();
        registerDisconnectEvent();
    }

    // callback for when a player uses an ender pearl
    private void registerUseItemCallback() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack itemStack = player.getStackInHand(hand);

            if (itemStack.getItem() instanceof EnderPearlItem) {
                if (!world.isClient) {
                    ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                    float lookingPitch = serverPlayer.getPitch(1.0F);
                    UUID playerUUID = serverPlayer.getUuid();

                    if (lookingPitch <= (LOOKING_UP_PITCH + 10.0F) && lookingPitch >= (LOOKING_UP_PITCH - 10.0F)) {
                        if (serverPlayer.isSneaking()) {
                            // if the player is already pending, don't do anything and let the player throw a pearl normally
                            if (playerStateManager.isPending(playerUUID)) {
                                return ActionResult.PASS;
                            }

                            // if the player has no xp, don't allow them to teleport request
                            if (serverPlayer.experienceLevel <= 0) {
                                serverPlayer.sendMessage(Text.of("Teleport cancelled due to lack of XP"), true);
                                world.playSound(
                                        null,
                                        serverPlayer.getX(),
                                        serverPlayer.getY(),
                                        serverPlayer.getZ(),
                                        SoundEvents.ENTITY_ENDER_EYE_DEATH,
                                        SoundCategory.PLAYERS,
                                        1.0F,
                                        1.0F
                                );
                                return ActionResult.FAIL;
                            }

                            itemStack.decrement(1); // remove and enderpearl to simulate throwing it
                            serverPlayer.sendMessage(Text.of("Pending teleport"), true);

                            world.playSound(
                                    null,
                                    serverPlayer.getX(),
                                    serverPlayer.getY(),
                                    serverPlayer.getZ(),
                                    SoundEvents.ENTITY_ENDER_PEARL_THROW,
                                    SoundCategory.PLAYERS,
                                    1.0F,
                                    1.0F
                            );

                            playerStateManager.registerPendingPlayer(serverPlayer);
                            return ActionResult.FAIL;
                        }

                        return ActionResult.PASS;
                    }
                }
            }
            return ActionResult.PASS;
        });
    }

    private void registerServerTickEvents() {
        // repeat through each player on the server
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerManager().getPlayerList().forEach(player -> {
                            UUID playerUUID = player.getUuid();

                            // is a player has a current teleport request
                            if (playerStateManager.isPending(playerUUID)) {
                                // cancel it is appropriate
                                if (teleportHandler.shouldCancelTeleport(player)) {
                                    teleportHandler.cancelTeleport(player);
                                    player.sendMessage(Text.of("Teleport cancelled"), true);
                                    return;
                                }

                                PearlUtils.applyHiddenSlowness(player);
                                teleportHandler.drainXp(player);

                                // attempt to find any other player who is not pending, sneaking and looking down and teleport to them if found
                                server.getPlayerManager().getPlayerList().stream()
                                        .filter(otherPlayer -> !otherPlayer.getUuid().equals(playerUUID))
                                        .filter(otherPlayer -> !playerStateManager.isPending(otherPlayer.getUuid()))  // Make sure acceptor isn't pending
                                        .filter(ServerPlayerEntity::isSneaking)
                                        .filter(otherPlayer -> otherPlayer.getPitch(1.0F) >= LOOKING_DOWN_PITCH)  // Changed to looking down
                                        .findFirst()
                                        .ifPresent(targetPlayer -> teleportHandler.teleportPlayer(player, targetPlayer));
                            }
                        }
                )
        );

    }

    // cleanup player state when they disconnect
    private void registerDisconnectEvent() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playerStateManager.cleanupPlayerState(handler.player.getUuid()));
    }
}
