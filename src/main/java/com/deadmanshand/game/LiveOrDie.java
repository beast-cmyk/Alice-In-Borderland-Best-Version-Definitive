package com.deadmanshand.game;

import com.deadmanshand.Builder;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Three of Clubs: LIVE OR DIE. A plain block of a building, two rooms wide and four deep. Every room has two doors, one
 * signed LIVE and one signed DIE, and the signs mean nothing: one door leads on through the building, and behind the
 * other there's nothing but death (a cupboard of a room with no way out, or a room you've already come through, now
 * burning). Each room's clock is shorter than the last, down to ten seconds in the eighth, and once you've moved on,
 * the room behind you burns. The way to live is to hold the building's shape in your head: a door in a wall with
 * windows leads outside, and a door back the way you came leads into the fire.
 */
final class LiveOrDie extends Game {
    private static final int ROOMS = 8;
    private static final int[] ROOM_SECONDS = {120, 110, 100, 90, 80, 70, 60, 10};
    /** Inside size of a room, and from one room's start to the next. */
    private static final int CELL = 6;
    private static final int PITCH = 7;
    /** Once you've moved on, how long the room behind you has before it burns (ticks). */
    private static final int BEHIND = 20 * 12;

    private enum Dir {
        NORTH(0, -1, Direction.NORTH), EAST(1, 0, Direction.EAST), SOUTH(0, 1, Direction.SOUTH), WEST(-1, 0, Direction.WEST);

        final int dx;
        final int dz;
        final Direction facing;

        Dir(int dx, int dz, Direction facing) {
            this.dx = dx;
            this.dz = dz;
            this.facing = facing;
        }

        Dir opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        Dir left() {
            return values()[(ordinal() + 3) % 4];
        }

        Dir right() {
            return values()[(ordinal() + 1) % 4];
        }

        String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final class Room {
        final int index;
        final int col;
        final int row;
        /** The way you came in (the wall you entered by is {@code in.opposite()}), the door on, and the door to death. */
        Dir in;
        Dir onward;
        Dir wrong;
        /** Which of the two doors is signed LIVE: true, the one that leads on. */
        boolean onwardSignedLive;
        /** The walls with windows in them (the outside of the building, where it hasn't been bricked up). */
        final Set<Dir> windows = new HashSet<>();
        int startedAt = -1;
        int endsAt = -1;
        int expiredAt = -1;
        /** Door 0 is the one on, door 1 the one to death. */
        final boolean[] opened = new boolean[2];
        final boolean[] provenSafe = new boolean[2];
        final boolean[] provenDeadly = new boolean[2];

        Room(int index, int col, int row) {
            this.index = index;
            this.col = col;
            this.row = row;
        }

        int xMin() {
            return x0 + 3 + col * PITCH;
        }

        int xMax() {
            return xMin() + CELL - 1;
        }

        int zMax() {
            return zS - 3 - row * PITCH;
        }

        int zMin() {
            return zMax() - CELL + 1;
        }

        Dir door(int d) {
            return d == 0 ? onward : wrong;
        }

        String sign(int d) {
            return (d == 0) == onwardSignedLive ? "LIVE" : "DIE";
        }

        boolean contains(Vec3d pos) {
            return pos.x >= xMin() && pos.x < xMax() + 1 && pos.z >= zMin() && pos.z < zMax() + 1
                    && pos.y >= origin.getY() - 0.5 && pos.y < origin.getY() + 4;
        }

        Box box() {
            return new Box(xMin(), origin.getY(), zMin(), xMax() + 1, origin.getY() + 4, zMax() + 1);
        }

        int secondsLeft() {
            if (startedAt < 0) {
                return ROOM_SECONDS[index];
            }
            return Math.max(0, (endsAt - ticks + 19) / 20);
        }
    }

    private final List<Room> rooms = new ArrayList<>();
    /** The building: its west edge, south edge and north edge; the column the way out is in. */
    private int x0;
    private int zS;
    private int zN;
    private int exitCol;
    private int ticks;
    /** Strangers: when they'll make up their minds, which door they mean to take (0 on, 1 death), and which room they're in. */
    private final Map<UUID, Integer> decideAt = new HashMap<>();
    private final Map<UUID, Integer> belief = new HashMap<>();
    private final Map<UUID, Integer> roomOf = new HashMap<>();
    /** Strangers who've committed to a door in their room (and said so). */
    private final Set<UUID> committed = new HashSet<>();
    /** When each stranger, still making up their mind, next moves to look at something else. */
    private final Map<UUID, Integer> nextLook = new HashMap<>();
    /** When each stranger opened the door they'd chosen (to take in what's behind it). */
    private final Map<UUID, Integer> openedAt = new HashMap<>();
    /** The ways each person has gone, room to room, so far. */
    private final Map<UUID, List<String>> route = new HashMap<>();

    LiveOrDie(Kind kind, ServerWorld world, BlockPos origin) {
        super(kind, world, origin);
    }

    // --- the building -------------------------------------------------------------------------------------------

