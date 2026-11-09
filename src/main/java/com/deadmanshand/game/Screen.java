package com.deadmanshand.game;

import net.minecraft.block.BlockState;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen on a wall of the venue, like the ones in the show: black, with the card, the game, the rules as they're read
 * out, the clock, and at the end GAME CLEAR or GAME OVER. Three maps across and two down, in glowing item frames (so it
 * lights up in the dark), fixed to the wall: nobody can take or turn them.
 */
final class Screen {
    static final int ACROSS = 3;
    static final int DOWN = 2;
    static final int W = 128 * ACROSS;
    static final int H = 128 * DOWN;
    static final String TAG = "deadmanshand_screen";

    // map colours: colour id * 4 + shade (shade 2 is full brightness, 1 a little darker, 0 darker, 3 darkest)
    static final byte BG = (byte) (29 * 4 + 3);
    static final byte WHITE = (byte) (8 * 4 + 2);
    static final byte GRAY = (byte) (22 * 4 + 1);
    static final byte DARK = (byte) (21 * 4);
    static final byte RED = (byte) (4 * 4 + 2);
    static final byte GREEN = (byte) (19 * 4 + 2);
    static final byte GOLD = (byte) (30 * 4 + 2);
    static final byte CARD = (byte) (8 * 4 + 1);
    static final byte EDGE = (byte) (22 * 4);
    static final byte INK = (byte) (29 * 4 + 2);

    /** What a screen shows. */
    record Look(String top, String headline, byte headlineColor, String sub, byte subColor, String body, @Nullable Card card,
                @Nullable String clock) {
    }

    // --- the picture -----------------------------------------------------------------------------------------------

    final byte[] px = new byte[W * H];

