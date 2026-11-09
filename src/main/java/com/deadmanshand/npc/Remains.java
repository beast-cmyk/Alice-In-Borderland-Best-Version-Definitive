package com.deadmanshand.npc;

import com.deadmanshand.Borderland;
import com.deadmanshand.Laser;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * What's left after a death: the body, where they fell, for a while. And now and then, far off across the city, a red
 * line drops out of the sky onto somebody you'll never meet.
 */
public final class Remains {
    private static final long KEEP = 20L * 60L * 8L;
    private static final List<Object[]> BODIES = new ArrayList<>();
    private static long nextFar = 20L * 60L * 3L;

    private Remains() {
    }

    public static void init() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.world instanceof ServerWorld world) || !Borderland.is(world)) {
                return;
            }
            if (entity instanceof ServerPlayerEntity || entity instanceof Contestant c && !c.isBody()) {
                Contestant.corpse(world, entity);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(Remains::tick);
    }

    static void left(Contestant body) {
        BODIES.add(new Object[]{body.getUuid(), body.world.getTime() + KEEP, body.world});
        // never more than a few dozen lying about
        while (BODIES.size() > 40) {
            Object[] oldest = BODIES.remove(0);
            Entity e = ((ServerWorld) oldest[2]).getEntity((UUID) oldest[0]);
            if (e != null) {
                e.discard();
            }
        }
    }

    private static void tick(MinecraftServer server) {
        for (Iterator<Object[]> it = BODIES.iterator(); it.hasNext(); ) {
            Object[] b = it.next();
            ServerWorld world = (ServerWorld) b[2];
            if (world.getTime() > (Long) b[1]) {
                Entity e = world.getEntity((UUID) b[0]);
                if (e != null) {
                    e.discard();
                }
                it.remove();
            }
        }
        // somewhere out there, someone else's visa just ran out
        for (ServerWorld world : server.getWorlds()) {
            if (!Borderland.is(world) || world.getPlayers().isEmpty() || world.getTime() < nextFar) {
                continue;
            }
            nextFar = world.getTime() + 20L * 60L * (3 + world.random.nextInt(6));
            ServerPlayerEntity someone = world.getPlayers().get(world.random.nextInt(world.getPlayers().size()));
            double angle = world.random.nextDouble() * Math.PI * 2;
            double far = 70 + world.random.nextInt(60);
            double x = someone.getX() + Math.cos(angle) * far;
            double z = someone.getZ() + Math.sin(angle) * far;
            if (!world.isChunkLoaded(new BlockPos((int) x, 64, (int) z))) {
                continue;
            }
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) x, (int) z);
            Laser.beam(world, x, y, z);
            world.playSound(null, someone.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 0.35F, 0.4F);
        }
    }
}