    /** The grid cell (col, row) in direction {@code d} from a room: rows 0..3 are rooms, row 4 is the way out. */
    private boolean isRoomCell(int col, int row) {
        return col >= 0 && col <= 1 && row >= 0 && row <= 3;
    }

    private boolean isExitCell(int col, int row) {
        return col == exitCol && row == 4;
    }

    private Room roomAt(int col, int row) {
        for (Room room : rooms) {
            if (room.col == col && room.row == row) {
                return room;
            }
        }
        return null;
    }

    /** Where the door in {@code room}'s {@code d} wall is (its lower half). */
    private BlockPos doorPos(Room room, Dir d) {
        int y = origin.getY();
        return switch (d) {
            case NORTH -> new BlockPos(room.xMin() + 2, y, room.zMin() - 1);
            case SOUTH -> new BlockPos(room.xMin() + 2, y, room.zMax() + 1);
            case EAST -> new BlockPos(room.xMax() + 1, y, room.zMax() - 2);
            case WEST -> new BlockPos(room.xMin() - 1, y, room.zMax() - 2);
        };
    }

    /** Where you'd stand to open the door in {@code room}'s {@code d} wall. */
    private Vec3d before(Room room, Dir d) {
        return Vec3d.ofBottomCenter(doorPos(room, d)).add(-d.dx * 1.4, 0, -d.dz * 1.4);
    }

    /** Where you end up through the door in {@code room}'s {@code d} wall: two steps into the next room, or into the cupboard. */
    private Vec3d beyond(Room room, Dir d) {
        int col = room.col + d.dx;
        int row = room.row - d.dz;
        BlockPos door = doorPos(room, d);
        if (isRoomCell(col, row) || isExitCell(col, row)) {
            return Vec3d.ofBottomCenter(door).add(d.dx * 2.5, 0, d.dz * 2.5);
        }
        return Vec3d.ofBottomCenter(door).add(d.dx, 0, d.dz);
    }

    /** The cupboard behind a wrong door that leads nowhere: one step past the door, inside the outer wall. */
    private Box cupboard(Room room) {
        BlockPos at = doorPos(room, room.wrong).add(room.wrong.dx, 0, room.wrong.dz);
        return new Box(at.getX(), at.getY(), at.getZ(), at.getX() + 1, at.getY() + 2, at.getZ() + 1);
    }

    @Override
    protected void plan(Builder b) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        x0 = ox - 9;
        zS = oz - 6;
        zN = zS - 3 - 4 * PITCH - (CELL - 1) - 3;      // the north face of the building
        boolean mirror = random.nextBoolean();
        int c0 = mirror ? 1 : 0;
        int c1 = 1 - c0;
        // the way through: a snake, row by row, starting in the corner by the way in
        int[][] path = {{c0, 0}, {c1, 0}, {c1, 1}, {c0, 1}, {c0, 2}, {c1, 2}, {c1, 3}, {c0, 3}};
        exitCol = c0;
        for (int i = 0; i < ROOMS; i++) {
            rooms.add(new Room(i, path[i][0], path[i][1]));
        }
        for (int i = 0; i < ROOMS; i++) {
            Room room = rooms.get(i);
            room.in = i == 0 ? Dir.NORTH : step(rooms.get(i - 1), room);
            room.onward = i == ROOMS - 1 ? Dir.NORTH : step(room, rooms.get(i + 1));
            // the door to death: in a wall that's the outside of the building, with nothing behind it but a cupboard
            List<Dir> outside = new ArrayList<>();
            for (Dir d : Dir.values()) {
                if (d != room.in.opposite() && d != room.onward && outsideWall(room, d, c0)) {
                    outside.add(d);
                }
            }
            room.wrong = outside.get(random.nextInt(outside.size()));
            room.onwardSignedLive = random.nextBoolean();
            // windows in the outside walls: every one in the first rooms; deeper in, some are bricked up, and you have to
            // keep the building's shape in your head
            for (Dir d : Dir.values()) {
                if (outsideWall(room, d, c0) && (i < 4 || random.nextFloat() < 0.4F)) {
                    room.windows.add(d);
                }
            }
        }

