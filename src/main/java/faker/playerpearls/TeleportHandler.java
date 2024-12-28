package faker.playerpearls;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;

public class TeleportHandler {
    private final PlayerStateManager playerStateManager;

    public TeleportHandler(PlayerStateManager playerStateManager) {
        this.playerStateManager = playerStateManager;
    }

    // teleport the pending player to the target player, spawning xp orbs and cleaning up state
    public void teleportPlayer(ServerPlayerEntity pendingPlayer, ServerPlayerEntity targetPlayer) {
        UUID playerUUID = pendingPlayer.getUuid();

        double pendingX = pendingPlayer.getX();
        double pendingY = pendingPlayer.getY();
        double pendingZ = pendingPlayer.getZ();

        double targetX = targetPlayer.getX();
        double targetY = targetPlayer.getY();
        double targetZ = targetPlayer.getZ();

        int totalDrainedXp = playerStateManager.getDrainedXP(playerUUID);
        int xpPerLocation = totalDrainedXp / 2;

        pendingPlayer.teleport(targetX, targetY, targetZ, true);
        targetPlayer.sendMessage(Text.of("Accepted teleport"), true);

        pendingPlayer.getWorld().playSound(
                null,
                targetX,
                targetY,
                targetZ,
                SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.PLAYERS,
                1.0F,
                1.0F
        );

        if (xpPerLocation > 0) {
            PearlUtils.spawnXpOrbs(pendingPlayer, pendingX, pendingY, pendingZ, xpPerLocation);
            PearlUtils.spawnXpOrbs(pendingPlayer, targetX, targetY, targetZ, xpPerLocation);
        }

        playerStateManager.cleanupPlayerState(playerUUID);
    }

    // check if the pending player is still in a valid pending state
    public boolean shouldCancelTeleport(ServerPlayerEntity pendingPlayer) {
        UUID playerUUID = pendingPlayer.getUuid();
        Map<String, Object> state = playerStateManager.getPlayerState(playerUUID);
        int originalLevel = (int) state.get("originalLevel");
        float originalProgress = (float) state.get("originalProgress");
        double lastPosX = (double) state.get("lastPosX");
        double lastPosY = (double) state.get("lastPosY");
        double lastPosZ = (double) state.get("lastPosZ");

        int levelsLost = originalLevel - pendingPlayer.experienceLevel;
        boolean shouldCancel = levelsLost >= EventHandler.MAX_XP_LOSS && pendingPlayer.experienceProgress <= originalProgress;

        double dx = lastPosX - pendingPlayer.getX();
        double dy = lastPosY - pendingPlayer.getY();
        double dz = lastPosZ - pendingPlayer.getZ();
        double distanceMoved = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distanceMoved > EventHandler.MOVEMENT_THRESHOLD) {
            shouldCancel = true;
        }

        return shouldCancel;
    }

    // play a sound and clean up the pending player state
    public void cancelTeleport(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();

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

        int drainedXp = playerStateManager.getDrainedXP(playerUUID);
        PearlUtils.spawnXpOrbs(player, player.getX(), player.getY(), player.getZ(), drainedXp / 3);

        playerStateManager.cleanupPlayerState(playerUUID);
        player.sendMessage(Text.of("Teleport cancelled"), true);
    }

    // called each tick to drain xp from the pending player
    public void drainXp(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        int requiredXpForNextLevel = player.getNextLevelExperience();
        double pointsToDrain = requiredXpForNextLevel * EventHandler.DRAIN_RATE;

        // add the current accumulated points to the new points to drain
        double currentAccumulatedPoints = playerStateManager.getAccumulatedPoints(playerUUID);
        currentAccumulatedPoints += pointsToDrain;

        // only remove points if the accumulated points are greater than 1
        if (currentAccumulatedPoints >= 1) {
            int pointsToRemove = (int) currentAccumulatedPoints;
            currentAccumulatedPoints -= pointsToRemove;

            int currentLevelXp = (int) (player.experienceProgress * requiredXpForNextLevel);

            playerStateManager.addDrainedXP(playerUUID, pointsToRemove); // collect total amount of xp drained

            if (currentLevelXp >= pointsToRemove) {
                player.addExperience(-pointsToRemove);
            } else if (player.experienceLevel > 0) {
                // if needed, reduce the level and set points back to 100%
                player.setExperienceLevel(player.experienceLevel - 1);
                player.setExperiencePoints(player.getNextLevelExperience());
                player.addExperience(-pointsToRemove);
            }

            PearlUtils.playXpSound(player);
        }

        playerStateManager.setAccumulatedPoints(playerUUID, currentAccumulatedPoints);
    }

}
