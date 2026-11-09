package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Hunter;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Five of Spades: a nine-storey L-shaped apartment block with three stairwells. Somewhere in it is the safe room, with
 * a spade on the wall and two buttons: two different people must press them at the same moment. You have twenty
 * minutes before the bomb goes off. "It" walks the galleries in a horse's head with a submachine gun; the mask means
 * it only sees straight ahead. If it touches you, you're dead. The ground around the building is mined.
 */
final class Tag extends Game {
    private static final int FLOORS = 9;
    private static final int HUNTERS = 2;

    private record Flat(int floor, int index, boolean north, BlockPos center, BlockPos door) {
    }

    private int cx;
    private int cz;
    private final List<Flat> flats = new ArrayList<>();
    private Flat goal;
    private BlockPos buttonA;
    private BlockPos buttonB;
    private final Map<UUID, Integer> pressedA = new HashMap<>();
    private final Map<UUID, Integer> pressedB = new HashMap<>();
    private final List<UUID> hunters = new ArrayList<>();
    private final Map<UUID, Vec3d> lastSeen = new HashMap<>();
    private final Map<UUID, UUID> chasing = new HashMap<>();
    private final Map<UUID, Integer> lostFor = new HashMap<>();
    private final Map<UUID, Integer> shotAt = new HashMap<>();
    private final Map<UUID, Set<Integer>> searched = new HashMap<>();
    private final Map<UUID, Integer> searching = new HashMap<>();
    private boolean goalKnown;
    private int ticks;
    private int countdown = -1;