        int x1 = x0 + 18;
        bounds = new Box(x0 - 1, oy - 2, zN - 14, x1 + 2, oy + 9, oz + 1);
        Room first = rooms.get(0);
        int lobbyX0 = first.xMin() - 1;
        int lobbyX1 = first.xMax() + 1;
        entry = new Box(lobbyX0, oy, oz - 5, lobbyX1 + 1, oy + 3, oz);
        entryPos = new BlockPos((lobbyX0 + lobbyX1) / 2, oy, oz - 3);
        approach = new BlockPos(ox, oy, oz + 16);

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState outer = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState inner = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        // clear the whole lot (an older building of this game may still be standing on it), ground everywhere
        com.deadmanshand.city.City.Lot lot = kind.lot();
        if (lot != null) {
            b.fill(lot.x0(), oy, lot.z0(), lot.x1(), oy + 10, lot.z1() - 1, air);
            b.fill(lot.x0(), oy - 1, lot.z0(), lot.x1(), oy - 1, lot.z1() - 1, Blocks.GRAY_CONCRETE.getDefaultState());
        }
        b.fill(x0, oy, zN - 14, x1, oy + 9, oz, air);
        b.fill(x0, oy - 2, zN - 14, x1, oy - 2, oz, Blocks.STONE.getDefaultState());
        b.fill(x0, oy - 1, zN - 14, x1, oy - 1, oz, Blocks.GRAY_CONCRETE.getDefaultState());
        // the building: a plain grey block, two storeys' worth of wall, a flat roof with a parapet
        b.fill(x0, oy - 1, zN, x1, oy + 6, zS, outer);
        b.fill(x0 + 1, oy, zN + 1, x1 - 1, oy + 4, zS - 1, inner);
        b.fill(x0 + 1, oy + 6, zN + 1, x1 - 1, oy + 6, zS - 1, air);
        for (int y = oy + 2; y <= oy + 5; y += 3) {
            // dead windows along the outside, upstairs: the building looks lived in, once
            for (int x = x0 + 2; x <= x1 - 2; x += 3) {
                b.set(x, y + (y == oy + 2 ? 2 : 0), zS, Blocks.BLACK_STAINED_GLASS.getDefaultState());
                b.set(x, y + (y == oy + 2 ? 2 : 0), zN, Blocks.BLACK_STAINED_GLASS.getDefaultState());
            }
        }
        // the rooms
        for (Room room : rooms) {
            carve(b, room.xMin(), room.zMin(), room.xMax(), room.zMax(), false);
        }
        int exX = x0 + 3 + exitCol * PITCH;
        int exZ = zS - 3 - 4 * PITCH;
        carve(b, exX, exZ - CELL + 1, exX + CELL - 1, exZ, true);
        // windows: in every wall that's the outside of the building
        for (Room room : rooms) {
            for (Dir d : room.windows) {
                for (int k : new int[]{0, CELL - 1}) {
                    for (int y = oy + 1; y <= oy + 2; y++) {
                        for (int depth = 1; depth <= 3; depth++) {
                            BlockPos at = wallPoint(room, d, k, y, depth);
                            b.set(at, depth == 2 ? air : Blocks.GLASS_PANE.getDefaultState());
                        }
                    }
                }
            }
        }
        // doors, the signs over them, and the room numbers
        for (Room room : rooms) {
            for (int d = 0; d < 2; d++) {
                Dir dir = room.door(d);
                BlockPos door = doorPos(room, dir);
                b.door(door, Blocks.IRON_DOOR, dir.facing);
                b.sign(door.up(2).add(-dir.dx, 0, -dir.dz), dir.opposite().facing, "", room.sign(d), "", "");
                if (d == 1) {
                    BlockPos cup = door.add(dir.dx, 0, dir.dz);
                    b.fill(cup.getX(), cup.getY(), cup.getZ(), cup.getX(), cup.getY() + 1, cup.getZ(), air);
                    b.set(cup.down(), Blocks.BLACK_CONCRETE.getDefaultState());
                }
            }
            BlockPos entered = doorPos(room, room.in.opposite());
            b.sign(entered.up(2).add(room.in.dx, 0, room.in.dz), room.in.facing, "", "ROOM " + (room.index + 1), "", "");
        }
        // the way in: the lobby where you register, and a barred passage into room 1
        b.fill(lobbyX0, oy, oz - 5, lobbyX1, oy + 3, oz - 1, air);
        b.fill(lobbyX0 - 1, oy - 1, oz - 6, lobbyX1 + 1, oy + 4, oz - 6, outer);
        b.fill(lobbyX0 - 1, oy - 1, oz - 5, lobbyX0 - 1, oy + 4, oz - 1, outer);
        b.fill(lobbyX1 + 1, oy - 1, oz - 5, lobbyX1 + 1, oy + 4, oz - 1, outer);
        b.fill(lobbyX0, oy + 4, oz - 5, lobbyX1, oy + 4, oz - 1, outer);
        b.fill(lobbyX0, oy - 1, oz - 5, lobbyX1, oy - 1, oz - 1, Blocks.POLISHED_ANDESITE.getDefaultState());
        b.set((lobbyX0 + lobbyX1) / 2, oy + 4, oz - 3, Blocks.SEA_LANTERN.getDefaultState());
        b.fill(lobbyX0 + 1, oy + 1, oz - 5, lobbyX1 - 1, oy + 1, oz - 5, Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        b.sign(new BlockPos((lobbyX0 + lobbyX1) / 2, oy + 2, oz - 5), Direction.SOUTH, "ONE PHONE", "PER PERSON", "", "");
        BlockPos into = doorPos(first, Dir.SOUTH);
        b.fill(into.getX(), oy, zS - 2, into.getX(), oy + 1, zS, Blocks.IRON_BARS.getDefaultState());
        int mid = (lobbyX0 + lobbyX1) / 2;
        b.fill(lobbyX0 - 1, oy - 1, oz, lobbyX1 + 1, oy + 4, oz, outer);
        b.fill(mid - 1, oy, oz, mid + 1, oy + 2, oz, air);
        b.set(lobbyX0 - 1, oy + 2, oz + 1, Blocks.SEA_LANTERN.getDefaultState());
        b.set(lobbyX1 + 1, oy + 2, oz + 1, Blocks.SEA_LANTERN.getDefaultState());
        b.sign(new BlockPos(mid, oy + 3, oz + 1), Direction.SOUTH, "GAME VENUE", "", "step inside", "to register");
        // the way out of the last room: the exit hall, a doorway through the north wall, and the alley behind
        int doorX = exX + 2;
        b.fill(doorX, oy, zN, doorX, oy + 1, exZ - CELL, air);
        b.set(doorX, oy + 2, exZ - CELL, Blocks.GLOWSTONE.getDefaultState());
        b.sign(new BlockPos(doorX, oy + 2, exZ - CELL + 1), Direction.SOUTH, "", "EXIT", "", "");
        b.fill(x0, oy - 1, zN - 13, x1, oy - 1, zN - 1, Blocks.GRAY_CONCRETE.getDefaultState());
        b.fill(x0, oy, zN - 13, x0, oy + 3, zN - 1, Blocks.STONE_BRICKS.getDefaultState());
        b.fill(x1, oy, zN - 13, x1, oy + 3, zN - 1, Blocks.STONE_BRICKS.getDefaultState());
        b.set(x0 + 1, oy, zN - 6, Blocks.LANTERN.getDefaultState());
        b.set(x1 - 1, oy, zN - 6, Blocks.LANTERN.getDefaultState());
        b.set(x0 + 2, oy, zN - 10, Blocks.BARREL.getDefaultState());
        b.set(x1 - 3, oy, zN - 9, Blocks.CAULDRON.getDefaultState());
    }

