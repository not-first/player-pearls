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
        targetPlayer.sendMessage(Text.of("Accepted teleport."), false);

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
        boolean shouldCancel = levelsLost > EventHandler.MAX_XP_LOSS && pendingPlayer.experienceProgress <= originalProgress;

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

        playerStateManager.cleanupPlayerState(playerUUID);
    }

    // called each tick to drain xp from the pending player
    public void drainXp(ServerPlayerEntity player) {
        int requiredXpForNextLevel = player.getNextLevelExperience();
        int pointsToDrain = Math.max(1, (int) (requiredXpForNextLevel * EventHandler.DRAIN_RATE));
        int currentLevelXp = (int) (player.experienceProgress * requiredXpForNextLevel);
        UUID playerUUID = player.getUuid();

        playerStateManager.addDrainedXP(playerUUID, pointsToDrain); // collect total amount of xp drained

        if (currentLevelXp >= pointsToDrain) {
            player.addExperience(-pointsToDrain); // if the player has enough xp, drain it
        } else if (player.experienceLevel > 0) {
            // if the player doesn't have enough xp, remove the level, set to maximum points and keep draining
            player.setExperienceLevel(player.experienceLevel - 1);
            player.setExperiencePoints(player.getNextLevelExperience());
            pointsToDrain = Math.max(1, (int) (player.getNextLevelExperience() * EventHandler.DRAIN_RATE));
            player.addExperience(-pointsToDrain);
        }

        PearlUtils.playXpSound(player);
    }

}