    Tag(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    private int base(int floor) {
        return origin.getY() + 4 * floor;
    }

    @Override
    protected void plan(Builder b) {
        cx = origin.getX() - 4;
        cz = origin.getZ();
        int oy = origin.getY();
        int top = base(FLOORS);
        bounds = new Box(cx - 1, oy - 1, cz - 45, cx + 46, top + 3, cz + 2);
        entry = new Box(cx, oy, cz - 2, cx + 9, oy + 3, cz + 2);
        entryPos = new BlockPos(cx + 4, oy, cz);
        approach = new BlockPos(cx + 4, oy, cz + 7);

        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState rail = Blocks.IRON_BARS.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        // Clear the lot, lay the mined ground of the courtyard
        b.fill(cx - 1, oy, cz - 45, cx + 45, top + 3, cz + 1, air);
        b.fill(cx - 1, oy - 1, cz - 45, cx + 45, oy - 1, cz + 1, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(cx + 10, oy - 1, cz - 34, cx + 45, oy - 1, cz + 1, Blocks.COARSE_DIRT.getDefaultState());
        for (int x = cx + 12; x <= cx + 43; x += 5) {
            for (int z = cz - 32; z <= cz - 1; z += 5) {
                b.set(x, oy, z, Blocks.STONE_PRESSURE_PLATE.getDefaultState());     // the mines
            }
        }
        for (int f = 0; f < FLOORS; f++) {
            int y = base(f);
            // North wing: floor, outer wall, flats, front wall, gallery, railing
            b.fill(cx, y - 1, cz - 44, cx + 44, y - 1, cz - 36, slab);
            b.fill(cx + 8, y, cz - 44, cx + 36, y + 2, cz - 44, wall);
            b.fill(cx + 8, y, cz - 38, cx + 36, y + 2, cz - 38, wall);
            for (int i = 0; i < 7; i++) {
                int wx = cx + 8 + 4 * i;
                b.fill(wx, y, cz - 43, wx, y + 2, cz - 39, wall);
                b.fill(wx + 1, y, cz - 43, wx + 3, y + 2, cz - 39, air);
                b.fill(wx + 2, y, cz - 38, wx + 2, y + 1, cz - 38, air);
                b.set(wx + 2, y + 1, cz - 44, Blocks.GLASS_PANE.getDefaultState());
                furnish(b, wx + 1, y, cz - 43, true);
                flats.add(new Flat(f, i, true, new BlockPos(wx + 2, y, cz - 41), new BlockPos(wx + 2, y, cz - 37)));
            }
            b.fill(cx + 36, y, cz - 43, cx + 36, y + 2, cz - 39, wall);
            b.fill(cx + 8, y, cz - 35, cx + 44, y, cz - 35, rail);
            // West wing
            b.fill(cx, y - 1, cz - 36, cx + 8, y - 1, cz, slab);
            b.fill(cx, y, cz - 36, cx, y + 2, cz - 8, wall);
            b.fill(cx + 6, y, cz - 36, cx + 6, y + 2, cz - 8, wall);
            for (int j = 0; j < 7; j++) {
                int wz = cz - 36 + 4 * j;
                b.fill(cx + 1, y, wz, cx + 5, y + 2, wz, wall);
                b.fill(cx + 1, y, wz + 1, cx + 5, y + 2, wz + 3, air);
                b.fill(cx + 6, y, wz + 2, cx + 6, y + 1, wz + 2, air);
                b.set(cx, y + 1, wz + 2, Blocks.GLASS_PANE.getDefaultState());
                furnish(b, cx + 1, y, wz + 1, false);
                flats.add(new Flat(f, 7 + j, false, new BlockPos(cx + 3, y, wz + 2), new BlockPos(cx + 7, y, wz + 2)));
            }
            b.fill(cx + 1, y, cz - 8, cx + 5, y + 2, cz - 8, wall);
            b.fill(cx + 9, y, cz - 35, cx + 9, y, cz - 8, rail);
            if (f > 0) {
                // the open edges beside the south stairwell
                b.fill(cx + 9, y, cz - 7, cx + 9, y, cz, rail);
                b.set(cx + 8, y, cz, rail);
            }
            // Lights on the galleries, every few doors
            for (int x = cx + 10; x <= cx + 40; x += 8) {
                b.set(x, y + 2, cz - 37, Blocks.SEA_LANTERN.getDefaultState());
            }
            for (int z = cz - 33; z <= cz - 9; z += 8) {
                b.set(cx + 8, y + 2, z, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // The three stairwells: at the corner, at the east end and at the south end
        stairs(b, cx, cz - 44, false, false);
        stairs(b, cx + 37, cz - 44, true, false);
        stairs(b, cx, cz - 7, false, true);
        // Roof
        b.fill(cx, top - 1, cz - 44, cx + 44, top - 1, cz - 36, slab);
        b.fill(cx, top - 1, cz - 36, cx + 8, top - 1, cz, slab);
        b.fill(cx, top, cz - 44, cx + 44, top, cz - 44, wall);
        b.fill(cx, top, cz - 44, cx, top, cz, wall);
        b.sign(new BlockPos(cx + 4, oy + 2, cz + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        b.set(cx + 2, oy + 2, cz + 2, Blocks.SEA_LANTERN.getDefaultState());
        b.set(cx + 6, oy + 2, cz + 2, Blocks.SEA_LANTERN.getDefaultState());

        // The safe room: one flat, somewhere from the second floor up
        List<Flat> candidates = new ArrayList<>();
        for (Flat flat : flats) {
            if (flat.floor >= 1) {
                candidates.add(flat);
            }
        }
        goal = candidates.get(random.nextInt(candidates.size()));
        int y = goal.center.getY();
        if (goal.north) {
            int x0 = goal.center.getX() - 1;
            buttonA = new BlockPos(x0, y + 1, cz - 42);
            buttonB = new BlockPos(x0 + 2, y + 1, cz - 40);
            b.fill(x0, y, cz - 43, x0 + 2, y + 2, cz - 39, air);
            b.sign(new BlockPos(x0 + 1, y + 1, cz - 43), Direction.SOUTH, "♠", "SAFE ZONE", "two of you", "press at once");
            b.set(buttonA, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.EAST));
            b.set(buttonB, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.WEST));
            b.set(x0 + 1, y + 2, cz - 41, Blocks.REDSTONE_LAMP.getDefaultState());
        } else {
            int z0 = goal.center.getZ() - 1;
            buttonA = new BlockPos(cx + 2, y + 1, z0);
            buttonB = new BlockPos(cx + 4, y + 1, z0 + 2);
            b.fill(cx + 1, y, z0, cx + 5, y + 2, z0 + 2, air);
            b.sign(new BlockPos(cx + 1, y + 1, z0 + 1), Direction.EAST, "♠", "SAFE ZONE", "two of you", "press at once");
            b.set(buttonA, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.SOUTH));
            b.set(buttonB, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, WallMountLocation.WALL).with(net.minecraft.block.HorizontalFacingBlock.FACING, Direction.NORTH));
        }
    }

    /** A little furniture so there's somewhere to hide: a bed, a table, a wardrobe. */
    private void furnish(Builder b, int x0, int y, int z0, boolean north) {
        if (random.nextFloat() < 0.6F) {
            b.set(north ? x0 : x0 + 4, y, north ? z0 : z0 + 2, Blocks.OAK_PLANKS.getDefaultState());
            b.set(north ? x0 : x0 + 4, y + 1, north ? z0 : z0 + 2, Blocks.BOOKSHELF.getDefaultState());
        }
        if (random.nextFloat() < 0.5F) {
            b.set(north ? x0 + 2 : x0 + 2, y, north ? z0 + 3 : z0, Blocks.WHITE_WOOL.getDefaultState());
        }
    }

    /**
     * A stairwell of 8 x 8 inside, NW corner (sx, sz): two short flights per floor with a landing halfway; you walk on
     * the floor's landing along the south side and the strip along the east side. Solid on the west and north; bars
     * on the east and south where that side faces the outside.
     */
    private void stairs(Builder b, int sx, int sz, boolean eastOutside, boolean southOutside) {
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState wall = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState step = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        int top = base(FLOORS);
        b.fill(sx, base(0), sz, sx + 7, top + 1, sz + 7, air);
        b.fill(sx, base(0), sz, sx, top, sz + 7, wall);
        b.fill(sx, base(0), sz, sx + 7, top, sz, wall);
        for (int f = 0; f < FLOORS; f++) {
            int y = base(f);
            b.fill(sx + 1, y - 1, sz + 6, sx + 7, y - 1, sz + 7, floor);
            b.fill(sx + 6, y - 1, sz + 1, sx + 7, y - 1, sz + 7, floor);
            if (eastOutside) {
                b.fill(sx + 7, y, sz + 1, sx + 7, y, sz + 7, Blocks.IRON_BARS.getDefaultState());
            }
            if (southOutside && f > 0) {
                b.fill(sx + 1, y, sz + 7, sx + 7, y, sz + 7, Blocks.IRON_BARS.getDefaultState());
            }
            if (f == FLOORS - 1) {
                b.fill(sx + 1, y + 3, sz + 1, sx + 7, y + 3, sz + 7, floor);
                continue;
            }
            // up the west lane, northwards
            for (int x = sx + 1; x <= sx + 2; x++) {
                b.set(x, y, sz + 5, step.with(StairsBlock.FACING, Direction.NORTH));
                b.set(x, y, sz + 4, wall);
                b.set(x, y + 1, sz + 4, step.with(StairsBlock.FACING, Direction.NORTH));
            }
            // the landing halfway up
            b.fill(sx + 1, y + 1, sz + 1, sx + 5, y + 1, sz + 3, floor);
            // on up the east lane, southwards
            for (int x = sx + 4; x <= sx + 5; x++) {
                b.set(x, y + 2, sz + 4, step.with(StairsBlock.FACING, Direction.SOUTH));
                b.set(x, y + 2, sz + 5, wall);
                b.set(x, y + 3, sz + 5, step.with(StairsBlock.FACING, Direction.SOUTH));
            }
            b.fill(sx + 3, y, sz + 4, sx + 3, y + 3, sz + 5, wall);
            b.set(sx + 6, y + 2, sz + 1, Blocks.SEA_LANTERN.getDefaultState());
        }
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Somewhere in this building is the safe zone. Find it before the time limit.",
                "In the safe zone, two different people must press its two buttons at the same moment.",
                "\"It\" is hunting you. If it touches you, you lose.",
                "When the time limit runs out, a bomb in the building goes off.",
                "The grounds around the building are mined. Time limit: twenty minutes."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 20;
    }

    @Override
    protected void arrange() {
        // On the open floor at the foot of the south stairwell, each in the middle of a block, clear of the flight of
        // steps and the stairwell's inner wall (standing on a block's corner put two of them with their heads inside
        // that wall, and they suffocated while the rules were read)
        int i = 0;
        for (LivingEntity entity : living()) {
            entity.teleport(cx + 4.5 + i % 4, origin.getY(), cz - 0.5 + (i / 4) % 2);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            i++;
        }
        for (int h = 0; h < HUNTERS; h++) {
            Hunter hunter = DeadMansHand.HUNTER.create(world);
            if (hunter == null) {
                continue;
            }
            BlockPos at = h == 0 ? new BlockPos(cx + 40, base(4), cz - 37) : new BlockPos(cx + 7, base(6), cz - 20);
            hunter.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            hunter.addScoreboardTag(DeadMansHand.ID);
            world.spawnEntity(hunter);
            hunter.hold();
            hunters.add(hunter.getUuid());
        }
    }

    @Override
    protected void begin() {
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
            searched.put(contestant.getUuid(), new HashSet<>());
        }
        for (Hunter hunter : hunterList()) {
            hunter.stopAll();
        }
        Contestant first = strangers().isEmpty() ? null : strangers().get(0);
        if (first != null) {
            first.speak("The game just started. Something in a horse mask is walking the galleries above you with a gun.",
                    Lines.pick(random, "Split up. Whoever finds it, shout.", "It's wearing a horse's head. A horse's head with a gun.", "Stay low. Don't let it see you."));
        }
        world.playSound(null, cx + 22, base(4), cz - 22, SoundEvents.ENTITY_HORSE_ANGRY, SoundCategory.HOSTILE, 3.0F, 0.6F);
    }

    private List<Hunter> hunterList() {
        List<Hunter> list = new ArrayList<>();
        for (UUID id : hunters) {
            if (world.getEntity(id) instanceof Hunter hunter && hunter.isAlive()) {
                list.add(hunter);
            }
        }
        return list;
    }

    @Override
    protected void running() {
        ticks++;
        // The mines
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && entity.getX() > cx + 9.6 && entity.getZ() > cz - 34.6 && entity.getY() < origin.getY() + 1.0) {
                world.spawnParticles(ParticleTypes.EXPLOSION, entity.getX(), entity.getY(), entity.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
                world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5F, 1.0F);
                fail(id, "You stepped on a mine.");
            }
        }
        for (Hunter hunter : hunterList()) {
            hunt(hunter);
        }
        strangersThink();
        if (countdown > 0 && --countdown == 0) {
            pressTogether();
        }
        if (timeLeft == 20 * 60 || timeLeft == 20 * 60 * 5 || timeLeft == 20 * 30) {
            announce(timeLeft == 20 * 30 ? "Thirty seconds." : timeLeft / (20 * 60) + (timeLeft == 20 * 60 ? " minute left." : " minutes left."));
        }
    }