    /** Whether {@code room}'s {@code d} wall is the outside of the building (not another room, the lobby, or the back). */
    private boolean outsideWall(Room room, Dir d, int c0) {
        int col = room.col + d.dx;
        int row = room.row - d.dz;
        return (col < 0 || col > 1 || row < 0) && !(row == -1 && col == c0);
    }

    /** The way from {@code a} into the room next to it, {@code b}. */
    private static Dir step(Room a, Room b) {
        for (Dir d : Dir.values()) {
            if (a.col + d.dx == b.col && a.row - d.dz == b.row) {
                return d;
            }
        }
        throw new IllegalStateException("rooms aren't next to each other");
    }

    /** A point in {@code room}'s {@code d} wall: {@code k} along it, {@code depth} into it (1: the inside face). */
    private BlockPos wallPoint(Room room, Dir d, int k, int y, int depth) {
        return switch (d) {
            case NORTH -> new BlockPos(room.xMin() + k, y, room.zMin() - depth);
            case SOUTH -> new BlockPos(room.xMin() + k, y, room.zMax() + depth);
            case EAST -> new BlockPos(room.xMax() + depth, y, room.zMax() - k);
            case WEST -> new BlockPos(room.xMin() - depth, y, room.zMax() - k);
        };
    }

    /** One room: hollow, a scuffed floor, a single light. */
    private void carve(Builder b, int xa, int za, int xb, int zb, boolean exit) {
        int oy = origin.getY();
        b.fill(xa, oy, za, xb, oy + 3, zb, Blocks.AIR.getDefaultState());
        b.fill(xa, oy - 1, za, xb, oy - 1, zb, Blocks.SMOOTH_STONE.getDefaultState());
        for (int i = 0; i < 5; i++) {
            b.set(xa + random.nextInt(xb - xa + 1), oy - 1, za + random.nextInt(zb - za + 1), Blocks.CRACKED_STONE_BRICKS.getDefaultState());
        }
        b.set((xa + xb) / 2, oy + 4, (za + zb) / 2, exit ? Blocks.GLOWSTONE.getDefaultState() : Blocks.SEA_LANTERN.getDefaultState());
    }

    @Override
    protected String[] rules() {
        return new String[]{
                "Leave the building alive.",
                "Every room has two doors. One says LIVE. One says DIE.",
                "One door takes you on. Through the other, you die.",
                "Every room has a time limit, and every room's is shorter than the last.",
                "When a room's time runs out, it burns.",
                "Touch a door to open it."};
    }

    @Override
    protected int timeLimit() {
        return 20 * 60 * 12;
    }

    @Override
    protected void arrange() {
        Room first = rooms.get(0);
        int i = 0;
        for (LivingEntity entity : living()) {
            double x = first.xMin() + 0.5 + (i % 5);
            double z = first.zMax() + 0.5 - (i / 5) * 1.5;
            entity.teleport(x, origin.getY(), z);
            entity.setYaw(180.0F);
            if (entity instanceof Contestant contestant) {
                contestant.hold();
            }
            route.put(entity.getUuid(), new ArrayList<>());
            i++;
        }
    }

