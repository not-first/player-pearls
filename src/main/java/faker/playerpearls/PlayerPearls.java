package faker.playerpearls;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerPearls implements ModInitializer {
    public static final String MOD_ID = "player-pearls";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Integer> pendingPlayers = new HashMap<>();
    private static final Map<UUID, Integer> originalXp = new HashMap<>();

    private static final float LOOKING_UP_PITCH = -90.0F;
    private static final int MAX_XP_LOSS = 5;

    private static final Map<UUID, Double> lastPosX = new HashMap<>();
    private static final Map<UUID, Double> lastPosZ = new HashMap<>();
    private static final Map<UUID, Double> lastPosY = new HashMap<>();

    private static final double MOVEMENT_THRESHOLD = 2.0; // 2 blocks
    private static final double DRAIN_RATE = 0.005; // 0.5% of progress bar per tick

    @Override
    public void onInitialize() {
        LOGGER.info("Player Pearls initialized.");
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack itemStack = player.getStackInHand(hand);

            if (itemStack.getItem() instanceof EnderPearlItem) {
                float pitch = player.getPitch(1.0F);
                if (pitch <= (LOOKING_UP_PITCH + 10.0F) && pitch >= (LOOKING_UP_PITCH - 10.0F) && !world.isClient) { // adjusted
                                                                                                                     // condition
                    LOGGER.info("Player {} threw an ender pearl while looking up. Pitch: {}",
                            player.getName().getString(), pitch);
                    itemStack.decrement(1);
                    player.playSound(SoundEvents.ENTITY_ENDER_PEARL_THROW);
                    RegisterPlayerRequest((ServerPlayerEntity) player);
                    return ActionResult.FAIL;
                } else {
                    LOGGER.info("Player {} threw an ender pearl but was not looking straight up. Pitch: {}",
                            player.getName().getString(), pitch);
                }
            }
            return ActionResult.PASS;
        });


        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerUUID = handler.player.getUuid();
            pendingPlayers.remove(playerUUID);
            originalXp.remove(playerUUID);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerManager().getPlayerList().forEach(player -> {
            UUID playerUUID = player.getUuid();

            if (pendingPlayers.containsKey(playerUUID)) {
                if (shouldCancelTeleport(player)) {
                    cancelTeleport(player);
                    player.sendMessage(Text.literal("Teleport cancelled due to movement or XP loss"), true);
                    return;
                }

                applyHiddenNausea(player);
                drainXp(player);

                server.getPlayerManager().getPlayerList().stream()
                        .filter(otherPlayer -> !otherPlayer.getUuid().equals(playerUUID))
                        .filter(ServerPlayerEntity::isSneaking)
                        .filter(otherPlayer -> otherPlayer.getPitch(1.0F) <= LOOKING_UP_PITCH).findFirst()
                        .ifPresent(targetPlayer -> {
                            LOGGER.info("Found target player {} to teleport player {}",
                                    targetPlayer.getName().getString(), player.getName().getString());
                            teleportPlayer(player, targetPlayer);
                        });
            }
        }));

    }

    private void RegisterPlayerRequest(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        pendingPlayers.put(playerUUID, 0);
        originalXp.put(playerUUID, player.experienceLevel);
        lastPosX.put(playerUUID, player.getX());
        lastPosZ.put(playerUUID, player.getZ());
        lastPosY.put(playerUUID, player.getY());
        LOGGER.info("Player {} is now in pending state. XP level: {}", player.getName().getString(),
                player.experienceLevel);
    }

    private void applyHiddenNausea(ServerPlayerEntity player) {
        LOGGER.info("Applying nausea effect to player {}", player.getName().getString());
        StatusEffectInstance nausea = new StatusEffectInstance(StatusEffects.NAUSEA, 100, 1, false, false);
        player.addStatusEffect(nausea);
    }

    private boolean shouldCancelTeleport(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        int originalXpValue = originalXp.get(playerUUID);
        int currentXpValue = player.experienceLevel;
        boolean shouldCancel = currentXpValue <= 0 || (originalXpValue - currentXpValue) >= MAX_XP_LOSS;

        double dx = player.getX() - lastPosX.get(playerUUID);
        double dz = player.getZ() - lastPosZ.get(playerUUID);
        double dy = player.getY() - lastPosY.get(playerUUID);
        double distanceMoved = Math.sqrt(dx * dx + dz * dz + dy * dy);

        if (distanceMoved > MOVEMENT_THRESHOLD) {
            LOGGER.info("Teleport should be canceled for player {} due to movement. Distance moved: {}",
                    player.getName().getString(), distanceMoved);
            shouldCancel = true;
        }

        if (shouldCancel) {
            LOGGER.info("Teleport should be canceled for player {}. Current XP: {}, Original XP: {}",
                    player.getName().getString(), currentXpValue, originalXpValue);
        }
        return shouldCancel;
    }

    private void cancelTeleport(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        pendingPlayers.remove(playerUUID);
        originalXp.remove(playerUUID);
        lastPosX.remove(playerUUID);
        lastPosZ.remove(playerUUID);
        lastPosY.remove(playerUUID);
        LOGGER.info("Teleport canceled for player {}", player.getName().getString());
    }

    private void drainXp(ServerPlayerEntity player) {
        // Calculate how many actual XP points correspond to our drain rate
        // for the current level
        int requiredXpForNextLevel = player.getNextLevelExperience();
        int pointsToDrain = Math.max(1, (int)(requiredXpForNextLevel * DRAIN_RATE));

        // Get current XP points for this level
        int currentLevelXp = (int) (player.experienceProgress * player.getNextLevelExperience());

        if (currentLevelXp >= pointsToDrain) {
            // If we have enough points, drain them
            player.addExperience(-pointsToDrain);
        } else {
            // Not enough points, need to decrease level
            if (player.experienceLevel > 0) {
                player.setExperienceLevel(player.experienceLevel - 1);
                // Set points to maximum for new level
                player.setExperiencePoints(player.getNextLevelExperience());
                // Drain from the new full bar
                pointsToDrain = Math.max(1, (int)(player.getNextLevelExperience() * DRAIN_RATE));
                player.addExperience(-pointsToDrain);
            }
        }

        player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);


        LOGGER.info("Player {} XP status - Level: {}, Progress: {}, Points Drained: {}",
                player.getName().getString(),
                player.experienceLevel,
                player.experienceProgress,
                pointsToDrain);
    }

    private void teleportPlayer(ServerPlayerEntity requestingPlayer, ServerPlayerEntity targetPlayer) {
        UUID playerUUID = requestingPlayer.getUuid();
        LOGGER.info("Teleporting player {} to player {}", requestingPlayer.getName().getString(),
                targetPlayer.getName().getString());

        double originalX = requestingPlayer.getX();
        double originalY = requestingPlayer.getY();
        double originalZ = requestingPlayer.getZ();

        int originalLevel = originalXp.get(playerUUID);
        int currentLevel = requestingPlayer.experienceLevel;
        int xpDrained = originalLevel - currentLevel;
        int xpToDrop = xpDrained / 2;

        requestingPlayer.teleport(targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(), true);
        requestingPlayer.playSound(SoundEvents.ENTITY_ENDER_PEARL_THROW);
        LOGGER.info("Player {} teleported to player {}.", requestingPlayer.getName().getString(),
                targetPlayer.getName().getString());

        if (xpToDrop > 0) {
            LOGGER.info("Dropping {} XP orbs for player {}", xpToDrop, requestingPlayer.getName().getString());
            int xpPerOrb = xpToDrop / 5;
            for (int i = 0; i < 5; i++) {
                double spreadX = originalX + (Math.random() - 0.5);
                double spreadZ = originalZ + (Math.random() - 0.5);

                ExperienceOrbEntity orb = new ExperienceOrbEntity(requestingPlayer.getWorld(), spreadX, originalY + 0.5,
                        spreadZ, xpPerOrb);
                requestingPlayer.getWorld().spawnEntity(orb);
                LOGGER.info("Spawned XP orb with {} XP at ({}, {}, {}).", xpPerOrb, spreadX, originalY + 0.5, spreadZ);
            }

        }

        pendingPlayers.remove(playerUUID);
        originalXp.remove(playerUUID);
        LOGGER.info("Pending state cleared for player {}", requestingPlayer.getName().getString());
    }
}