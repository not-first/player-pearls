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

import java.util.*;

public class PlayerPearls implements ModInitializer {
    public static final String MOD_ID = "player-pearls";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Integer> pendingPlayers = new HashMap<>();
    private static final Map<UUID, Integer> originalXp = new HashMap<>();
    private static final Map<UUID, Float> originalProgress = new HashMap<>();  // Add this at class level
    private static final Map<UUID, Integer> drainedXpPoints = new HashMap<>();

    private static final float LOOKING_UP_PITCH = -90.0F;
    private static final float LOOKING_DOWN_PITCH = 90.0F;
    private static final int MAX_XP_LOSS = 5;

    private static final Map<UUID, Double> lastPosX = new HashMap<>();
    private static final Map<UUID, Double> lastPosZ = new HashMap<>();
    private static final Map<UUID, Double> lastPosY = new HashMap<>();

    private static final double MOVEMENT_THRESHOLD = 2.0; // 2 block
    private static final double DRAIN_RATE = 0.009; // 0.5% of progress bar per tick

    @Override
    public void onInitialize() {
        LOGGER.info("Player Pearls initialized.");
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack itemStack = player.getStackInHand(hand);

            if (itemStack.getItem() instanceof EnderPearlItem) {
                if (!world.isClient) {
                    ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                    float pitch = serverPlayer.getPitch(1.0F);
                    UUID playerUUID = serverPlayer.getUuid();

                    // Check if looking up and sneaking for pending state
                    if (pitch <= (LOOKING_UP_PITCH + 10.0F) && pitch >= (LOOKING_UP_PITCH - 10.0F)) {
                        // Only enter pending state if sneaking and not already pending
                        if (serverPlayer.isSneaking()) {
                            // If already pending, let vanilla handle the pearl throw
                            if (pendingPlayers.containsKey(playerUUID)) {
                                return ActionResult.PASS;
                            }

                            // Check XP before allowing pearl throw
                            if (serverPlayer.experienceLevel <= 0) {
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

                            LOGGER.info("Player {} threw an ender pearl while sneaking and looking up. Pitch: {}",
                                    serverPlayer.getName().getString(), pitch);
                            itemStack.decrement(1);
                            serverPlayer.sendMessage(Text.literal("Pending teleport . . ."), true);

                            // Play throw sound for everyone
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

                            RegisterPlayerRequest(serverPlayer);
                            return ActionResult.FAIL;
                        }
                        // If looking up but not sneaking, let vanilla handle it
                        return ActionResult.PASS;
                    }
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
                    player.sendMessage(Text.literal("Teleport request cancelled"), true);
                    return;
                }

                applyHiddenSlowness(player);
                drainXp(player);

                server.getPlayerManager().getPlayerList().stream()
                        .filter(otherPlayer -> !otherPlayer.getUuid().equals(playerUUID))
                        .filter(otherPlayer -> !pendingPlayers.containsKey(otherPlayer.getUuid()))  // Make sure acceptor isn't pending
                        .filter(ServerPlayerEntity::isSneaking)
                        .filter(otherPlayer -> otherPlayer.getPitch(1.0F) >= LOOKING_DOWN_PITCH)  // Changed to looking down
                        .findFirst()
                        .ifPresent(targetPlayer -> {
                            LOGGER.info("Found target player {} to teleport player {}",
                                    targetPlayer.getName().getString(), player.getName().getString());
                            targetPlayer.sendMessage(Text.literal("Accepting teleport request..."), true);
                            teleportPlayer(player, targetPlayer);
                        });
            }
        }));

    }

    private static int pendingCounter = 0;  // Add at class level

    private void RegisterPlayerRequest(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        pendingPlayers.put(playerUUID, pendingCounter++);  // Use counter for ordering
        originalXp.put(playerUUID, player.experienceLevel);
        originalProgress.put(playerUUID, player.experienceProgress);
        lastPosX.put(playerUUID, player.getX());
        lastPosZ.put(playerUUID, player.getZ());
        lastPosY.put(playerUUID, player.getY());
        LOGGER.info("Player {} is now in pending state. XP level: {}, Progress: {}",
                player.getName().getString(),
                player.experienceLevel,
                player.experienceProgress);
    }

    private void applyHiddenSlowness(ServerPlayerEntity player) {
        StatusEffectInstance slowness = new StatusEffectInstance(StatusEffects.SLOWNESS, 1, 2, false, false);
        player.addStatusEffect(slowness);
    }

    private boolean shouldCancelTeleport(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        int originalLevel = originalXp.get(playerUUID);
        float initialProgress = originalProgress.get(playerUUID);
        int currentLevel = player.experienceLevel;
        float currentProgress = player.experienceProgress;

        // Calculate total levels lost
        int levelsLost = originalLevel - currentLevel;

        // Only cancel if we've lost MAX_XP_LOSS levels AND current progress is less than or equal to original
        boolean shouldCancel = levelsLost >= MAX_XP_LOSS && currentProgress <= initialProgress;

        // Movement check
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
            LOGGER.info("Teleport canceled for player {}. Original Level: {}, Original Progress: {}, Current Level: {}, Current Progress: {}",
                    player.getName().getString(), originalLevel, originalProgress, currentLevel, currentProgress);
        }
        return shouldCancel;
    }

    private void cleanupPlayerState(UUID playerUUID) {
        pendingPlayers.remove(playerUUID);
        originalXp.remove(playerUUID);
        originalProgress.remove(playerUUID);
        drainedXpPoints.remove(playerUUID);  // Clean up drained XP
        lastPosX.remove(playerUUID);
        lastPosZ.remove(playerUUID);
        lastPosY.remove(playerUUID);
    }

    private void cancelTeleport(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();

        LOGGER.info("Teleport canceled for player {}. Current XP level: {}, Current Progress: {}",
                player.getName().getString(),
                player.experienceLevel,
                player.experienceProgress);

        // Play cancel sound for everyone
        player.getWorld().playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.ENTITY_ENDER_EYE_DEATH,
                SoundCategory.PLAYERS,
                1.0F,
                1.0F
        );

        cleanupPlayerState(playerUUID);
        LOGGER.info("Teleport canceled for player {}", player.getName().getString());
    }

    private void spawnXpOrbs(ServerPlayerEntity player, double x, double y, double z, int totalXp) {
        int numOrbs = (int)(Math.random() * 11); // Random number between 5 and 15
        int xpPerOrb = Math.max(1, totalXp / numOrbs); // Ensure at least 1 XP per orb

        for (int i = 0; i < numOrbs; i++) {
            double spreadX = x + (Math.random() - 0.5) * 2;
            double spreadZ = z + (Math.random() - 0.5) * 2;
            ExperienceOrbEntity orb = new ExperienceOrbEntity(
                    player.getWorld(),
                    spreadX,
                    y + 0.5,
                    spreadZ,
                    xpPerOrb
            );
            player.getWorld().spawnEntity(orb);
        }
    }

    private void drainXp(ServerPlayerEntity player) {
        int requiredXpForNextLevel = player.getNextLevelExperience();
        int pointsToDrain = Math.max(1, (int)(requiredXpForNextLevel * DRAIN_RATE));
        int currentLevelXp = (int) (player.experienceProgress * player.getNextLevelExperience());
        UUID playerUUID = player.getUuid();

        // Keep track of drained XP
        drainedXpPoints.merge(playerUUID, pointsToDrain, Integer::sum);

        if (currentLevelXp >= pointsToDrain) {
            player.addExperience(-pointsToDrain);
        } else if (player.experienceLevel > 0) {
            player.setExperienceLevel(player.experienceLevel - 1);
            player.setExperiencePoints(player.getNextLevelExperience());
            pointsToDrain = Math.max(1, (int)(player.getNextLevelExperience() * DRAIN_RATE));
            player.addExperience(-pointsToDrain);
        }

        // Play XP sound only for the draining player
        float pitch = 0.8F + (float) Math.random() * 0.4F;
        player.playSoundToPlayer(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                0.05F, // Reduced volume
                pitch
        );
    }

    private void teleportPlayer(ServerPlayerEntity requestingPlayer, ServerPlayerEntity targetPlayer) {
        UUID playerUUID = requestingPlayer.getUuid();
        double originalX = requestingPlayer.getX();
        double originalY = requestingPlayer.getY();
        double originalZ = requestingPlayer.getZ();

        // Get total drained XP
        int totalDrainedXp = drainedXpPoints.getOrDefault(playerUUID, 0);
        int xpPerLocation = totalDrainedXp / 2;

        // Teleport the player
        requestingPlayer.teleport(targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(), true);
        requestingPlayer.sendMessage(Text.literal("Teleported!"), true);

        // Play teleport sound for everyone
        requestingPlayer.getWorld().playSound(
                null,
                targetPlayer.getX(),
                targetPlayer.getY(),
                targetPlayer.getZ(),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.PLAYERS,
                1.0F,
                1.0F
        );

        // Spawn XP orbs at both locations
        if (xpPerLocation > 0) {
            spawnXpOrbs(requestingPlayer, originalX, originalY, originalZ, xpPerLocation); // Original location
            spawnXpOrbs(requestingPlayer, targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(), xpPerLocation); // New location
        }

        cleanupPlayerState(playerUUID);
    }
}