    @Override
    protected void begin() {
        Room first = rooms.get(0);
        first.startedAt = ticks;
        first.endsAt = ticks + ROOM_SECONDS[0] * 20;
        for (Contestant contestant : strangers()) {
            contestant.stopAll();
            roomOf.put(contestant.getUuid(), 0);
            startThinking(contestant, first);
        }
        for (UUID id : playing()) {
            roomOf.putIfAbsent(id, 0);
        }
        announce("Room 1. " + ROOM_SECONDS[0] + " seconds.");
    }

    // --- every tick -----------------------------------------------------------------------------------------------

    @Override
    protected void running() {
        ticks++;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null) {
                continue;
            }
            Vec3d pos = entity.getPos();
            if (pos.z < zN - 1.5) {
                clear(id);
                if (entity instanceof Contestant contestant) {
                    contestant.stopAll();
                    contestant.speak("You just made it out of the building alive, into the alley behind it.",
                            Lines.pick(random, "We're out. We're actually out.", "Air. Real air.", "I can't believe that worked."));
                }
                continue;
            }
            for (Room room : rooms) {
                if (room.contains(pos)) {
                    enter(id, entity, room);
                }
                if (cupboard(room).contains(pos)) {
                    wrongDoor(id, entity, room);
                }
            }
        }
        for (Room room : rooms) {
            tickFire(room);
        }
        strangersThink();
    }

    private void wrongDoor(UUID id, LivingEntity entity, Room room) {
        room.provenDeadly[1] = true;
        note(nameOf(id) + " went through the door signed " + room.sign(1) + " in room " + (room.index + 1)
                + " and found a tiny room with no way out. The laser killed them.");
        world.playSound(null, entity.getBlockPos(), SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.0F, 0.6F);
        fail(id, "There was no way out behind that door.");
        Contestant witness = nearestStranger(entity);
        if (witness != null && !minds()) {
            witness.speak(nameOf(id) + " just went through the " + room.sign(1) + " door and was killed by a laser in a tiny room with no way out.",
                    Lines.pick(random, "No. No no no.", "It killed them. Just like that.", "Not that one! The other door!"));
        }
    }

    private void enter(UUID id, LivingEntity entity, Room room) {
        Integer was = roomOf.put(id, room.index);
        if (was != null && was == room.index) {
            return;
        }
        if (was != null && room.index == was + 1) {
            Room behind = rooms.get(was);
            behind.provenSafe[0] = true;
            route.computeIfAbsent(id, k -> new ArrayList<>()).add(behind.onward.word());
            if (behind.expiredAt < 0) {
                behind.endsAt = Math.min(behind.endsAt, ticks + BEHIND);     // the fire comes on behind you
            }
        }
        if (room.startedAt < 0) {
            room.startedAt = ticks;
            room.endsAt = ticks + ROOM_SECONDS[room.index] * 20;
            announce("Room " + (room.index + 1) + ". " + ROOM_SECONDS[room.index] + " seconds.");
        }
        if (entity instanceof ServerPlayerEntity player) {
            tell(player, "ROOM " + (room.index + 1) + ": " + ROOM_SECONDS[room.index] + " seconds.", Formatting.YELLOW);
        }
        if (entity instanceof Contestant contestant) {
            // they carry on the last couple of steps into the room (not stopping dead in the doorway), then take it in
            startThinking(contestant, room);
        }
    }

    private void startThinking(Contestant contestant, Room room) {
        UUID id = contestant.getUuid();
        committed.remove(id);
        openedAt.remove(id);
        nextLook.put(id, -1);
        decideAt.put(id, ticks + think(contestant, room));
        belief.put(id, believe(contestant, room));
    }

    /** How long they take to make up their mind, by nature and by how frightened they are (and how little time there is). */
    private int think(Contestant contestant, Room room) {
        int base = switch (contestant.nature) {
            case RECKLESS -> 30 + random.nextInt(50);
            case CLEVER -> 90 + random.nextInt(150);
            case TIMID -> 160 + random.nextInt(220);
            default -> 90 + random.nextInt(180);
        };
        float f = contestant.feelings.fear;
        float push = contestant.feelings.grief + contestant.feelings.anger;
        int t = (int) (base * (1.0F + f * 0.8F) / (1.0F + push * 0.6F));
        return Math.min(t, Math.max(20, ROOM_SECONDS[room.index] * 20 / 2));
    }

    /**
     * Which door this stranger believes in, with no AI to think for them: the sharp ones work out the building; the
     * rest go by the windows if they notice them, or trust the LIVE sign.
     */
    private int believe(Contestant contestant, Room room) {
        float sharp = contestant.judgment();
        if (random.nextFloat() < 0.2F + sharp * 0.7F) {
            return 0;
        }
        if (room.windows.contains(room.wrong) && random.nextFloat() < sharp) {
            return 0;       // noticed the other door's in a wall with windows: it can only lead outside
        }
        return random.nextFloat() < 0.65F ? (room.onwardSignedLive ? 0 : 1) : random.nextInt(2);
    }

    private void strangersThink() {
        for (Contestant contestant : strangers()) {
            UUID id = contestant.getUuid();
            Integer index = roomOf.get(id);
            if (index == null) {
                continue;
            }
            Room room = rooms.get(index);
            if (!room.contains(contestant.getPos()) && !nearDoor(contestant, room)) {
                continue;       // on their way through a door: keep going
            }
            int door = belief.getOrDefault(id, 0);
            // what they've seen with their own eyes beats what they worked out, and they say so
            int seen = room.provenDeadly[door] ? 1 - door : room.provenSafe[1 - door] ? 1 - door : door;
            if (seen != door) {
                belief.put(id, seen);
                if (committed.contains(id)) {
                    contestant.speak("You were about to take the door signed " + room.sign(door) + ", but you've just seen that the other one, signed "
                                    + room.sign(seen) + ", is the way. You're changing your mind out loud.",
                            Lines.pick(random, "Not that one. The " + room.sign(seen) + " door!", "Wait! It's the other one!", "Change of plan. " + room.sign(seen) + "."));
                }
                door = seen;
            }
            boolean urgent = room.secondsLeft() < 8 || room.provenSafe[door] && room.opened[door];
            boolean thinking = !committed.contains(id) && aiHolds(contestant) && room.secondsLeft() > 5;
            if (!committed.contains(id) && (ticks < decideAt.getOrDefault(id, 0) || thinking) && !urgent) {
                deliberate(contestant, room);
                continue;
            }
            if (committed.add(id)) {
                // they say which door, so what they say is what they do
                Dir dir = room.door(door);
                contestant.speak("You've made up your mind in room " + (room.index + 1) + ": the door signed " + room.sign(door) + ", in the "
                                + dir.word() + " wall. You're walking to it now.",
                        Lines.pick(random, "The " + room.sign(door) + " door. I'm going.", "I'm taking the " + room.sign(door) + " one.",
                                "This way. The " + dir.word() + " door."));
            }
            Dir dir = room.door(door);
            BlockPos doorPos = doorPos(room, dir);
            if (!room.opened[door]) {
                if (contestant.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) < 2.4 * 2.4) {
                    contestant.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    open(room, door, contestant.name());
                } else {
                    contestant.go(before(room, dir), urgent ? 1.3 : 1.0);
                    contestant.lookAt(Vec3d.ofCenter(doorPos.up()));
                }
                continue;
            }
            if (door == 1 && contestant.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) < 3.0 * 3.0) {
                // the door opens on a cupboard of a room with no way out: a beat to take that in
                int since = ticks - openedAt.computeIfAbsent(id, k -> ticks);
                if (since < 30) {
                    contestant.lookAt(Vec3d.ofCenter(doorPos.add(dir.dx, 0, dir.dz)));
                    if (since == 10 && !minds() && contestant.judgment() > 0.55F) {
                        belief.put(id, 0);
                        openedAt.remove(id);
                        contestant.speak("You opened the door signed " + room.sign(1) + " and behind it is a tiny dark space with no way out.",
                                Lines.pick(random, "There's nothing here. It's a cupboard.", "This goes nowhere. The other door!", "No. This isn't a way out."));
                    }
                    continue;
                }
            }
            contestant.go(beyond(room, dir), urgent ? 1.3 : 1.05);
        }
    }

    private boolean nearDoor(LivingEntity who, Room room) {
        for (int d = 0; d < 2; d++) {
            if (who.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos(room, room.door(d)))) < 2.0 * 2.0) {
                return true;
            }
        }
        return false;
    }

    /** While they're making up their minds: look at the doors, out of the windows, at each other. Mostly, stand and think. */
    private void deliberate(Contestant c, Room room) {
        UUID id = c.getUuid();
        if (!c.arrived(1.2) || ticks < nextLook.getOrDefault(id, 0)) {
            return;
        }
        boolean first = nextLook.getOrDefault(id, -1) < 0;
        nextLook.put(id, ticks + 20 * (4 + random.nextInt(8)));
        if (!first && random.nextFloat() < 0.4F) {
            return;     // stay where you are, and keep thinking
        }
        int oy = origin.getY();
        int what = first ? random.nextInt(2) : random.nextInt(5);
        if (what <= 1) {
            // one of the doors, and the sign over it
            Dir dir = room.door(what);
            Vec3d spot = before(room, dir).add(-dir.dx * 0.8, 0, -dir.dz * 0.8);
            c.go(spot.add((random.nextDouble() - 0.5) * 1.5, 0, (random.nextDouble() - 0.5) * 1.5), 0.9);
            c.lookAt(Vec3d.ofCenter(doorPos(room, dir).up(2)));
        } else if (what == 2) {
            // out of a window, if there is one: where is this room, in the building?
            for (Dir d : room.windows) {
                {
                    BlockPos window = wallPoint(room, d, random.nextBoolean() ? 0 : CELL - 1, oy + 1, 1);
                    c.go(Vec3d.ofBottomCenter(window).add(-d.dx * 1.3, 0, -d.dz * 1.3), 0.9);
                    c.lookAt(Vec3d.ofCenter(window).add(d.dx * 3.0, 0.3, d.dz * 3.0));
                    return;
                }
            }
        } else if (what == 3) {
            // someone else: what do you make of it?
            LivingEntity other = null;
            for (LivingEntity e : living()) {
                if (e != c && room.contains(e.getPos()) && (other == null || random.nextBoolean())) {
                    other = e;
                }
            }
            if (other != null) {
                Vec3d to = other.getPos().add(c.getPos().subtract(other.getPos()).multiply(1, 0, 1).normalize().multiply(1.6));
                c.go(new Vec3d(Math.max(room.xMin() + 0.5, Math.min(room.xMax() + 0.5, to.x)), oy,
                        Math.max(room.zMin() + 0.5, Math.min(room.zMax() + 0.5, to.z))), 0.9);
                c.look(other);
            }
        }
        // else: stand where you are, head down, thinking
    }

    private void open(Room room, int door, String who) {
        if (room.opened[door]) {
            return;
        }
        room.opened[door] = true;
        BlockPos pos = doorPos(room, room.door(door));
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock doorBlock) {
            doorBlock.setOpen(null, world, state, pos, true);
        }
        world.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.0F, 0.8F);
        String behind = door == 0 ? (room.index == ROOMS - 1 ? "a hall with a way out" : "another room")
                : "a tiny dark space, no bigger than a cupboard, with no way out";
        note(who + " opened the door signed " + room.sign(door) + " in the " + room.door(door).word() + " wall of room "
                + (room.index + 1) + ". Behind it: " + behind + ".");
    }

    /** A room whose time is up fills with smoke, then burns: anyone still in it dies. */
    private void tickFire(Room room) {
        if (room.startedAt < 0) {
            return;
        }
        if (room.expiredAt < 0 && ticks >= room.endsAt) {
            room.expiredAt = ticks;
            if (anyoneIn(room)) {
                announce("Room " + (room.index + 1) + ": time is up.");
            }
            world.playSound(null, room.box().getCenter().x, origin.getY() + 2, room.box().getCenter().z, SoundEvents.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.BLOCKS, 2.0F, 0.5F);
        }
        Vec3d c = room.box().getCenter();
        if (room.expiredAt < 0) {
            // smoke seeps under the door from the room behind, once it's about to go
            if (room.endsAt - ticks < 20 * 5 && ticks % 5 == 0) {
                world.spawnParticles(ParticleTypes.SMOKE, c.x, origin.getY() + 0.2, c.z, 6, 2.5, 0.1, 2.5, 0.01);
            }
            return;
        }
        int since = ticks - room.expiredAt;
        if (since < 60) {
            if (since % 3 == 0) {
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, origin.getY() + 1.0, c.z, 20, 2.5, 1.2, 2.5, 0.01);
            }
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, room.box(), LivingEntity::isAlive)) {
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0));
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
            }
            return;
        }
        if (since == 60) {
            world.playSound(null, c.x, origin.getY() + 2, c.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 2.0F, 0.6F);
            for (int x = room.xMin(); x <= room.xMax(); x++) {
                for (int z = room.zMin(); z <= room.zMax(); z++) {
                    BlockPos at = new BlockPos(x, origin.getY(), z);
                    if (random.nextFloat() < 0.4F && world.isAir(at)) {
                        world.setBlockState(at, Blocks.FIRE.getDefaultState());
                    }
                }
            }
        }
        if (since < 400 && since % 4 == 0) {
            world.spawnParticles(ParticleTypes.FLAME, c.x, origin.getY() + 1.0, c.z, 12, 2.5, 1.2, 2.5, 0.02);
        }
        for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, room.box(), LivingEntity::isAlive)) {
            entity.setOnFireFor(6);
            if (since > 100) {
                if (isPlaying(entity.getUuid())) {
                    fail(entity.getUuid(), "The room burned.");
                } else if (entity instanceof Contestant k && !k.isBody()) {
                    Laser.strike(entity, null);
                }
            }
        }
    }

    private boolean anyoneIn(Room room) {
        for (LivingEntity entity : living()) {
            if (room.contains(entity.getPos())) {
                return true;
            }
        }
        return false;
    }

    private Contestant nearestStranger(LivingEntity near) {
        Contestant best = null;
        double distance = 400.0;
        for (Contestant contestant : strangers()) {
            double d = contestant.squaredDistanceTo(near);
            if (d < distance && contestant != near) {
                best = contestant;
                distance = d;
            }
        }
        return best;
    }

    @Override
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock) || !isPlaying(player.getUuid()) || phase != Phase.RUNNING) {
            return state.getBlock() instanceof DoorBlock ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        BlockPos lower = state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
        for (Room room : rooms) {
            for (int door = 0; door < 2; door++) {
                if (lower.equals(doorPos(room, room.door(door)))) {
                    open(room, door, player.getEntityName());
                    return ActionResult.SUCCESS;
                }
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void more(ServerPlayerEntity player, Hud hud) {
        Integer index = roomOf.get(player.getUuid());
        if (index != null) {
            Room room = rooms.get(index);
            hud.line(room.contains(player.getPos()) ? "§fROOM " + (index + 1) + "  §e" + Hud.clock(room.secondsLeft() * 20) : "§7ROOM " + (index + 1));
        }
    }

    // --- what a person in here can know -------------------------------------------------------------------------

    @Override
    public String situation(Contestant who) {
        UUID id = who.getUuid();
        Integer index = roomOf.get(id);
        StringBuilder s = new StringBuilder("Before you came in, you saw the building from the street: a plain grey block, just two rooms wide "
                + "and four deep, its way in at the front corner. You registered in the lobby, and the bars closed behind you. ");
        if (index == null) {
            return s.toString();
        }
        Room room = rooms.get(index);
        Dir facing = room.in;
        s.append("You're in room ").append(room.index + 1).append(" (nobody's said how many there are). ").append(room.secondsLeft())
                .append(" seconds left before it burns").append(room.index > 0 ? ", and the rooms behind you are burning." : ".")
                .append(" You came in through the ").append(room.in.opposite().word()).append(" wall, facing ").append(facing.word()).append(". ");
        for (int d = 0; d < 2; d++) {
            Dir dir = room.door(d);
            String side = dir == facing ? "straight ahead" : dir == facing.left() ? "on your left" : dir == facing.right() ? "on your right" : "behind you";
            s.append("One door is in the ").append(dir.word()).append(" wall (").append(side).append("), signed ").append(room.sign(d)).append(". ");
        }
        List<String> windows = new ArrayList<>();
        for (Dir d : Dir.values()) {
            if (room.windows.contains(d)) {
                windows.add(d.word());
            }
        }
        s.append(windows.isEmpty() ? "This room has no windows at all. " : "Through the windows in the " + String.join(" and ", windows)
                + (windows.size() == 1 ? " wall" : " walls") + " you can see the street outside. ");
        List<String> went = route.getOrDefault(id, List.of());
        if (!went.isEmpty()) {
            s.append("Room to room so far, you've gone: ").append(String.join(", then ", went)).append(". ");
        }
        for (int d = 0; d < 2; d++) {
            if (room.provenDeadly[d]) {
                s.append("The door signed ").append(room.sign(d)).append(" has killed someone. ");
            } else if (room.opened[d] && d == 1) {
                s.append("The door signed ").append(room.sign(d)).append(" is open: behind it is a tiny dark space with no way out. ");
            } else if (room.opened[d]) {
                s.append("The door signed ").append(room.sign(d)).append(" is open, and there's another room through it. ");
            }
        }
        Integer door = belief.get(id);
        if (door != null && committed.contains(id)) {
            s.append("You've decided on the door signed ").append(room.sign(door)).append(" and you're going through it.");
        }
        return s.toString();
    }

    @Override
    protected void died(UUID id) {
        roomOf.remove(id);
    }

    /** The room {@code who} is in and still has to choose a door in, or null. */
    private Room choosing(Contestant who) {
        Integer index = roomOf.get(who.getUuid());
        if (index == null) {
            return null;
        }
        Room room = rooms.get(index);
        return room.contains(who.getPos()) ? room : null;
    }

    @Override
    public Map<String, String> options(Contestant who) {
        Room room = choosing(who);
        if (room == null) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        int live = room.onwardSignedLive ? 0 : 1;
        options.put("LIVE", "open the door signed LIVE, in the " + room.door(live).word() + " wall, and go through it");
        options.put("DIE", "open the door signed DIE, in the " + room.door(1 - live).word() + " wall, and go through it");
        options.put("WAIT", "stay where you are a little longer and think");
        return options;
    }

    @Override
    protected Map<String, String> optionWords(Contestant who) {
        Room room = choosing(who);
        if (room == null) {
            return Map.of();
        }
        int live = room.onwardSignedLive ? 0 : 1;
        Dir facing = room.in;
        Map<String, String> words = new LinkedHashMap<>();
        for (int sign = 0; sign < 2; sign++) {
            int d = sign == 0 ? live : 1 - live;
            Dir dir = room.door(d);
            String label = sign == 0 ? "live" : "die";
            String rel = dir == facing ? "straight|ahead|front" : dir == facing.left() ? "left" : dir == facing.right() ? "right" : "behind";
            words.put(sign == 0 ? "LIVE" : "DIE", "\\b(" + label + " door|the " + label + " one|" + label + " sign|says " + label + "|marked " + label
                    + "|" + dir.word() + " door|" + dir.word() + " wall|door on the (" + rel + ")|(" + rel + ") door|the (" + rel + ") one)\\b");
        }
        return words;
    }

    @Override
    public void chose(Contestant who, String option) {
        Room room = choosing(who);
        if (room == null) {
            return;
        }
        UUID id = who.getUuid();
        int live = room.onwardSignedLive ? 0 : 1;
        switch (option) {
            case "LIVE", "DIE" -> {
                belief.put(id, option.equals("LIVE") ? live : 1 - live);
                decideAt.put(id, ticks);
                committed.add(id);      // they've said it themselves: no second announcement
                who.stopAll();
            }
            case "WAIT" -> decideAt.put(id, Math.max(decideAt.getOrDefault(id, ticks), ticks + 80));
            default -> {
            }
        }
    }
}
