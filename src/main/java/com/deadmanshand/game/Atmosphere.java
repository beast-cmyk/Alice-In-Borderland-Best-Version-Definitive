package com.deadmanshand.game;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a venue sounds like, and what fear sounds like.
 *
 * <p>The background: every venue has its own sounds, made from Minecraft's, played now and then around whoever's there
 * (never on a loop you'd notice): water dripping and a light humming in an empty building, wind and distant thunder in
 * the streets, pipes knocking in the sewer, wheels clattering in the train, a clock ticking at the card tables, nothing
 * at all in the Joker's white room.
 *
 * <p>The tension: your heart. When something's hunting you and it's close, or it's seen you, or the clock's nearly out,
 * or your grip's going, or someone has just died near you, you hear it, and it beats faster the worse it gets. A sting
 * when you're spotted, a tick for each of the last ten seconds, a boom when the game starts. All of it is on the
 * "Ambient/Environment" volume slider.
 */
final class Atmosphere {
    /** One sound of a venue: how often (ticks between, at random), how loud, pitched how, and how far off (0: all round you). */
    record Sound(SoundEvent event, int minGap, int maxGap, float volume, float pitchLo, float pitchHi, double distance) {
    }

    private static Sound s(SoundEvent e, int minGap, int maxGap, float volume, float pitchLo, float pitchHi, double distance) {
        return new Sound(e, minGap, maxGap, volume, pitchLo, pitchHi, distance);
    }

    // --- the venues' sounds ------------------------------------------------------------------------------------------

    /** Out in the dead city at night: wind, distant thunder, a dog somewhere, someone else's game far off. */
    private static final List<Sound> CITY = List.of(
            s(SoundEvents.ITEM_ELYTRA_FLYING, 300, 800, 0.10F, 0.45F, 0.65F, 0),
            s(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1000, 2600, 0.22F, 0.5F, 0.7F, 70),
            s(SoundEvents.ENTITY_WOLF_HOWL, 1600, 3600, 0.25F, 0.6F, 0.8F, 55),
            s(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1400, 3200, 0.30F, 0.5F, 0.7F, 70),
            s(SoundEvents.ENTITY_BAT_AMBIENT, 700, 1600, 0.15F, 0.8F, 1.0F, 14),
            s(SoundEvents.BLOCK_AZALEA_LEAVES_STEP, 200, 600, 0.30F, 0.7F, 1.0F, 9));

    /** An empty concrete building: drips, a humming light, pipes knocking, a door somewhere, the odd draught. */
    private static final List<Sound> BUILDING = List.of(
            s(SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 60, 220, 0.45F, 0.8F, 1.1F, 6),
            s(SoundEvents.BLOCK_BEACON_AMBIENT, 160, 300, 0.10F, 0.55F, 0.65F, 0),
            s(SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 500, 1200, 0.22F, 0.45F, 0.6F, 18),
            s(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 800, 1800, 0.25F, 0.6F, 0.85F, 20),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 600, 1400, 0.05F, 0.35F, 0.45F, 0),
            s(SoundEvents.AMBIENT_CAVE, 1400, 3000, 0.45F, 0.9F, 1.0F, 0));

    /** Water underground: running water, drips, gurgles, pipes. */
    private static final List<Sound> SEWER = List.of(
            s(SoundEvents.BLOCK_WATER_AMBIENT, 40, 120, 0.50F, 0.8F, 1.0F, 7),
            s(SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 40, 140, 0.50F, 0.8F, 1.2F, 5),
            s(SoundEvents.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, 400, 900, 0.30F, 0.7F, 0.9F, 0),
            s(SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 500, 1100, 0.25F, 0.45F, 0.6F, 16),
            s(SoundEvents.AMBIENT_CAVE, 1600, 3200, 0.40F, 0.9F, 1.0F, 0));

    /** Machinery: hum, buzz, cables, clangs. */
    private static final List<Sound> MACHINES = List.of(
            s(SoundEvents.BLOCK_BEACON_AMBIENT, 140, 220, 0.18F, 0.5F, 0.6F, 0),
            s(SoundEvents.BLOCK_CONDUIT_AMBIENT, 220, 420, 0.15F, 0.55F, 0.7F, 0),
            s(SoundEvents.BLOCK_CHAIN_STEP, 300, 700, 0.30F, 0.6F, 0.8F, 10),
            s(SoundEvents.BLOCK_METAL_STEP, 400, 900, 0.30F, 0.5F, 0.7F, 16),
            s(SoundEvents.BLOCK_SCULK_SENSOR_CLICKING, 500, 1200, 0.20F, 0.6F, 0.8F, 10),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 400, 1000, 0.08F, 0.4F, 0.6F, 0));

    /** The train: wheels on the rails, the clack of the joints, couplings. */
    private static final List<Sound> TRAIN = List.of(
            s(SoundEvents.ENTITY_MINECART_INSIDE, 90, 130, 0.35F, 0.55F, 0.65F, 0),
            s(SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 16, 22, 0.16F, 1.3F, 1.5F, 3),
            s(SoundEvents.BLOCK_CHAIN_HIT, 200, 500, 0.30F, 0.6F, 0.8F, 6));

    /** A room with a table: the clock, the light's hum, a door down the corridor. */
    private static final List<Sound> TABLE = List.of(
            s(SoundEvents.BLOCK_BEACON_AMBIENT, 200, 320, 0.07F, 0.6F, 0.7F, 0),
            s(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 1200, 2600, 0.20F, 0.6F, 0.8F, 18),
            s(SoundEvents.AMBIENT_CAVE, 2400, 4800, 0.30F, 0.9F, 1.0F, 0));

    /** An old hall of wood: creaks, ropes, the building settling. */
    private static final List<Sound> HALL = List.of(
            s(SoundEvents.BLOCK_WOOD_STEP, 160, 420, 0.30F, 0.5F, 0.7F, 12),
            s(SoundEvents.BLOCK_LADDER_STEP, 300, 700, 0.25F, 0.6F, 0.8F, 10),
            s(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 900, 2000, 0.20F, 0.6F, 0.8F, 20),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 600, 1400, 0.05F, 0.35F, 0.45F, 0),
            s(SoundEvents.AMBIENT_CAVE, 1800, 3600, 0.35F, 0.9F, 1.0F, 0));

    /** A prison: steel doors, drips, someone banging on something far off. */
    private static final List<Sound> PRISON = List.of(
            s(SoundEvents.BLOCK_IRON_DOOR_CLOSE, 500, 1300, 0.30F, 0.5F, 0.7F, 20),
            s(SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 80, 260, 0.40F, 0.8F, 1.1F, 6),
            s(SoundEvents.BLOCK_METAL_STEP, 400, 900, 0.25F, 0.5F, 0.7F, 18),
            s(SoundEvents.BLOCK_BEACON_AMBIENT, 180, 300, 0.08F, 0.55F, 0.65F, 0),
            s(SoundEvents.AMBIENT_CAVE, 1800, 3600, 0.35F, 0.9F, 1.0F, 0));

    /** The labyrinth in the dark: drips, the walls, and the dread. */
    private static final List<Sound> LABYRINTH = List.of(
            s(SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 80, 240, 0.40F, 0.7F, 1.0F, 8),
            s(SoundEvents.AMBIENT_CAVE, 700, 1600, 0.50F, 0.85F, 1.0F, 0),
            s(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD, 900, 2000, 0.35F, 0.8F, 1.0F, 0),
            s(SoundEvents.BLOCK_STONE_STEP, 300, 800, 0.20F, 0.6F, 0.8F, 14));

    /** The amusement park after dark: rides creaking, chains, wind, the cats somewhere. */
    private static final List<Sound> PARK = List.of(
            s(SoundEvents.BLOCK_CHAIN_STEP, 200, 500, 0.35F, 0.5F, 0.7F, 14),
            s(SoundEvents.BLOCK_WOOD_STEP, 300, 700, 0.30F, 0.5F, 0.6F, 16),
            s(SoundEvents.ENTITY_CAT_HISS, 900, 2200, 0.25F, 0.5F, 0.7F, 30),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 300, 800, 0.10F, 0.45F, 0.65F, 0),
            s(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1200, 2800, 0.20F, 0.5F, 0.7F, 70));

    /** The camp: the bonfire, wind, voices far off. */
    private static final List<Sound> CAMP = List.of(
            s(SoundEvents.BLOCK_CAMPFIRE_CRACKLE, 40, 120, 0.40F, 0.8F, 1.0F, 10),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 400, 900, 0.08F, 0.45F, 0.65F, 0),
            s(SoundEvents.ENTITY_PILLAGER_AMBIENT, 900, 2000, 0.15F, 0.7F, 0.9F, 30),
            s(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 900, 2000, 0.20F, 0.7F, 0.9F, 20));

    /** The shrine: chimes in the wind, the trees, a bell far off. */
    private static final List<Sound> SHRINE = List.of(
            s(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 160, 420, 0.35F, 0.7F, 1.1F, 8),
            s(SoundEvents.BLOCK_AZALEA_LEAVES_STEP, 160, 400, 0.30F, 0.7F, 1.0F, 9),
            s(SoundEvents.BLOCK_BELL_RESONATE, 1600, 3600, 0.25F, 0.5F, 0.6F, 40),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 300, 800, 0.08F, 0.5F, 0.7F, 0));

    /** The glasshouse: frogs in the beds, drips on the glass, the wind outside. */
    private static final List<Sound> GARDEN = List.of(
            s(SoundEvents.ENTITY_FROG_AMBIENT, 200, 600, 0.25F, 0.8F, 1.0F, 12),
            s(SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 80, 260, 0.35F, 0.9F, 1.2F, 8),
            s(SoundEvents.BLOCK_AZALEA_LEAVES_STEP, 160, 420, 0.30F, 0.7F, 1.0F, 6),
            s(SoundEvents.ITEM_ELYTRA_FLYING, 500, 1100, 0.05F, 0.4F, 0.6F, 0));

    /** Up high: wind, and the structure moving in it. */
    private static final List<Sound> HEIGHTS = List.of(
            s(SoundEvents.ITEM_ELYTRA_FLYING, 160, 420, 0.20F, 0.5F, 0.8F, 0),
            s(SoundEvents.BLOCK_CHAIN_STEP, 300, 700, 0.30F, 0.5F, 0.7F, 8),
            s(SoundEvents.BLOCK_METAL_STEP, 400, 900, 0.30F, 0.45F, 0.6F, 10),
            s(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1200, 2800, 0.20F, 0.5F, 0.7F, 70));

    /** The stadium: wind over the stands, and the water under the pitch. */
    private static final List<Sound> STADIUM = List.of(
            s(SoundEvents.ITEM_ELYTRA_FLYING, 300, 700, 0.12F, 0.45F, 0.65F, 0),
            s(SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 200, 500, 0.30F, 0.6F, 0.8F, 16),
            s(SoundEvents.BLOCK_FIRE_EXTINGUISH, 300, 800, 0.15F, 0.5F, 0.7F, 16),
            s(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1200, 2800, 0.20F, 0.5F, 0.7F, 70));

    /** The Joker's white room: almost nothing at all. A ringing in your ears. */
    private static final List<Sound> WHITE = List.of(
            s(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1200, 2600, 0.08F, 1.6F, 1.9F, 0));

    static List<Sound> of(Kind kind) {
        return switch (kind) {
            case LIVE_OR_DIE, BINGO, KICK_THE_CAN, FUTURE_SUGOROKU -> BUILDING;
            case TAG, SURVIVAL, OSMOSIS, CROQUET -> CITY;
            case WOLF_AND_LAMBS -> GARDEN;
            case DISTANCE, THREE_SWITCHES, TARGET -> SEWER;
            case HUMAN_ELEVATOR, BINGO_TOWER -> HEIGHTS;
            case BORROWING_RACE, CHECKMATE, PARKING -> MACHINES;
            case BEAST_HUNT -> PARK;
            case WITCH_HUNT -> CAMP;
            case SOLITARY_CONFINEMENT -> PRISON;
            case BALANCE_SCALE, MAHJONG, OLD_MAID, ZOMBIE_HUNT -> TABLE;
            case BAGWORM, CHAMPION -> HALL;
            case SILENT -> LABYRINTH;
            case BOILING_DEATH -> STADIUM;
            case SACRED_FORTUNES -> SHRINE;
            case RUNAWAY_TRAIN -> TRAIN;
            case ONE_LAST_GAME -> WHITE;
        };
    }

    /** The games played around a table, where a clock ticks on the wall. */
    private static boolean clock(Kind kind) {
        return switch (kind) {
            case BALANCE_SCALE, MAHJONG, OLD_MAID, ZOMBIE_HUNT, SOLITARY_CONFINEMENT, FUTURE_SUGOROKU -> true;
            default -> false;
        };
    }

    // --- playing it --------------------------------------------------------------------------------------------------

    private final Game game;
    private final List<Sound> palette;
    private final boolean clock;
    /** For each player, when each of the venue's sounds next plays. */
    private final Map<UUID, long[]> next = new HashMap<>();
    private final Map<UUID, Long> nextBeat = new HashMap<>();
    private final Map<UUID, Float> lastDanger = new HashMap<>();
    private final Map<UUID, Long> stungAt = new HashMap<>();
    /** Deaths, for a while: where and when. */
    private final List<double[]> deaths = new java.util.ArrayList<>();

    Atmosphere(Game game) {
        this.game = game;
        this.palette = of(game.kind);
        this.clock = clock(game.kind);
    }

    private float between(float lo, float hi) {
        return lo + game.random.nextFloat() * (hi - lo);
    }

    /** Every tick while the venue is open or the game's on. */
    void tick(boolean running) {
        long now = game.world.getTime();
        deaths.removeIf(d -> now - (long) d[3] > 20 * 12);
        for (ServerPlayerEntity player : game.audience()) {
            background(player, now);
            if (running && game.isPlaying(player.getUuid())) {
                heart(player, now);
            }
        }
        if (running) {
            lastSeconds();
        }
    }

    private void background(ServerPlayerEntity player, long now) {
        long[] when = next.computeIfAbsent(player.getUuid(), k -> {
            long[] w = new long[palette.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = now + 40 + game.random.nextInt(Math.max(1, palette.get(i).minGap()));
            }
            return w;
        });
        for (int i = 0; i < palette.size(); i++) {
            if (now < when[i]) {
                continue;
            }
            Sound sound = palette.get(i);
            when[i] = now + sound.minGap() + game.random.nextInt(Math.max(1, sound.maxGap() - sound.minGap()));
            float pitch = between(sound.pitchLo(), sound.pitchHi());
            if (sound.distance() <= 0 || sound.distance() > 20) {
                // all round you, or far off (thunder, a howl, shots in the distance): quietly, right where you are
                player.playSound(sound.event(), SoundCategory.AMBIENT, sound.volume(), pitch);
            } else {
                // somewhere near (a drip, a door, a pipe): from that direction. Minecraft fades a sound to nothing 16
                // blocks out, so it's played within 12 and made louder at the source to arrive as loud as it should.
                double a = game.random.nextDouble() * Math.PI * 2.0;
                double r = Math.min(12.0, sound.distance() * (0.7 + game.random.nextDouble() * 0.6));
                Vec3d at = player.getPos().add(Math.cos(a) * r, -1.0 + game.random.nextDouble() * 4.0, Math.sin(a) * r);
                float volume = (float) Math.min(1.0, sound.volume() / (1.0 - r / 16.0));
                game.world.playSound(null, at.x, at.y, at.z, sound.event(), SoundCategory.AMBIENT, volume, pitch);
            }
        }
        if (clock && game.phase == Game.Phase.RUNNING && now % 20 == 0) {
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT, SoundCategory.AMBIENT, 0.06F, (now / 20) % 2 == 0 ? 1.9F : 1.6F);
        }
    }

    /** Your heart: faster and louder the more danger you're in; a sting when it jumps (you've been seen). */
    private void heart(ServerPlayerEntity player, long now) {
        UUID id = player.getUuid();
        float danger = game.danger(player);
        for (double[] d : deaths) {
            double dist = player.getPos().distanceTo(new Vec3d(d[0], d[1], d[2]));
            if (dist < 24.0) {
                danger = Math.max(danger, (float) (0.7 * (1.0 - (now - d[3]) / (20.0 * 12.0)) * (1.0 - dist / 30.0)));
            }
        }
        float before = lastDanger.getOrDefault(id, 0.0F);
        lastDanger.put(id, danger);
        if (danger > 0.72F && before < 0.5F && now - stungAt.getOrDefault(id, -1000L) > 20 * 15) {
            stungAt.put(id, now);
            player.playSound(SoundEvents.ENTITY_WARDEN_NEARBY_CLOSEST, SoundCategory.AMBIENT, 0.7F, 1.0F);
        }
        if (danger < 0.3F) {
            nextBeat.remove(id);
            return;
        }
        long beat = nextBeat.getOrDefault(id, now);
        if (now >= beat) {
            float t = Math.min(1.0F, (danger - 0.3F) / 0.7F);
            player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.35F + 0.65F * t, 0.95F + 0.2F * t);
            nextBeat.put(id, now + Math.round(26 - 17 * t));
        }
    }

    /** The last ten seconds on the clock: a tick for each, higher as it runs out. */
    private void lastSeconds() {
        if (game.timeLimit() <= 0 || game.timeLeft <= 0 || game.timeLeft > 20 * 10 || game.timeLeft % 20 != 0) {
            return;
        }
        int left = game.timeLeft / 20;
        for (ServerPlayerEntity player : game.audience()) {
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT, SoundCategory.AMBIENT, 0.5F, 1.0F + (10 - left) * 0.08F);
        }
    }

    /** The game begins: a low boom, and the dread. */
    void start() {
        for (ServerPlayerEntity player : game.audience()) {
            player.playSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 0.5F, 0.5F);
            player.playSound(SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.AMBIENT, 0.6F, 0.9F);
        }
    }

    /** Someone died at {@code at}: whoever's near feels it. */
    void death(Vec3d at) {
        long now = game.world.getTime();
        deaths.add(new double[]{at.x, at.y, at.z, now});
        for (ServerPlayerEntity player : game.audience()) {
            if (player.getPos().distanceTo(at) < 28.0 && now - stungAt.getOrDefault(player.getUuid(), -1000L) > 100) {
                stungAt.put(player.getUuid(), now);
                player.playSound(SoundEvents.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.8F, 0.9F);
            }
        }
    }
}