    private void hunt(Hunter hunter) {
        UUID id = hunter.getUuid();
        LivingEntity target = null;
        UUID targetId = chasing.get(id);
        if (targetId != null) {
            LivingEntity known = entity(targetId);
            target = known != null && known.isAlive() && isPlaying(targetId) ? known : null;
        }
        if (ticks % 5 == 0) {
            for (LivingEntity prey : living()) {
                if (hunter.sees(prey) && (target == null || hunter.squaredDistanceTo(prey) < hunter.squaredDistanceTo(target))) {
                    target = prey;
                }
            }
        }
        if (target != null && hunter.sees(target)) {
            chasing.put(id, target.getUuid());
            lastSeen.put(id, target.getPos());
            lostFor.put(id, 0);
            hunter.look(target);
            hunter.go(target.getPos(), 1.25);
            double distance = hunter.distanceTo(target);
            if (distance < 1.4) {
                fail(target.getUuid(), "It touched you. You're it no longer: you're out.");
                world.playSound(null, hunter.getX(), hunter.getY(), hunter.getZ(), SoundEvents.ENTITY_HORSE_DEATH, SoundCategory.HOSTILE, 1.5F, 0.6F);
            } else if (distance < 18.0 && ticks - shotAt.getOrDefault(id, -100) > 22) {
                shotAt.put(id, ticks);
                hunter.shoot(target, random);
                hunter.shoot(target, random);
                gunfire(hunter, target);
            }
            return;
        }
        int lost = lostFor.merge(id, 1, Integer::sum);
        Vec3d last = lastSeen.get(id);
        if (last != null && lost < 80) {
            hunter.look(null);
            hunter.go(last, 1.0);
            if (hunter.squaredDistanceTo(last) < 2.0) {
                lastSeen.remove(id);
            }
            return;
        }
        chasing.remove(id);
        // Patrol: walk to a door on a floor near its own
        if (hunter.arrived(1.5) || ticks % 400 == 0) {
            int floor = Math.max(0, Math.min(FLOORS - 1, (int) ((hunter.getY() - origin.getY()) / 4) + random.nextInt(3) - 1));
            List<Flat> choices = new ArrayList<>();
            for (Flat flat : flats) {
                if (flat.floor == floor) {
                    choices.add(flat);
                }
            }
            Flat next = choices.get(random.nextInt(choices.size()));
            hunter.look(null);
            hunter.go(next.door, 0.9);
        }
    }