    void fill(int x, int y, int w, int h, byte c) {
        for (int yy = Math.max(0, y); yy < Math.min(H, y + h); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(W, x + w); xx++) {
                px[yy * W + xx] = c;
            }
        }
    }

    void dot(int x, int y, byte c) {
        if (x >= 0 && y >= 0 && x < W && y < H) {
            px[y * W + x] = c;
        }
    }

    static final class Font {
        final int height;
        final int[] widths;
        final boolean[][] bits;

        Font(int height, String data) {
            this.height = height;
            String[] parts = data.split("\\|");
            widths = new int[parts.length];
            bits = new boolean[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                int dotAt = part.indexOf('.');
                widths[i] = Integer.parseInt(part.substring(0, 2), 16);
                String hex = part.substring(dotAt + 1);
                boolean[] b = new boolean[widths[i] * height];
                for (int k = 0; k < b.length; k++) {
                    int digit = Character.digit(hex.charAt(k / 4), 16);
                    b[k] = (digit >> (3 - k % 4) & 1) != 0;
                }
                bits[i] = b;
            }
        }

        int index(char ch) {
            int i = ScreenFont.CHARS.indexOf(ch);
            return i >= 0 ? i : ScreenFont.CHARS.indexOf('?');
        }

        int width(String s, int scale) {
            int w = 0;
            for (int i = 0; i < s.length(); i++) {
                w += (widths[index(s.charAt(i))] + 1) * scale;
            }
            return Math.max(0, w - scale);
        }
    }

    static final Font SMALL = new Font(ScreenFont.SMALL_HEIGHT, ScreenFont.SMALL);
    static final Font BIG = new Font(ScreenFont.BIG_HEIGHT, ScreenFont.BIG);
    static final Font HUGE = new Font(ScreenFont.HUGE_HEIGHT, ScreenFont.HUGE);

    int text(Font f, String s, int x, int y, byte c, int scale) {
        int at = x;
        for (int i = 0; i < s.length(); i++) {
            int g = f.index(s.charAt(i));
            int w = f.widths[g];
            boolean[] b = f.bits[g];
            for (int gy = 0; gy < f.height; gy++) {
                for (int gx = 0; gx < w; gx++) {
                    if (b[gy * w + gx]) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                dot(at + gx * scale + sx, y + gy * scale + sy, c);
                            }
                        }
                    }
                }
            }
            at += (w + 1) * scale;
        }
        return at - x;
    }

    void centered(Font f, String s, int cx, int y, byte c, int scale) {
        text(f, s, cx - f.width(s, scale) / 2, y, c, scale);
    }

    /** {@code s} broken into lines no wider than {@code width}. */
    static List<String> wrap(Font f, String s, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (f.width(next, 1) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The playing card, as it's shown in the show: white, the suit in the middle, the rank in the corners. */
    void card(Card card, int x, int y, int w, int h) {
        fill(x + 2, y, w - 4, h, CARD);
        fill(x, y + 2, w, h - 4, CARD);
        fill(x + 1, y + 1, w - 2, h - 2, CARD);
        for (int i = 2; i < w - 2; i++) {
            dot(x + i, y, EDGE);
            dot(x + i, y + h - 1, EDGE);
        }
        for (int i = 2; i < h - 2; i++) {
            dot(x, y + i, EDGE);
            dot(x + w - 1, y + i, EDGE);
        }
        byte ink = card.suit().red ? RED : INK;
        String suit = String.valueOf(card.suit().symbol);
        if (card.rank() == Card.JOKER) {
            centered(BIG, "JOKER", x + w / 2, y + h / 2 - BIG.height / 2, (byte) (24 * 4 + 2), 1);
            text(SMALL, "JK", x + 6, y + 5, ink, 1);
            return;
        }
        String rank = switch (card.rank()) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> String.valueOf(card.rank());
        };
        text(BIG, rank, x + 6, y + 3, ink, 1);
        text(SMALL, suit, x + 7, y + 3 + BIG.height - 2, ink, 1);
        int rw = BIG.width(rank, 1);
        text(BIG, rank, x + w - 6 - rw, y + h - 4 - BIG.height, ink, 1);
        centered(HUGE, suit, x + w / 2, y + h / 2 - HUGE.height, ink, 2);
    }

    void draw(Look look) {
        fill(0, 0, W, H, BG);
        // a thin frame round the edge, like the bezel of a monitor
        for (int i = 0; i < W; i++) {
            dot(i, 0, DARK);
            dot(i, H - 1, DARK);
        }
        for (int i = 0; i < H; i++) {
            dot(0, i, DARK);
            dot(W - 1, i, DARK);
        }
        if (look.card() == null) {
            // the entrance, and the end: one big word in the middle
            centered(SMALL, look.top(), W / 2, 22, GRAY, 1);
            Font f = HUGE.width(look.headline(), 1) <= W - 24 ? HUGE : BIG;
            centered(f, look.headline(), W / 2, 92 - f.height / 2, look.headlineColor(), 1);
            centered(SMALL, look.sub(), W / 2, 150, look.subColor(), 1);
            int y = 182;
            for (String line : wrap(SMALL, look.body(), W - 40)) {
                if (y > H - 20) {
                    break;
                }
                centered(SMALL, line, W / 2, y, GRAY, 1);
                y += SMALL.height + 2;
            }
            return;
        }
        // the game: the card on the left, the game and its difficulty on the right
        card(look.card(), 16, 14, 100, 140);
        int rx = 132;
        int rw = W - rx - 14;
        text(SMALL, look.top(), rx, 16, GRAY, 1);
        int y = 34;
        List<String> title = wrap(BIG, look.headline(), rw);
        boolean bigFits = title.size() <= 2;
        for (String line : title) {
            bigFits &= BIG.width(line, 1) <= rw;
        }
        Font f = bigFits ? BIG : SMALL;
        for (String line : bigFits ? title : wrap(SMALL, look.headline(), rw)) {
            text(f, line, rx, y, look.headlineColor(), 1);
            y += f.height + 1;
        }
        y += 6;
        text(SMALL, "DIFFICULTY", rx, y, GRAY, 1);
        y += SMALL.height + 1;
        for (String line : wrap(SMALL, look.sub(), rw)) {
            text(SMALL, line, rx, y, look.subColor(), 1);
            y += SMALL.height + 1;
        }
        if (look.clock() != null) {
            if (y <= 104) {
                text(SMALL, "TIME LEFT", rx, 108, GRAY, 1);
                text(BIG, look.clock(), rx, 124, WHITE, 1);
            } else {
                // a two-line name: the clock goes beside its label
                int row = Math.max(y + 6, 132);
                int lw = text(SMALL, "TIME LEFT", rx, row + BIG.height - SMALL.height - 3, GRAY, 1);
                text(BIG, look.clock(), rx + lw + 10, row, WHITE, 1);
            }
        }
        // below: what's being read out, or what's going on
        fill(14, 166, W - 28, 1, DARK);
        int by = 174;
        for (String line : wrap(SMALL, look.body(), W - 36)) {
            if (by > H - 18) {
                break;
            }
            text(SMALL, line, 18, by, WHITE, 1);
            by += SMALL.height + 2;
        }
    }

    // --- in the world ----------------------------------------------------------------------------------------------

    private final ServerWorld world;
    private final List<ItemFrameEntity> frames = new ArrayList<>();
    /** The monitor and its post, when there was no wall for it: taken away with it. */
    private final List<BlockPos> built = new ArrayList<>();
    private final MapState[] maps = new MapState[ACROSS * DOWN];
    final Vec3d center;
    @Nullable
    private Look shown;

    private Screen(ServerWorld world, Vec3d center) {
        this.world = world;
        this.center = center;
    }

    /** A screen that isn't anywhere: for drawing on. */
    static Screen offscreen() {
        return new Screen(null, Vec3d.ZERO);
    }

    /** Shows {@code look} (only what changed is sent). */
    void show(Look look) {
        if (look.equals(shown)) {
            return;
        }
        shown = look;
        draw(look);
        for (int r = 0; r < DOWN; r++) {
            for (int c = 0; c < ACROSS; c++) {
                MapState map = maps[r * ACROSS + c];
                if (map == null) {
                    continue;
                }
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        map.putColor(x, y, px[(r * 128 + y) * W + c * 128 + x]);
                    }
                }
            }
        }
    }

    void remove() {
        for (ItemFrameEntity frame : frames) {
            frame.discard();
        }
        frames.clear();
        for (BlockPos pos : built) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.BLACK_CONCRETE) || state.isOf(net.minecraft.block.Blocks.POLISHED_BLACKSTONE_WALL)) {
                world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
            }
        }
        built.clear();
    }

    /** Takes down any screens left in {@code area} (a game that was running when the world was closed). */
    static void clearOld(ServerWorld world, Box area) {
        for (ItemFrameEntity frame : world.getEntitiesByClass(ItemFrameEntity.class, area, f -> f.getScoreboardTags().contains(TAG))) {
            frame.discard();
        }
    }

    /**
     * Puts a screen on the best wall near {@code viewer} (someone's eyes), inside {@code area}: a flat wall of solid
     * blocks three wide and two high, open in front, facing the viewer, that they can see from where they are. With no
     * such wall and {@code mayStand}, a black monitor on a post, put up where they can see it (and taken away after).
     */
    @Nullable
    static Screen place(ServerWorld world, Box area, Vec3d viewer, boolean mayStand) {
        BlockPos eye = new BlockPos((int) Math.floor(viewer.x), (int) Math.floor(viewer.y), (int) Math.floor(viewer.z));
        Direction[] faces = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        double bestScore = Double.MAX_VALUE;
        BlockPos bestBase = null;
        Direction bestFace = null;
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    for (Direction face : faces) {
                        // base: the wall block at the bottom left of the screen, as you look at it
                        BlockPos base = eye.add(dx, dy, dz);
                        Direction right = face.rotateYCounterclockwise();
                        Vec3d mid = new Vec3d(base.getX() + 0.5 + right.getOffsetX() * 1.0 + face.getOffsetX() * 0.55,
                                base.getY() + 1.0, base.getZ() + 0.5 + right.getOffsetZ() * 1.0 + face.getOffsetZ() * 0.55);
                        double ahead = (viewer.x - mid.x) * face.getOffsetX() + (viewer.z - mid.z) * face.getOffsetZ();
                        double distance = viewer.distanceTo(mid);
                        if (ahead < 1.5 || distance > 18.0) {
                            continue;
                        }
                        double score = Math.abs(distance - 5.0) + Math.abs(mid.y - viewer.y - 0.3) * 3.0 + (1.0 - ahead / distance) * 8.0;
                        if (score >= bestScore || !fits(world, area, base, face, right) || !inSight(world, viewer, mid)) {
                            continue;
                        }
                        bestScore = score;
                        bestBase = base;
                        bestFace = face;
                    }
                }
            }
        }
        List<BlockPos> built = new ArrayList<>();
        if (bestBase == null && !mayStand) {
            return null;
        }
        if (bestBase == null) {
            // no wall anyone can see: a monitor on a post, standing where they can
            BlockPos feet = new BlockPos((int) Math.floor(viewer.x), (int) Math.floor(viewer.y - 1.6), (int) Math.floor(viewer.z));
            double bestStand = Double.MAX_VALUE;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -9; dx <= 9; dx++) {
                    for (int dz = -9; dz <= 9; dz++) {
                        for (Direction face : faces) {
                            BlockPos post = feet.add(dx, dy, dz);
                            Direction right = face.rotateYCounterclockwise();
                            Vec3d mid = new Vec3d(post.getX() + 0.5 + face.getOffsetX() * 0.55, post.getY() + 2.0, post.getZ() + 0.5 + face.getOffsetZ() * 0.55);
                            double ahead = (viewer.x - mid.x) * face.getOffsetX() + (viewer.z - mid.z) * face.getOffsetZ();
                            double distance = viewer.distanceTo(mid);
                            if (ahead < 3.5 || distance > 10.0) {
                                continue;
                            }
                            boolean backed = !world.getBlockState(post.up().offset(face.getOpposite())).isAir();
                            double score = Math.abs(distance - 5.0) + (1.0 - ahead / distance) * 8.0 + (backed ? 0.0 : 2.0);
                            if (score >= bestStand || !standsHere(world, area, post, face, right) || !inSight(world, viewer, mid)) {
                                continue;
                            }
                            bestStand = score;
                            bestBase = post.up().offset(right, -1);
                            bestFace = face;
                        }
                    }
                }
            }
            if (bestBase == null) {
                return null;
            }
            Direction right = bestFace.rotateYCounterclockwise();
            BlockPos post = bestBase.offset(right, 1).down();
            world.setBlockState(post, net.minecraft.block.Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
            built.add(post);
            for (int c = 0; c < ACROSS; c++) {
                for (int r = 0; r < DOWN; r++) {
                    BlockPos panel = bestBase.offset(right, c).up(r);
                    world.setBlockState(panel, net.minecraft.block.Blocks.BLACK_CONCRETE.getDefaultState());
                    built.add(panel);
                }
            }
        }
        Direction right = bestFace.rotateYCounterclockwise();
        Vec3d mid = new Vec3d(bestBase.getX() + 0.5 + right.getOffsetX() + bestFace.getOffsetX() * 0.55, bestBase.getY() + 1.0,
                bestBase.getZ() + 0.5 + right.getOffsetZ() + bestFace.getOffsetZ() * 0.55);
        Screen screen = new Screen(world, mid);
        screen.built.addAll(built);
        for (int r = 0; r < DOWN; r++) {
            for (int c = 0; c < ACROSS; c++) {
                // row 0 is the top of the picture
                BlockPos wall = bestBase.offset(right, c).up(DOWN - 1 - r);
                ItemStack map = FilledMapItem.createMap(world, 29_000_000, 29_000_000, (byte) 0, false, false);
                screen.maps[r * ACROSS + c] = FilledMapItem.getMapState(FilledMapItem.getMapId(map), world);
                GlowItemFrameEntity frame = new GlowItemFrameEntity(world, wall.offset(bestFace), bestFace);
                frame.setHeldItemStack(map);
                NbtCompound nbt = new NbtCompound();
                frame.writeNbt(nbt);
                nbt.putBoolean("Fixed", true);
                nbt.putBoolean("Invisible", true);
                nbt.putBoolean("Invulnerable", true);
                frame.readNbt(nbt);
                frame.addScoreboardTag(TAG);
                world.spawnEntity(frame);
                screen.frames.add(frame);
            }
        }
        return screen;
    }

    private static boolean fits(ServerWorld world, Box area, BlockPos base, Direction face, Direction right) {
        for (int c = 0; c < ACROSS; c++) {
            for (int r = 0; r < DOWN; r++) {
                BlockPos wall = base.offset(right, c).up(r);
                BlockPos front = wall.offset(face);
                if (!area.contains(Vec3d.ofCenter(front))) {
                    return false;
                }
                BlockState state = world.getBlockState(wall);
                if (!state.isFullCube(world, wall) || !world.getBlockState(front).isAir() || !world.getBlockState(front.offset(face)).isAir()) {
                    return false;
                }
            }
        }
        Box space = new Box(base.offset(face)).union(new Box(base.offset(face).offset(right, ACROSS - 1).up(DOWN - 1)));
        return world.getEntitiesByClass(ItemFrameEntity.class, space.expand(0.1), f -> true).isEmpty();
    }

    /** Room for a monitor on a post at {@code post}: open ground, open air for it and in front of it. */
    private static boolean standsHere(ServerWorld world, Box area, BlockPos post, Direction face, Direction right) {
        BlockPos below = post.down();
        if (world.getBlockState(below).getCollisionShape(world, below).isEmpty() || !world.getBlockState(post).isAir()
                || !area.contains(Vec3d.ofCenter(post))) {
            return false;
        }
        for (int c = -1; c <= 1; c++) {
            for (int r = 1; r <= 2; r++) {
                BlockPos panel = post.offset(right, c).up(r);
                if (!world.getBlockState(panel).isAir() || !world.getBlockState(panel.offset(face)).isAir()
                        || !world.getBlockState(panel.offset(face, 2)).isAir() || !area.contains(Vec3d.ofCenter(panel))) {
                    return false;
                }
            }
        }
        return world.getEntitiesByClass(ItemFrameEntity.class, new Box(post).expand(2.5), f -> true).isEmpty();
    }

    /** Nothing solid between {@code from} and {@code to}. */
    private static boolean inSight(ServerWorld world, Vec3d from, Vec3d to) {
        double length = from.distanceTo(to);
        int steps = (int) Math.ceil(length / 0.3);
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Vec3d at = new Vec3d(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
            BlockPos pos = new BlockPos((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z));
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
