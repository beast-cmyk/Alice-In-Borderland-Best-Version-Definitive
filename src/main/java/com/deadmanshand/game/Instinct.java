package com.deadmanshand.game;

import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a frightened person does when the danger is right there, before any thinking: the strangers' instinct, the same
 * in every game that has something hunting them.
 *
 * <p>When it's there (it's looking at them, they've caught sight of it close by, it's shooting at them or at someone
 * beside them, it's on top of them), a person doesn't work out the place furthest from it. They flinch first. Then, by
 * who they are and how scared they already are: some freeze for a second or two, the calm ones slip away quietly, the
 * terrified ones bolt. From a gun, out of its sight is the point: the nearest doorway, a room, round a corner, not
 * distance. From something that chases, away from it, round things it has to go round, somewhere it can't see them go.
 * Once they're clear they keep still a while, the ones with their wits about them warn the others where it is, and then
 * they think again (that's the AI's again).
 */
final class Instinct {
    enum Kind { SHOOTER, CHASER }

    /**
     * A danger to the strangers. {@code noun}: what they'd call it ("it", "the King", "a cat"). {@code sees}: whether it
     * can see someone (null: it looks with its eyes, what's in front of it). {@code where}: where it is, in words, for
     * the ones who warn the others (null: which way from them, and how far).
     */
    record Threat(LivingEntity who, Kind kind, String noun, @Nullable Predicate<LivingEntity> sees, @Nullable Function<LivingEntity, String> where) {
        static Threat shooter(LivingEntity who, String noun) {
            return new Threat(who, Kind.SHOOTER, noun, null, null);
        }

        static Threat chaser(LivingEntity who, String noun) {
            return new Threat(who, Kind.CHASER, noun, null, null);
        }

        boolean canSee(LivingEntity target) {
            if (sees != null) {
                return sees.test(target);
            }
            double d = who.distanceTo(target);
            return d < 1.6 || d < 24.0 && facing(who, target) > 0.5 && who.canSee(target);
        }
    }

    enum Fright { STARTLED, FROZEN, ESCAPING, HIDING }

    private static final class Panic {
        Fright state;
        long until;
        long since;
        @Nullable Vec3d to;
        double pace;
        final Threat from;
        int outOfSight;

        Panic(Fright state, long until, Threat from) {
            this.state = state;
            this.until = until;
            this.from = from;
        }
    }

    private final Game game;
    private final Map<UUID, Panic> panic = new HashMap<>();
    private final Map<UUID, Long> underFire = new HashMap<>();
    private final Map<UUID, Long> criedAt = new HashMap<>();

    Instinct(Game game) {
        this.game = game;
    }

    /** {@code c} was just shot at, or someone right beside them was: that sets it off at once. */
    void underFire(Contestant c) {
        underFire.put(c.getUuid(), game.world.getTime());
    }

    /** It fired at {@code target}: whoever was hit or beside them is under fire. */
    void gunfire(LivingEntity target) {
        for (Contestant c : game.strangers()) {
            if (c == target || c.distanceTo(target) < 5.0) {
                underFire(c);
            }
        }
    }

    /** Where {@code c} is in it, or null if they're not. */
    @Nullable
    Fright state(UUID id) {
        Panic p = panic.get(id);
        return p == null ? null : p.state;
    }

    void forget(UUID id) {
        panic.remove(id);
        underFire.remove(id);
    }

    /** A bang, a burst of fire, a collapse at {@code at}: everyone near flinches from it, and some cry out. */
    void flinch(Vec3d at, double radius, float scare) {
        for (Contestant c : game.strangers()) {
            double d = c.getPos().distanceTo(at);
            if (d > radius || !c.isAlive()) {
                continue;
            }
            c.startle(at);
            c.feelings.scared(c, scare * (float) (1.0 - d / radius));
            if (d < radius * 0.6 && c.quiet() && game.random.nextFloat() < 0.35F) {
                c.say(switch (c.nature) {
                    case TIMID -> Lines.pick(game.random, "No!", "What was that?!", "Oh god...");
                    case BRAVE -> Lines.pick(game.random, "Get back!", "Everyone OK?", "Keep moving!");
                    case KIND -> Lines.pick(game.random, "Is everyone alright?", "Careful!", "Get away from it!");
                    case CLEVER -> Lines.pick(game.random, "Back. Now.", "Don't stand there.", "That's what happens. Think.");
                    case SELFISH -> Lines.pick(game.random, "Not me. Not me.", "Out of my way!", "Damn it!");
                    case RECKLESS -> Lines.pick(game.random, "Whoa!", "Ha... that was close.", "Move, move!");
                });
            }
        }
    }