    private void strangersThink() {
        List<Hunter> list = hunterList();
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            // Instinct: what a frightened person does when it's there, before any thinking
            if (instinct(contestant, list)) {
                continue;
            }
            if (goalKnown || contestant.squaredDistanceTo(Vec3d.ofCenter(goal.center)) < 9.0) {
                if (!goalKnown) {
                    goalKnown = true;
                    announceFind(contestant);
                }
                BlockPos stand = freeButton(contestant);
                contestant.go(stand, 1.1);
                contestant.lookAt(Vec3d.ofCenter(goal.door));
                continue;
            }
            if (ticks < hideUntil.getOrDefault(id, 0)) {
                continue;       // keeping still, in a flat, out of its sight
            }
            Integer target = searching.get(id);
            if (target == null || contestant.arrived(1.2)) {
                if (target != null) {
                    searched.computeIfAbsent(id, k -> new HashSet<>()).add(target);
                }
                Flat next = nextFlat(contestant);
                searching.put(id, flats.indexOf(next));
                contestant.go(next.center, 1.0);
            }
        }
        // Two strangers at the buttons count down and press together
        if (countdown < 0 && strangerAt(buttonA) != null && strangerAt(buttonB) != null) {
            countdown = 60;
            Contestant one = strangerAt(buttonA);
            if (one != null) {
                one.say("On three. One... two...");
            }
        }
    }

    // --- instinct ---------------------------------------------------------------------------------------------------
    //
    // When it's there (it's looking at them, they've caught sight of it close by, or it's shooting at them or someone
    // beside them) a person doesn't work out the flat furthest from it. They flinch first. Then, by who they are and how
    // scared they already are: some freeze for a second or two, the calm ones slip quietly out of its sight, the
    // terrified ones bolt for it; out of its sight is the point, the nearest doorway, the stairwell, a wall between them
    // and it, not distance. Once they're hidden, they keep still a while, the ones with their wits about them warn the
    // others where it is, and then they think again (that's the AI's again).

    private enum Fright { STARTLED, FROZEN, TAKING_COVER, HIDING }

    private static final class Panic {
        Fright state;
        int until;
        int since;
        @Nullable Vec3d cover;
        double pace;
        final UUID from;
        int outOfSight;

        Panic(Fright state, int until, UUID from) {
            this.state = state;
            this.until = until;
            this.from = from;
        }
    }

    private final Map<UUID, Panic> panic = new HashMap<>();
    /** When each stranger was last shot at, or had someone shot right beside them. */
    private final Map<UUID, Integer> underFire = new HashMap<>();
    /** When each last screamed or shouted about it (not every second). */
    private final Map<UUID, Integer> criedAt = new HashMap<>();
    /** The floor where each last heard it shooting, and when: they'll keep away from it for a while. */
    private final Map<UUID, int[]> heardShooting = new HashMap<>();

    private int floorOf(net.minecraft.entity.Entity e) {
        return Math.max(0, Math.min(FLOORS - 1, (int) Math.floor((e.getY() - origin.getY() + 0.5) / 4.0)));
    }

    private String sideOf(net.minecraft.entity.Entity e) {
        return e.getX() < cx + 9 ? "west side" : "north side";
    }

    /** It fired at {@code target}: whoever was hit or beside them is under fire; everyone within earshot hears it. */
    private void gunfire(Hunter hunter, LivingEntity target) {
        int floor = floorOf(hunter);
        for (Contestant c : strangers()) {
            double d = c.distanceTo(target);
            if (c == target || d < 5.0) {
                underFire.put(c.getUuid(), ticks);
            } else if (c.distanceTo(hunter) < 30.0 && !panic.containsKey(c.getUuid())) {
                // heard, not seen: they turn toward it, and they'll keep away from that floor
                c.notice(hunter.getEyePos(), 40);
                heardShooting.put(c.getUuid(), new int[]{floor, ticks});
                if (floorOf(c) != floor || c.distanceTo(hunter) > 16.0) {
                    c.remember("You heard gunfire " + (floor == floorOf(c) ? "on your floor" : floor > floorOf(c) ? "above you, floor " + (floor + 1)
                            : "below you, floor " + (floor + 1)) + ".");
                }
                if (ticks - criedAt.getOrDefault(c.getUuid(), -400) > 300 && random.nextFloat() < 0.25F) {
                    criedAt.put(c.getUuid(), ticks);
                    c.say(Lines.pick(random, "Gunshots. " + (floor > floorOf(c) ? "Upstairs." : floor < floorOf(c) ? "Below us." : "Close."),
                            "Did you hear that?", "It's shooting at someone."));
                }
            }
        }
    }

    /** Handles {@code c}'s instinct this tick; true if it's in charge of them (nothing else should move them). */
    private boolean instinct(Contestant c, List<Hunter> list) {
        UUID id = c.getUuid();
        Hunter near = null;
        double nearest = Double.MAX_VALUE;
        for (Hunter hunter : list) {
            double d = hunter.distanceTo(c);
            if (d < nearest) {
                nearest = d;
                near = hunter;
            }
        }
        Panic p = panic.get(id);
        if (near == null) {
            panic.remove(id);
            return false;
        }
        boolean itSeesThem = near.sees(c) || nearest < 5.0 && near.canSee(c);
        boolean theySeeIt = nearest < 16.0 && c.canSee(near) && facing(c, near) > -0.2;
        boolean shot = ticks - underFire.getOrDefault(id, -100) < 10;
        boolean triggered = itSeesThem || theySeeIt || shot;
        // someone who chose to hide keeps still while it passes, unless it's looking right at them
        if (p == null && ticks < hideUntil.getOrDefault(id, 0) && !itSeesThem && !shot) {
            if (nearest < 12.0) {
                c.lookAt(near.getEyePos());
            }
            return false;
        }
        if (p == null || p.state == Fright.HIDING && (itSeesThem || shot)) {
            if (!triggered || atButton(c) && goalKnown && !shot) {
                return p != null && p.state == Fright.HIDING && hiding(c, p, near);
            }
            // the flinch: the half second it takes a person to react
            p = new Panic(Fright.STARTLED, ticks + 5 + random.nextInt(6), near.getUuid());
            p.since = ticks;
            panic.put(id, p);
            searching.remove(id);
            hideUntil.remove(id);
            c.stopAll();
            c.startle(near.getEyePos());
            c.feelings.scared(c, shot ? 0.3F : 0.2F);
            cry(c, near, shot);
            reflex(c);
            return true;
        }
        reflex(c);
        switch (p.state) {
            case STARTLED -> {
                if (ticks < p.until) {
                    return true;
                }
                // then: freeze, or get out of its sight, fast or quietly, by who they are and how scared they are
                float freeze = 0.06F + 0.3F * c.feelings.fear + (c.nature == Contestant.Nature.TIMID ? 0.15F : 0.0F)
                        - (c.nature == Contestant.Nature.BRAVE || c.nature == Contestant.Nature.RECKLESS ? 0.05F : 0.0F);
                if (random.nextFloat() < freeze && !shot) {
                    p.state = Fright.FROZEN;
                    p.until = ticks + 15 + random.nextInt(30);
                    c.hold();
                    c.lookAt(near.getEyePos());
                    return true;
                }
                takeCover(c, p, near);
                return true;
            }
            case FROZEN -> {
                c.lookAt(near.getEyePos());
                if (ticks >= p.until || shot || nearest < 3.0) {
                    takeCover(c, p, near);
                }
                return true;
            }
            case TAKING_COVER -> {
                boolean outOfSight = !near.canSee(c);
                p.outOfSight = outOfSight ? p.outOfSight + 1 : 0;
                boolean there = p.cover != null && c.squaredDistanceTo(p.cover) < 1.2 * 1.2;
                if (there || p.outOfSight >= 15 && nearest > 6.0 || ticks - p.since > 20 * 9) {
                    hide(c, p, near);
                } else if (p.cover != null && near.canSee(c) && near.sees(c) && ticks - p.since > 40 && c.getNavigation().isIdle() && !there) {
                    takeCover(c, p, near);      // that way's no good (it's still on them and they're getting nowhere): somewhere else
                }
                return true;
            }
            case HIDING -> {
                return hiding(c, p, near);
            }
        }
        return false;
    }

    /** How much {@code c} is facing {@code at} (1 straight at it, -1 away): people catch movement out of the corner of an eye. */
    private static double facing(LivingEntity c, LivingEntity at) {
        Vec3d to = at.getPos().subtract(c.getPos()).multiply(1.0, 0.0, 1.0);
        if (to.lengthSquared() < 0.01) {
            return 1.0;
        }
        double yaw = Math.toRadians(c.getHeadYaw());
        return to.normalize().dotProduct(new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw)));
    }

    private void cry(Contestant c, Hunter near, boolean shot) {
        if (ticks - criedAt.getOrDefault(c.getUuid(), -400) < 120) {
            return;
        }
        criedAt.put(c.getUuid(), ticks);
        String line = switch (c.nature) {
            case TIMID -> Lines.pick(random, "No, no, no...", "It's here... oh god, it's here.", "Help!");
            case BRAVE -> Lines.pick(random, "It's here! Get inside!", "Horse mask! Off the walkway!", "Down! Get down!");
            case KIND -> Lines.pick(random, "It's here! Everyone, get inside!", "Run! Get out of sight!", "Watch out, it's here!");
            case CLEVER -> Lines.pick(random, "Horse mask. Out of its line of sight, now.", "It's here. Get behind a wall.", "Into a flat. Quietly.");
            case SELFISH -> Lines.pick(random, "Get out of my way!", "It's here!", "Move!");
            case RECKLESS -> Lines.pick(random, "It's here! Run!", "Go, go, go!", "Run!");
        };
        c.say(shot ? Lines.pick(random, "It's shooting! ", "Gun! ", "") + line : line);
    }

    /** Off to the nearest place it can't see: quietly if they've kept their head, flat out if they haven't. */
    private void takeCover(Contestant c, Panic p, Hunter near) {
        boolean calm = c.feelings.fear < 0.55F && (c.nature == Contestant.Nature.CLEVER || c.nature == Contestant.Nature.BRAVE
                || c.nature == Contestant.Nature.KIND && random.nextBoolean());
        p.pace = calm ? 1.1 : 1.4;
        p.cover = cover(c, near);
        p.state = Fright.TAKING_COVER;
        p.since = ticks;
        p.outOfSight = 0;
        if (p.cover == null) {
            // nowhere out of its sight within reach: then away from it
            p.cover = Vec3d.ofBottomCenter(farthestFlat(c, near).center);
            p.pace = 1.4;
        }
        c.go(p.cover, p.pace);
    }

    /**
     * The nearest place to stand that {@code hunter} can't see from where it is, that {@code c} can get to without
     * running at it: a doorway, a flat, the stairwell, the far side of a wall.
     */
    @Nullable
    private Vec3d cover(Contestant c, Hunter hunter) {
        // properly out of sight first: round a corner from where they stand, and still hidden when it's taken a few
        // steps their way; a doorway it'll see into as it passes only if there's nothing better
        Vec3d found = cover(c, hunter, true);
        return found != null ? found : cover(c, hunter, false);
    }

    @Nullable
    private Vec3d cover(Contestant c, Hunter hunter, boolean properly) {
        Vec3d eye = hunter.getEyePos();
        Vec3d mine = c.getEyePos();
        Vec3d coming = mine.subtract(eye).multiply(1.0, 0.0, 1.0);
        double gap = coming.length();
        Vec3d closer = gap < 0.01 ? eye : eye.add(coming.multiply(Math.min(3.0, gap / 2.0) / gap));
        Vec3d here = c.getPos();
        Vec3d toIt = hunter.getPos().subtract(here).multiply(1.0, 0.0, 1.0);
        toIt = toIt.lengthSquared() < 0.01 ? Vec3d.ZERO : toIt.normalize();
        BlockPos at = c.getBlockPos();
        List<BlockPos> spots = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    if (dx * dx + dz * dz > 81) {
                        continue;
                    }
                    BlockPos feet = at.add(dx, dy, dz);
                    Vec3d spot = Vec3d.ofBottomCenter(feet);
                    if (!bounds.contains(spot) || mined(spot) || !standable(feet)) {
                        continue;
                    }
                    if (seenFrom(eye, spot.add(0.0, 1.5, 0.0), hunter) || seenFrom(eye, spot.add(0.0, 0.6, 0.0), hunter)) {
                        continue;
                    }
                    if (properly && (seenFrom(closer, spot.add(0.0, 1.5, 0.0), hunter) || seenFrom(mine, spot.add(0.0, 1.5, 0.0), hunter))) {
                        continue;
                    }
                    Vec3d way = spot.subtract(here).multiply(1.0, 0.0, 1.0);
                    double d = way.length();
                    double toward = d < 0.01 ? 0.0 : way.multiply(1.0 / d).dotProduct(toIt);
                    double score = d + (toward > 0.2 ? 14.0 * toward : 0.0) - 0.2 * spot.distanceTo(hunter.getPos()) + Math.abs(dy) * 2.0;
                    int i = 0;
                    while (i < scores.size() && scores.get(i) <= score) {
                        i++;
                    }
                    if (i < 8) {
                        spots.add(i, feet);
                        scores.add(i, score);
                        if (spots.size() > 8) {
                            spots.remove(8);
                            scores.remove(8);
                        }
                    }
                }
            }
        }
        // the way there counts, not just how far it is as the crow flies (a wall between you is no use if the door's
        // the other end of the gallery)
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            net.minecraft.entity.ai.pathing.Path path = c.getNavigation().findPathTo(spots.get(i), 0);
            if (path == null || !path.reachesTarget()) {
                continue;
            }
            double cost = path.getLength() + scores.get(i) - Math.sqrt(c.getBlockPos().getSquaredDistance(spots.get(i)));
            if (cost < bestCost) {
                bestCost = cost;
                best = spots.get(i);
            }
        }
        return best == null ? null : Vec3d.ofBottomCenter(best);
    }

    private boolean seenFrom(Vec3d eye, Vec3d point, Hunter hunter) {
        return world.raycast(new net.minecraft.world.RaycastContext(eye, point, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, hunter)).getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    private boolean standable(BlockPos feet) {
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty();
    }

    /** The courtyard: a step on it is a mine. */
    private boolean mined(Vec3d at) {
        return at.x > cx + 9.0 && at.z > cz - 35.0 && at.y < origin.getY() + 1.0;
    }

    /** Out of its sight: keep still a while, warn the others where it is, then think again. */
    private void hide(Contestant c, Panic p, Hunter near) {
        p.state = Fright.HIDING;
        p.until = ticks + 20 * (6 + random.nextInt(8)) + (int) (c.feelings.fear * 20 * 10);
        c.hold();
        c.lookAt(near.getEyePos());
        String where = "floor " + (floorOf(near) + 1) + ", " + sideOf(near);
        c.remember("You ran from the thing in the horse mask and hid. It was on " + where + ".");
        boolean warns = c.nature != Contestant.Nature.SELFISH && (c.nature != Contestant.Nature.TIMID || random.nextBoolean());
        if (warns && ticks - criedAt.getOrDefault(c.getUuid(), -400) > 60) {
            criedAt.put(c.getUuid(), ticks);
            c.say(Lines.pick(random, "It's on " + where + "!", "Horse mask, " + where + ". Stay away from there.",
                    "It's on " + where + ". Keep your heads down."));
        }
        note(c.name() + " is hiding from the thing in the horse mask. It was last seen on " + where + ".", false);
    }

    /** Hidden: holding still, watching the door. Seen again, or the wait's over. */
    private boolean hiding(Contestant c, Panic p, Hunter near) {
        if (ticks >= p.until) {
            panic.remove(c.getUuid());
            c.stopAll();
            return false;       // they think again (the AI picks what to do next)
        }
        if (near.distanceTo(c) < 14.0) {
            c.lookAt(near.getEyePos());
        }
        return true;
    }

    private void announceFind(Contestant finder) {
        finder.speak("You found the safe zone: floor " + (goal.floor + 1) + ", the room with the spade and two buttons. Shout it to everyone.",
                "It's here! Floor " + (goal.floor + 1) + "! The spade room! I need someone on the other button!");
        for (Contestant other : strangers()) {
            other.remember(finder.name() + " found the safe zone on floor " + (goal.floor + 1) + ".");
        }
    }

    private boolean atButton(Contestant contestant) {
        return contestant.squaredDistanceTo(Vec3d.ofCenter(buttonA)) < 3.0 || contestant.squaredDistanceTo(Vec3d.ofCenter(buttonB)) < 3.0;
    }

    private Contestant strangerAt(BlockPos button) {
        for (Contestant contestant : strangers()) {
            if (contestant.squaredDistanceTo(Vec3d.ofCenter(button)) < 3.0) {
                return contestant;
            }
        }
        return null;
    }

    private BlockPos freeButton(Contestant contestant) {
        Contestant atA = strangerAt(buttonA);
        Contestant atB = strangerAt(buttonB);
        BlockPos a = standBy(buttonA);
        BlockPos b = standBy(buttonB);
        if (atA == contestant) {
            return a;
        }
        if (atB == contestant) {
            return b;
        }
        if (atA == null) {
            return a;
        }
        return atB == null ? b : goal.center;
    }

    private BlockPos standBy(BlockPos button) {
        return new BlockPos(button.getX(), button.getY() - 1, button.getZ()).offset(facingOf(button));
    }

    private Direction facingOf(BlockPos button) {
        BlockState state = world.getBlockState(button);
        return state.contains(net.minecraft.block.HorizontalFacingBlock.FACING) ? state.get(net.minecraft.block.HorizontalFacingBlock.FACING) : Direction.UP;
    }

    private Flat nextFlat(Contestant contestant) {
        Set<Integer> done = searched.computeIfAbsent(contestant.getUuid(), k -> new HashSet<>());
        int floor = (int) ((contestant.getY() - origin.getY()) / 4);
        Flat best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < flats.size(); i++) {
            if (done.contains(i)) {
                continue;
            }
            Flat flat = flats.get(i);
            Integer wanted = floorWanted.get(contestant.getUuid());
            double score = contestant.squaredDistanceTo(Vec3d.ofCenter(flat.center)) + Math.abs(flat.floor - (wanted != null ? wanted : floor))
                    * (wanted != null ? 900.0 : 120.0) + random.nextInt(200);
            int[] heard = heardShooting.get(contestant.getUuid());
            if (heard != null && ticks - heard[1] < 20 * 60 && Math.abs(flat.floor - heard[0]) <= 1) {
                score += 4000.0;        // they heard it shooting there not a minute ago
            }
            if (score < bestScore) {
                best = flat;
                bestScore = score;
            }
        }
        return best != null ? best : flats.get(random.nextInt(flats.size()));
    }

    private Flat farthestFlat(Contestant contestant, Hunter hunter) {
        int floor = (int) ((contestant.getY() - origin.getY()) / 4);
        Flat best = flats.get(0);
        double bestDistance = -1.0;
        for (Flat flat : flats) {
            if (Math.abs(flat.floor - floor) > 1) {
                continue;
            }
            double distance = hunter.squaredDistanceTo(Vec3d.ofCenter(flat.center)) - contestant.squaredDistanceTo(Vec3d.ofCenter(flat.center)) * 0.5;
            if (distance > bestDistance) {
                best = flat;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (phase != Phase.RUNNING || !isPlaying(player.getUuid())) {
            return ActionResult.PASS;
        }
        if (pos.equals(buttonA) || pos.equals(buttonB)) {
            boolean a = pos.equals(buttonA);
            (a ? pressedA : pressedB).put(player.getUuid(), ticks);
            if (!goalKnown) {
                goalKnown = true;
            }
            // A stranger waiting at the other button presses with you
            Contestant partner = strangerAt(a ? buttonB : buttonA);
            if (partner != null) {
                (a ? pressedB : pressedA).put(partner.getUuid(), ticks);
                partner.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                partner.say(Lines.pick(random, "Now!", "Together!", "Pressing!"));
            }
            check();
        }
        return ActionResult.PASS;
    }

    private void pressTogether() {
        Contestant a = strangerAt(buttonA);
        Contestant b = strangerAt(buttonB);
        if (a == null || b == null) {
            return;
        }
        a.say("Three!");
        pressedA.put(a.getUuid(), ticks);
        pressedB.put(b.getUuid(), ticks);
        a.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        b.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        check();
    }

    /** Both buttons pressed within a quarter of a second, by two different people: the bomb is off. */
    private void check() {
        for (Map.Entry<UUID, Integer> one : pressedA.entrySet()) {
            for (Map.Entry<UUID, Integer> two : pressedB.entrySet()) {
                if (!one.getKey().equals(two.getKey()) && Math.abs(one.getValue() - two.getValue()) <= 5 && ticks - one.getValue() <= 5) {
                    cleared();
                    return;
                }
            }
        }
    }

    private void cleared() {
        announce("The safe zone has been reached. The bomb is disarmed. GAME CLEAR.");
        for (UUID id : playing()) {
            clear(id);
        }
        for (Hunter hunter : hunterList()) {
            Laser.strike(hunter, null);     // its collar
        }
        finish();
    }

    @Override
    protected void timeUp() {
        announce("Time's up.");
        for (int i = 0; i < 12; i++) {
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, cx + random.nextInt(45), base(random.nextInt(FLOORS)), cz - random.nextInt(45), 1, 0.0, 0.0, 0.0, 0.0);
        }
        world.playSound(null, cx + 22, base(4), cz - 22, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6.0F, 0.5F);
        super.timeUp();
    }

    @Override
    protected void ended() {
        for (Hunter hunter : hunterList()) {
            hunter.discard();
        }
        hunters.clear();
    }

    @Override
    protected void heard(ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase();
        if (lower.matches(".*\\b(found|here|safe|button|spade)\\b.*") && player.squaredDistanceTo(Vec3d.ofCenter(goal.center)) < 7.0 * 7.0) {
            goalKnown = true;
            for (Contestant contestant : strangers()) {
                contestant.remember(player.getEntityName() + " shouted that they found the safe zone on floor " + (goal.floor + 1) + ".");
            }
        }
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        int floor = (int) Math.floor((player.getY() - origin.getY()) / 4.0) + 1;
        hud.line("§7FLOOR " + Math.max(1, Math.min(FLOORS, floor)));
        if (goalKnown) {
            hud.line("§eSafe zone: floor " + (goal.floor + 1));
        }
    }

    // --- what a stranger can say they'll do, and then does ---------------------------------------------------

    @Override
    protected boolean followable() {
        return true;
    }

    /** The floor each stranger has decided to search, and who's decided to hide (until when). */
    private final Map<UUID, Integer> floorWanted = new HashMap<>();
    private final Map<UUID, Integer> hideUntil = new HashMap<>();

    @Override
    public Map<String, String> options(Contestant who) {
        if (!isPlaying(who.getUuid())) {
            return Map.of();
        }
        Map<String, String> options = new java.util.LinkedHashMap<>();
        if (!goalKnown) {
            options.put("SEARCH_N", "search the flats on floor N (1 to " + FLOORS + ") for the safe room: for example [SEARCH 4]");
        }
        options.put("HIDE", "slip into the nearest flat and keep still for a while, out of its line of sight");
        return options;
    }

    @Override
    public void chose(Contestant who, String option) {
        UUID id = who.getUuid();
        if (option.startsWith("SEARCH_")) {
            try {
                int f = Integer.parseInt(option.substring(7)) - 1;
                if (f >= 0 && f < FLOORS) {
                    floorWanted.put(id, f);
                    searching.remove(id);
                    hideUntil.remove(id);
                }
            } catch (NumberFormatException ignored) {
            }
        } else if (option.equals("HIDE")) {
            Flat near = null;
            for (Flat flat : flats) {
                if (near == null || who.squaredDistanceTo(Vec3d.ofCenter(flat.center)) < who.squaredDistanceTo(Vec3d.ofCenter(near.center))) {
                    near = flat;
                }
            }
            if (near != null) {
                who.go(near.center, 1.1);
                hideUntil.put(id, ticks + 20 * 20);
            }
        }
    }

    @Override
    public String situation(Contestant who) {
        int floor = (int) ((who.getY() - origin.getY()) / 4) + 1;
        StringBuilder text = new StringBuilder("You're playing TAG, the five of spades, in a nine-storey apartment block. Somewhere in it is a safe "
                + "room with a spade on the wall; two people must press its two buttons at the same moment before the bomb goes off. Something "
                + "in a horse's head with a submachine gun is hunting you; if it touches you, you die. The mask means it only sees straight "
                + "ahead. You're on floor " + floor + ". " + timeLeft / 20 / 60 + " minutes left.");
        if (goalKnown) {
            text.append(" The safe room is on floor ").append(goal.floor + 1).append('.');
        }
        Set<Integer> done = searched.getOrDefault(who.getUuid(), Set.of());
        Set<Integer> floorsDone = new java.util.TreeSet<>();
        for (int i : done) {
            floorsDone.add(flats.get(i).floor + 1);
        }
        text.append(" You've searched ").append(done.size()).append(" flats").append(floorsDone.isEmpty() ? "" : " (on floors " + floorsDone + ")").append('.');
        for (Hunter hunter : hunterList()) {
            if (hunter.squaredDistanceTo(who) < 30 * 30) {
                int hf = (int) ((hunter.getY() - origin.getY()) / 4) + 1;
                text.append(" The thing in the horse mask is ").append((int) Math.sqrt(hunter.squaredDistanceTo(who))).append(" m from you, on floor ")
                        .append(hf).append(hunter.canSee(who) ? ", and it's looking right at you!" : ".");
            }
        }
        Panic fright = panic.get(who.getUuid());
        if (fright != null && fright.state == Fright.HIDING) {
            text.append(" You've just run from it and you're hiding, keeping still.");
        }
        int[] heard = heardShooting.get(who.getUuid());
        if (heard != null && ticks - heard[1] < 20 * 60) {
            text.append(" You heard it shooting on floor ").append(heard[0] + 1).append(' ').append((ticks - heard[1]) / 20).append(" seconds ago.");
        }
        text.append(" It shoots on sight; out of its line of sight, behind a wall or inside a flat, you're safe from the gun.");
        Integer wanted = floorWanted.get(who.getUuid());
        if (wanted != null && !goalKnown) {
            text.append(" You decided to search floor ").append(wanted + 1).append('.');
        }
        return text.toString();
    }

    @Override
    protected void died(UUID id) {
        searching.remove(id);
        panic.remove(id);
        for (Contestant contestant : strangers()) {
            if (contestant.quiet() && random.nextFloat() < 0.3F) {
                contestant.speak(nameOf(id) + " was just killed by the thing in the horse mask.",
                        Lines.pick(random, "It got " + nameOf(id) + ". It got them.", "Keep moving. Don't stop.", "That could have been me."));
                break;
            }
        }
    }
}
