package faker.playerpearls;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerStateManager {
    private static final Map<UUID, Integer> pendingPlayers = new HashMap<>();

    // xp related state
    private static final Map<UUID, Integer> originalXp = new HashMap<>();
    private static final Map<UUID, Float> originalProgress = new HashMap<>();
    private static final Map<UUID, Integer> drainedXpPoints = new HashMap<>();

    // position related state
    private static final Map<UUID, Double> lastPosX = new HashMap<>();
    private static final Map<UUID, Double> lastPosY = new HashMap<>();
    private static final Map<UUID, Double> lastPosZ = new HashMap<>();

    // add player information linked to UUID to all the maps
    public void registerPendingPlayer(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        pendingPlayers.put(playerUUID, 1);

        originalXp.put(playerUUID, player.experienceLevel);
        originalProgress.put(playerUUID, player.experienceProgress);
        lastPosX.put(playerUUID, player.getX());
        lastPosY.put(playerUUID, player.getY());
        lastPosZ.put(playerUUID, player.getZ());
    }

    // remove player information linked to UUID from all the maps
    public void cleanupPlayerState(UUID playerUUID) {
        pendingPlayers.remove(playerUUID);
        originalXp.remove(playerUUID);
        originalProgress.remove(playerUUID);
        drainedXpPoints.remove(playerUUID);
        lastPosX.remove(playerUUID);
        lastPosY.remove(playerUUID);
        lastPosZ.remove(playerUUID);
    }

    public boolean isPending(UUID playerUUID) {
        return pendingPlayers.containsKey(playerUUID);
    }

    public void addDrainedXP(UUID playerUUID, int amount) {
        drainedXpPoints.merge(playerUUID, amount, Integer::sum);
    }

    public int getDrainedXP(UUID playerUUID) {
        return drainedXpPoints.getOrDefault(playerUUID, 0);
    }

    public Map<String, Object> getPlayerState(UUID playerUUID) {
        Map<String, Object> state = new HashMap<>();
        state.put("originalLevel", originalXp.get(playerUUID));
        state.put("originalProgress", originalProgress.get(playerUUID));
        state.put("lastPosX", lastPosX.get(playerUUID));
        state.put("lastPosY", lastPosY.get(playerUUID));
        state.put("lastPosZ", lastPosZ.get(playerUUID));
        return state;
    }
}