    /**
     * {@code c}'s instinct this tick, against {@code threats}: true while it's in charge of them (nothing else should
     * move them).
     */
    boolean react(Contestant c, List<Threat> threats) {
        UUID id = c.getUuid();
        long now = game.world.getTime();
        Threat near = null;
        double nearest = Double.MAX_VALUE;
        for (Threat threat : threats) {
            if (threat.who() == null || !threat.who().isAlive()) {
                continue;
            }
            double d = threat.who().distanceTo(c);
            if (d < nearest) {
                nearest = d;
                near = threat;
            }
        }
        Panic p = panic.get(id);
        if (near == null) {
            panic.remove(id);
            return false;
        }
        LivingEntity it = near.who();
        boolean itSeesThem = near.canSee(c) || nearest < 5.0 && it.canSee(c);
        boolean theySeeIt = nearest < 16.0 && c.canSee(it) && facing(c, it) > -0.2;
        boolean shot = now - underFire.getOrDefault(id, -1000L) < 10;
        boolean hurt = c.hurtTime > 0 && c.getAttacker() == it;
        boolean triggered = near.kind() == Kind.SHOOTER
                ? itSeesThem || theySeeIt || shot || hurt
                : nearest < 12.0 && (itSeesThem || theySeeIt) || nearest < 5.0 || hurt;
        boolean again = p != null && p.state == Fright.HIDING && (near.kind() == Kind.SHOOTER ? itSeesThem || shot || hurt : nearest < 8.0 || hurt);
        if (p == null || again) {
            if (!triggered) {
                return false;
            }
            // the flinch: the half second it takes a person to react
            p = new Panic(Fright.STARTLED, now + 5 + game.random.nextInt(6), near);
            p.since = now;
            panic.put(id, p);
            c.stopAll();
            c.startle(it.getEyePos());
            c.feelings.scared(c, shot || hurt ? 0.3F : 0.2F);
            cry(c, near, shot || hurt);
            game.reflex(c);
            return true;
        }
        game.reflex(c);
        LivingEntity from = p.from.who() != null && p.from.who().isAlive() ? p.from.who() : it;
        double away = from.distanceTo(c);
        switch (p.state) {
            case STARTLED -> {
                if (now < p.until) {
                    return true;
                }
                // then: freeze, or get away, fast or quietly, by who they are and how scared they are
                float freeze = 0.06F + 0.3F * c.feelings.fear + (c.nature == Contestant.Nature.TIMID ? 0.15F : 0.0F)
                        - (c.nature == Contestant.Nature.BRAVE || c.nature == Contestant.Nature.RECKLESS ? 0.05F : 0.0F);
                if (p.from.kind() == Kind.CHASER) {
                    freeze *= 0.5F;
                }
                if (game.random.nextFloat() < freeze && !shot && !hurt) {
                    p.state = Fright.FROZEN;
                    p.until = now + 15 + game.random.nextInt(30);
                    c.hold();
                    c.lookAt(from.getEyePos());
                    return true;
                }
                escape(c, p, from);
                return true;
            }
            case FROZEN -> {
                c.lookAt(from.getEyePos());
                if (now >= p.until || shot || hurt || away < 3.0) {
                    escape(c, p, from);
                }
                return true;
            }
            case ESCAPING -> {
                boolean outOfSight = !from.canSee(c);
                p.outOfSight = outOfSight ? p.outOfSight + 1 : 0;
                boolean there = p.to != null && c.squaredDistanceTo(p.to) < 1.2 * 1.2;
                boolean clear = p.from.kind() == Kind.SHOOTER ? p.outOfSight >= 15 && away > 6.0 : p.outOfSight >= 30 && away > 14.0 || there && away > 10.0;
                if (clear || now - p.since > 20 * 9 && (p.from.kind() == Kind.SHOOTER || away > 8.0)) {
                    hide(c, p, from);
                } else if (now - p.since > 20 && there || now - p.since > 40 && c.getNavigation().isIdle() && (p.from.kind() == Kind.CHASER || from.canSee(c))) {
                    escape(c, p, from);     // got there and it's still on them, or that way's no good: somewhere else
                }
                return true;
            }
            case HIDING -> {
                if (now >= p.until) {
                    panic.remove(id);
                    c.stopAll();
                    return false;       // they think again (the AI picks what to do next)
                }
                if (away < 14.0) {
                    c.lookAt(from.getEyePos());
                }
                return true;
            }
        }
        return false;
    }

