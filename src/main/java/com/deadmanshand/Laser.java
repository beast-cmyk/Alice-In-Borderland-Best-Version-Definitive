package com.deadmanshand;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The red line from the sky. It never misses, armour and totems don't help, and creative players are spared. */
public final class Laser {
    public static final DamageSource LASER = new Source("deadmanshand.laser");
    /** A game's collar going off. */
    public static final DamageSource COLLAR = new Source("deadmanshand.collar");
    /** A vat of acid, tipped. */
    public static final DamageSource ACID = new Source("deadmanshand.acid");
    /** Whatever it is that roars in the dark. */
    public static final DamageSource BEAST = new Source("deadmanshand.beast");
    /** Scalding water. */
    public static final DamageSource BOILING = new Source("deadmanshand.boiling");
    /** A chair, a floor or a collar wired to the mains. */
    public static final DamageSource SHOCK = new Source("deadmanshand.shock");
    /** A bomb going off in your hands. */
    public static final DamageSource BLAST = new Source("deadmanshand.blast");
    /** Struck down by arrows. */
    public static final DamageSource ARROWS = new Source("deadmanshand.arrows");
    /** Gas you shouldn't have breathed. */
    public static final DamageSource POISON = new Source("deadmanshand.poison");
    /** A long way down. */
    public static final DamageSource FALL = new Source("deadmanshand.fall");
    /** The water that comes for whoever's left. */
    public static final DamageSource FLOOD = new Source("deadmanshand.flood");

    private static final class Source extends DamageSource {
        Source(String name) {
            super(name);
            setBypassesArmor();
            setOutOfWorld();
        }
    }

    private Laser() {
    }

    /** Kills {@code target} with a laser from the sky. {@code why} is shown to a player who's spared (creative). */
    /** How long it holds you before it fires: long enough to know. */
    private static final int LOCK = 26;
    /** Who's been picked out: the target, ticks left. */
    private static final List<Object[]> LOCKED = new ArrayList<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    /**
     * The laser picks {@code target} out: a thin red line comes down out of the sky onto them, they can't move, there's a
     * rising whine, and then it fires.
     */
    public static void strike(LivingEntity target, @Nullable String why) {
        if (!(target.world instanceof ServerWorld world)) {
            return;
        }
        if (target instanceof ServerPlayerEntity player && (player.isCreative() || player.isSpectator())) {
            beam(world, target.getX(), target.getY(), target.getZ());
            if (why != null) {
                player.sendMessage(Text.literal(why + " (You're in creative, so the laser spared you.)").formatted(Formatting.RED), false);
            }
            return;
        }
        for (Object[] l : LOCKED) {
            if (l[0] == target) {
                return;
            }
        }
        LOCKED.add(new Object[]{target, LOCK});
        if (target instanceof ServerPlayerEntity player && why != null) {
            player.sendMessage(Text.literal(why).formatted(Formatting.DARK_RED), true);
        }
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, LOCK + 10, 255, false, false));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, LOCK + 10, 250, false, false));
        world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 2.0F, 1.5F);
    }

    private static void tick() {
        DustParticleEffect thin = new DustParticleEffect(new Vec3f(1.0F, 0.05F, 0.05F), 0.5F);
        for (Iterator<Object[]> it = LOCKED.iterator(); it.hasNext(); ) {
            Object[] l = it.next();
            LivingEntity target = (LivingEntity) l[0];
            int left = (Integer) l[1] - 1;
            l[1] = left;
            if (!(target.world instanceof ServerWorld world) || target.isRemoved() || !target.isAlive()) {
                it.remove();
                continue;
            }
            target.setVelocity(0, Math.min(0, target.getVelocity().y), 0);
            if (left == 20) {
                // the red dot on someone: everyone near turns to see who it's on
                for (com.deadmanshand.npc.Contestant c : world.getEntitiesByClass(com.deadmanshand.npc.Contestant.class,
                        target.getBoundingBox().expand(16.0), c -> c != target && !c.isBody())) {
                    c.notice(target, 30);
                }
            }
            if (left % 2 == 0) {
                double head = target.getEyeY() + 0.25;
                for (double h = 0.0; h < 60.0; h += 1.2) {
                    world.spawnParticles(thin, target.getX(), head + h, target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
                }
                world.spawnParticles(thin, target.getX(), head, target.getZ(), 3, 0.05, 0.05, 0.05, 0.0);
            }
            if (left <= 0) {
                it.remove();
                beam(world, target.getX(), target.getY(), target.getZ());
                // the crack of it: everyone close flinches back from it
                for (com.deadmanshand.npc.Contestant c : world.getEntitiesByClass(com.deadmanshand.npc.Contestant.class,
                        target.getBoundingBox().expand(12.0), c -> c != target && !c.isBody())) {
                    c.startle(target.getPos());
                }
                target.damage(LASER, Float.MAX_VALUE);
                if (target.isAlive()) {
                    target.kill();
                }
            }
        }
    }

    /** Just the beam and the sound, no harm. */
    public static void beam(ServerWorld world, double x, double y, double z) {
        DustParticleEffect red = new DustParticleEffect(new Vec3f(1.0F, 0.04F, 0.04F), 1.4F);
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(x, y, z) > 200.0 * 200.0) {
                continue;
            }
            for (double h = 0.0; h < 96.0; h += 0.6) {
                world.spawnParticles(viewer, red, true, x, y + h, z, 1, 0.01, 0.0, 0.01, 0.0);
            }
            world.spawnParticles(viewer, ParticleTypes.FLASH, true, x, y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(viewer, ParticleTypes.LAVA, true, x, y + 0.3, z, 6, 0.2, 0.1, 0.2, 0.0);
        }
        world.playSound(null, x, y, z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 2.0F, 2.0F);
        world.playSound(null, x, y, z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.HOSTILE, 2.0F, 0.6F);
    }
}
