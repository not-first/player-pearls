package faker.playerpearls;

import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

public class PearlUtils {

    // spawn xp orbs given an amount and location
    public static void spawnXpOrbs(ServerPlayerEntity player, double x, double y, double z, int totalXp) {
        int numOrbs = 1 + (int) (Math.random() * 11);
        int xpPerOrb = Math.max(1, totalXp / numOrbs);

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

    // play a quiet xp sound with a randomised pitch
    public static void playXpSound(ServerPlayerEntity player) {
        float pitch = 0.8F + 0.4F * (float) Math.random();
        player.playSoundToPlayer(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                0.1F,
                pitch
        );
    }

    // apply a hidden slowness effect to the player
    public static void applyHiddenSlowness(ServerPlayerEntity player) {
        StatusEffectInstance slowness = new StatusEffectInstance(StatusEffects.SLOWNESS, 1, 2, false, false);
        player.addStatusEffect(slowness);
    }

}