    /** How much {@code c} is facing {@code at} (1 straight at it, -1 away): people catch movement out of the corner of an eye. */
    static double facing(LivingEntity c, LivingEntity at) {
        Vec3d to = at.getPos().subtract(c.getPos()).multiply(1.0, 0.0, 1.0);
        if (to.lengthSquared() < 0.01) {
            return 1.0;
        }
        double yaw = Math.toRadians(c.getHeadYaw());
        return to.normalize().dotProduct(new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw)));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void cry(Contestant c, Threat threat, boolean shot) {
        long now = game.world.getTime();
        if (now - criedAt.getOrDefault(c.getUuid(), -1000L) < 120) {
            return;
        }
        criedAt.put(c.getUuid(), now);
        String n = cap(threat.noun());
        boolean gun = threat.kind() == Kind.SHOOTER;
        String line = switch (c.nature) {
            case TIMID -> Lines.pick(game.random, "No, no, no...", n + "... oh god, " + threat.noun() + "'s here.", "Help!");
            case BRAVE -> gun ? Lines.pick(game.random, n + "! Get inside!", "Down! Get down!", "Get out of sight!")
                    : Lines.pick(game.random, n + "! Back off, everyone!", "Go! I'm right behind you!", "Don't stop!");
            case KIND -> gun ? Lines.pick(game.random, n + "! Everyone, get inside!", "Get out of sight!", "Watch out, " + threat.noun() + "'s here!")
                    : Lines.pick(game.random, n + "! Run, everyone!", "Watch out!", "This way, quick!");
            case CLEVER -> gun ? Lines.pick(game.random, n + ". Out of its line of sight, now.", "Get behind a wall.", "Inside. Quietly.")
                    : Lines.pick(game.random, n + ". Don't run straight, go round something.", "Somewhere it can't follow. Now.", "Split up!");
            case SELFISH -> Lines.pick(game.random, "Get out of my way!", n + "!", "Move!");
            case RECKLESS -> Lines.pick(game.random, n + "! Run!", "Go, go, go!", "Run!");
        };
        c.say(shot && gun ? Lines.pick(game.random, "It's shooting! ", "Gun! ", "") + line : line);
    }

    /** Off to the nearest place out of its sight (a gun), or away round things (something that chases). */
    private void escape(Contestant c, Panic p, LivingEntity from) {
        boolean calm = c.feelings.fear < 0.55F && (c.nature == Contestant.Nature.CLEVER || c.nature == Contestant.Nature.BRAVE
                || c.nature == Contestant.Nature.KIND && game.random.nextBoolean());
        p.pace = calm && p.from.kind() == Kind.SHOOTER ? 1.1 : 1.4;
        p.to = p.from.kind() == Kind.SHOOTER ? game.findCover(c, from) : getAway(c, from);
        p.state = Fright.ESCAPING;
        p.since = game.world.getTime();
        p.outOfSight = 0;
        if (p.to == null) {
            // nowhere better within reach: then straight away from it
            Vec3d away = c.getPos().subtract(from.getPos()).multiply(1.0, 0.0, 1.0);
            away = away.lengthSquared() < 0.01 ? new Vec3d(1.0, 0.0, 0.0) : away.normalize();
            p.to = c.getPos().add(away.multiply(10.0));
            p.pace = 1.4;
        }
        c.go(p.to, p.pace);
    }

    /**
     * Somewhere 8 to 16 blocks off that takes them away from something chasing them: not past it, reachable, and
     * better if it can't see the place (round a corner, behind something), or if the game says it's a refuge.
     */
    @Nullable
    private Vec3d getAway(Contestant c, LivingEntity from) {
        Vec3d here = c.getPos();
        Vec3d off = here.subtract(from.getPos()).multiply(1.0, 0.0, 1.0);
        off = off.lengthSquared() < 0.01 ? new Vec3d(1.0, 0.0, 0.0) : off.normalize();
        BlockPos at = c.getBlockPos();
        List<BlockPos> tried = new ArrayList<>();
        List<double[]> scored = new ArrayList<>();      // {score, index into tried, radius}
        for (int k = 0; k < 24; k++) {
            double angle = Math.atan2(off.z, off.x) + (k / 24.0 - 0.5) * Math.PI * 1.3;
            double r = 8.0 + (k % 3) * 4.0;
            BlockPos guess = at.add((int) Math.round(Math.cos(angle) * r), 0, (int) Math.round(Math.sin(angle) * r));
            BlockPos feet = null;
            for (int dy = 0; dy <= 3 && feet == null; dy++) {
                for (int sign : new int[]{1, -1}) {
                    BlockPos tryAt = guess.up(dy * sign);
                    if (game.canStand(tryAt) && !game.forbidden(tryAt.getX(), tryAt.getY(), tryAt.getZ())
                            && !game.deadly(tryAt.getX(), tryAt.getY(), tryAt.getZ())) {
                        feet = tryAt;
                        break;
                    }
                }
            }
            if (feet == null || tried.contains(feet)) {
                continue;
            }
            Vec3d spot = Vec3d.ofBottomCenter(feet);
            Vec3d way = spot.subtract(here).multiply(1.0, 0.0, 1.0);
            double toward = way.normalize().dotProduct(from.getPos().subtract(here).multiply(1.0, 0.0, 1.0).normalize());
            if (toward > 0.3) {
                continue;
            }
            double score = -spot.distanceTo(from.getPos()) + (game.seenFrom(from.getEyePos(), spot.add(0.0, 1.5, 0.0), from) ? 6.0 : 0.0)
                    - (game.refuge(spot) ? 10.0 : 0.0);
            tried.add(feet);
            scored.add(new double[]{score, tried.size() - 1, r});
        }
        // the best few by the look of them, then the way there: no use if it's a long way round
        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < Math.min(5, scored.size()); i++) {
            BlockPos feet = tried.get((int) scored.get(i)[1]);
            net.minecraft.entity.ai.pathing.Path path = c.getNavigation().findPathTo(feet, 1);
            if (path != null && path.getLength() <= scored.get(i)[2] * 2.2) {
                return Vec3d.ofBottomCenter(feet);
            }
        }
        return null;
    }

    /** Clear of it: keep still a while, warn the others where it is, then think again. */
    private void hide(Contestant c, Panic p, LivingEntity from) {
        long now = game.world.getTime();
        p.state = Fright.HIDING;
        boolean gun = p.from.kind() == Kind.SHOOTER;
        p.until = now + 20L * (gun ? 6 + game.random.nextInt(8) : 3 + game.random.nextInt(5)) + (long) (c.feelings.fear * 20 * (gun ? 10 : 4));
        c.hold();
        c.lookAt(from.getEyePos());
        String where = p.from.where() != null ? p.from.where().apply(c) : whereFrom(c, from);
        String noun = p.from.noun();
        c.remember("You got away from " + noun + " and hid. It was " + where + ".");
        boolean warns = c.nature != Contestant.Nature.SELFISH && (c.nature != Contestant.Nature.TIMID || game.random.nextBoolean());
        if (warns && now - criedAt.getOrDefault(c.getUuid(), -1000L) > 60) {
            criedAt.put(c.getUuid(), now);
            String n = cap(noun);
            c.say(Lines.pick(game.random, n + "'s " + where + "!", n + ", " + where + ". Stay away from there.", n + "'s " + where + ". Keep your heads down."));
        }
        game.note(c.name() + " is hiding from " + noun + ". It was last seen " + where + ".", false);
    }

    /** "about 20 metres north of here" */
    private static String whereFrom(LivingEntity c, LivingEntity it) {
        Vec3d d = it.getPos().subtract(c.getPos());
        int metres = (int) Math.round(Math.sqrt(d.x * d.x + d.z * d.z));
        String way = Math.abs(d.x) > Math.abs(d.z) ? d.x > 0 ? "east" : "west" : d.z > 0 ? "south" : "north";
        String level = d.y > 2.5 ? ", above us" : d.y < -2.5 ? ", below us" : "";
        return String.format(Locale.ROOT, "about %d metres %s of here%s", Math.max(2, metres), way, level);
    }

    /** For the AI: what they're doing about it, if anything. */
    @Nullable
    String describe(UUID id) {
        Panic p = panic.get(id);
        if (p == null) {
            return null;
        }
        return switch (p.state) {
            case STARTLED, FROZEN -> "You've just seen " + p.from.noun() + " right there.";
            case ESCAPING -> "You're getting away from " + p.from.noun() + ".";
            case HIDING -> "You've just got away from " + p.from.noun() + " and you're keeping still.";
        };
    }
}
