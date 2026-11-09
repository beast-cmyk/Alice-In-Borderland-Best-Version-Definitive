package com.deadmanshand.npc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Someone a game can direct: walk there, look at that, stand still. The games decide; this does it. When nobody is
 * giving orders, its own idle goals take over.
 */
public abstract class Puppet extends PathAwareEntity {
    @Nullable
    Vec3d goal;
    double speed = 1.0;
    @Nullable
    Entity lookEntity;
    @Nullable
    Vec3d lookPoint;
    boolean frozen;
    /** When a glance ends (MAX_VALUE: it doesn't). */
    long lookUntil = Long.MAX_VALUE;

    // --- the body: what a person does without thinking about it -----------------------------------------------

    /** Someone (or something) it has just noticed, a voice or a scream: it turns to look, after a beat, for a moment. */
    @Nullable
    private Entity noticed;
    @Nullable
    private Vec3d noticedAt;
    private long noticeFrom;
    private long noticeUntil;
    /** Where something terrible just happened, and until when it flinches away from it. */
    @Nullable
    private Vec3d startledBy;
    private long startledUntil;
    /** Where its eyes wander when nothing in particular holds them, and when they'll wander next. */
    @Nullable
    private Vec3d idleGaze;
    private long idleUntil;
    /** Kneeling (checked once a second). */
    private boolean kneelNow;

    /** Turn to look at {@code who} (they spoke, they screamed) for a moment, after the beat it takes a person to react. */
    public void notice(Entity who, int ticks) {
        long now = world.getTime();
        if (who == this || now < noticeUntil && noticed == who) {
            return;
        }
        noticed = who;
        noticedAt = null;
        noticeFrom = now + 3 + getRandom().nextInt(7);
        noticeUntil = noticeFrom + ticks;
    }

    /** Turn to look at a place (a bang, a body falling). */
    public void notice(Vec3d where, int ticks) {
        long now = world.getTime();
        noticed = null;
        noticedAt = where;
        noticeFrom = now + 2 + getRandom().nextInt(5);
        noticeUntil = noticeFrom + ticks;
    }

    /** Something terrible happened at {@code where}: flinch away from it, crouching, and stare. */
    public void startle(Vec3d where) {
        startledBy = where;
        startledUntil = world.getTime() + 14 + getRandom().nextInt(8);
        notice(where, 50);
    }

    /** How fast this person walks, as a share of what they're told: everyone's a little different, and grief slows you. */
    protected float gait() {
        return 1.0F;
    }

    /**
     * Where its eyes go when nothing holds them: the nearest person, mostly. People who feel things look about
     * differently (Contestants override this). Null: keep looking where it was.
     */
    @Nullable
    protected Vec3d idleGaze() {
        Entity near = world.getClosestPlayer(this, 8.0);
        if (near != null && getRandom().nextFloat() < 0.6F) {
            return near.getEyePos();
        }
        double a = getRandom().nextDouble() * Math.PI * 2.0;
        return getEyePos().add(Math.cos(a) * 4.0, -0.3 + getRandom().nextDouble() * 0.6, Math.sin(a) * 4.0);
    }

    /** Down on one knee, standing still (grief). */
    protected boolean kneels() {
        return false;
    }

    /** How long, in ticks, its eyes rest on something before they move on. */
    protected int gazeTicks() {
        return 50 + getRandom().nextInt(70);
    }

    protected Puppet(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.moveControl = new Stride(this);
        if (getNavigation() instanceof MobNavigation navigation) {
            navigation.setCanPathThroughDoors(true);
            navigation.setCanEnterOpenDoors(true);
        }
        setPersistent();
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new Orders(this));
        idleGoals();
    }

    /** What it does when nobody is directing it. */
    protected void idleGoals() {
    }

    /** In the middle of something (a game): no wandering off or gazing about when there are no orders; stand and watch. */
    protected boolean busy() {
        return false;
    }

    /** Told to stand still (by the game) right now. */
    public boolean held() {
        return frozen;
    }

    /** Too frightened to move, this moment. */
    public boolean paralysed() {
        return false;
    }

    /** How much faster than told it goes (fear makes people run). */
    protected double hurry() {
        return 1.0;
    }

    public void go(BlockPos pos, double speed) {
        go(Vec3d.ofBottomCenter(pos), speed);
    }

    public void go(Vec3d target, double speed) {
        this.speed = speed;
        frozen = false;
        if (asked != null && asked.squaredDistanceTo(target) < 0.3 * 0.3 && goal != null) {
            return;     // the same place as before: keep the spot already picked for it
        }
        asked = target;
        goal = space(target);
    }

    /** Where the game asked it to go (before making room for anyone already there). */
    @Nullable
    private Vec3d asked;

    /**
     * People don't walk into the exact spot someone else is standing on (or heading for): if it's taken, the nearest
     * free place to stand beside it.
     */
    private Vec3d space(Vec3d target) {
        if (!taken(target)) {
            return target;
        }
        BlockPos at = new BlockPos((int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z));
        for (double r : new double[]{0.9, 1.3}) {
            for (int k = 0; k < 8; k++) {
                double a = (k + (r > 1 ? 0.5 : 0.0)) * Math.PI / 4.0;
                Vec3d spot = target.add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
                BlockPos feet = new BlockPos((int) Math.floor(spot.x), (int) Math.floor(target.y + 0.01), (int) Math.floor(spot.z));
                if (feet.getY() == at.getY() && standable(feet) && !taken(spot)) {
                    return spot;
                }
            }
        }
        return target;
    }

    private boolean taken(Vec3d spot) {
        for (Puppet other : world.getEntitiesByClass(Puppet.class, new net.minecraft.util.math.Box(spot, spot).expand(1.5, 1.5, 1.5), e -> e != this && e.isAlive())) {
            Vec3d theirs = other.goal != null ? other.goal : other.getPos();
            double dx = theirs.x - spot.x;
            double dz = theirs.z - spot.z;
            if (dx * dx + dz * dz < 0.75 * 0.75 && Math.abs(theirs.y - spot.y) < 1.0) {
                return true;
            }
        }
        return false;
    }

    private boolean standable(BlockPos feet) {
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty();
    }

    /** Look at someone (into their eyes, if they look back). */
    public void look(@Nullable Entity entity) {
        lookEntity = entity;
        lookPoint = null;
        lookUntil = Long.MAX_VALUE;
    }

    /** Look at someone for a moment (whoever's talking to you), then go back to what you were looking at. */
    public void glance(Entity entity, int ticks) {
        if (lookEntity == null && lookPoint == null || lookUntil != Long.MAX_VALUE) {
            lookEntity = entity;
            lookPoint = null;
            lookUntil = world.getTime() + ticks;
        }
    }

    public void lookAt(Vec3d point) {
        lookPoint = point;
        lookEntity = null;
        lookUntil = Long.MAX_VALUE;
    }

    /** Stand still where you are. */
    public void hold() {
        goal = null;
        asked = null;
        frozen = true;
        getNavigation().stop();
    }

    public void stopAll() {
        goal = null;
        asked = null;
        frozen = false;
        lookEntity = null;
        lookPoint = null;
        getNavigation().stop();
    }

    public boolean arrived(double within) {
        return goal == null || squaredDistanceTo(goal) < within * within;
    }

    @Nullable
    public Vec3d goal() {
        return goal;
    }

    /** Where it's really looking: its head, not its body. */
    public static Vec3d gaze(Entity entity) {
        float yaw = entity instanceof net.minecraft.entity.LivingEntity living ? living.getHeadYaw() : entity.getYaw();
        return Vec3d.fromPolar(entity.getPitch(), yaw);
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    /**
     * Walking like a person: turning into a new direction instead of snapping round to it, slowing for a sharp turn,
     * easing into a walk from standing, and at their own pace.
     */
    static final class Stride extends MoveControl {
        private final Puppet mob;
        /** How far into its stride it is, from standing (0) to full pace (1). */
        private float pace;

        Stride(Puppet mob) {
            super(mob);
            this.mob = mob;
        }

        @Override
        public void tick() {
            boolean moving = this.state == State.MOVE_TO;
            float before = mob.getYaw();
            super.tick();
            if (!moving) {
                pace = Math.max(0.0F, pace - 0.3F);
                return;
            }
            float diff = MathHelper.wrapDegrees(mob.getYaw() - before);
            float rate = mob.isSprinting() ? 24.0F : 16.0F;
            float turn = MathHelper.clamp(diff, -rate, rate);
            mob.setYaw(before + turn);
            float left = Math.abs(diff - turn);       // still to turn after this tick
            float facing = left > 70.0F ? 0.15F : left > 35.0F ? 0.55F : 1.0F;
            pace = Math.min(1.0F, pace + 0.18F);
            float full = (float) (this.speed * mob.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED));     // what it just set
            mob.setMovementSpeed(full * Math.min(Math.max(pace, 0.25F), facing) * mob.gait());
        }
    }

    private static final class Orders extends Goal {
        private final Puppet mob;
        private int repath;
        /** Where it was a second ago, and how many seconds it's been getting nowhere. */
        private Vec3d was = Vec3d.ZERO;
        private int stuck;
        private int check;
        /** Times it's got stuck on the way to this goal; and whether it's stopped trying (close enough, or no way there). */
        private int swaps;
        private boolean gaveUp;
        /** Walking straight at the goal instead of following a path (there wasn't one, or the path got it stuck). */
        private boolean direct;
        @Nullable
        private Vec3d lastGoal;

        Orders(Puppet mob) {
            this.mob = mob;
            setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            return mob.goal != null || mob.frozen || mob.lookEntity != null || mob.lookPoint != null || mob.busy();
        }

        @Override
        public boolean shouldContinue() {
            return canStart();
        }

        @Override
        public boolean shouldRunEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            direct = false;
            stuck = 0;
        }

        @Override
        public void tick() {
            // run like you mean it, creep when you're being careful, stand up straight otherwise
            boolean moving = mob.goal != null && !mob.frozen && !mob.paralysed() && mob.getVelocity().horizontalLengthSquared() > 0.0004;
            double pace = mob.speed * mob.hurry();
            long now = mob.world.getTime();
            boolean flinching = mob.startledBy != null && now < mob.startledUntil;
            mob.setSprinting(moving && pace >= 1.2 && !flinching);
            mob.setSneaking(moving && pace <= 0.75 || flinching || !moving && mob.goal == null && (mob.age % 20 == 0 ? mob.kneelNow = mob.kneels() : mob.kneelNow));
            boolean walking = false;
            if (flinching && !mob.frozen && now < mob.startledUntil - 6) {
                // a step or two back from it, without thinking
                Vec3d away = mob.getPos().subtract(mob.startledBy).multiply(1.0, 0.0, 1.0);
                if (away.lengthSquared() > 0.01 && away.lengthSquared() < 10.0 * 10.0) {
                    // backing off while still facing it: a push backwards, not a turn and walk away
                    Vec3d dir = away.normalize();
                    mob.getNavigation().stop();
                    if (clearTo(mob.getPos().add(dir.multiply(1.2))) && mob.isOnGround()) {
                        mob.setVelocity(mob.getVelocity().add(dir.x * 0.07, 0.0, dir.z * 0.07));
                    }
                }
                look(false);
                return;
            }
            if (mob.frozen || mob.paralysed()) {
                mob.getNavigation().stop();
                direct = false;
                stuck = 0;
            } else if (mob.goal != null) {
                Vec3d goal = mob.goal;
                if (lastGoal == null || lastGoal.squaredDistanceTo(goal) > 1.0) {
                    // a new place to go: start over with the pathfinder
                    lastGoal = goal;
                    direct = false;
                    stuck = 0;
                    swaps = 0;
                    gaveUp = false;
                    repath = 0;
                    mob.getNavigation().stop();
                }
                double dx = goal.x - mob.getX();
                double dz = goal.z - mob.getZ();
                double flat = dx * dx + dz * dz;
                double dy = goal.y - mob.getY();
                if (gaveUp || flat < 0.55 * 0.55 && Math.abs(dy) < 1.3 || flat < 0.3 * 0.3) {
                    // there (or as close as it can get, or right above or below it): stop, and stay stopped. Walking at
                    // a point straight above you is what makes a mob spin on the spot.
                    mob.getNavigation().stop();
                    direct = false;
                    stuck = 0;
                } else {
                    walking = true;
                    // the last few steps, in plain sight on the same level: just walk there. The pathfinder stops a
                    // block short, and waiting for it to notice is the pause-then-shuffle.
                    boolean finalSteps = flat < 2.5 * 2.5 && Math.abs(dy) < 0.9 && clearTo(goal);
                    if (finalSteps && !direct) {
                        direct = true;
                        mob.getNavigation().stop();
                    }
                    // a path is only worked out again when the last one's finished or failed (or the goal moved):
                    // throwing away a good path every second is what makes them stutter
                    if (!direct && --repath <= 0 && (mob.getNavigation().isIdle() || repath < -200)) {
                        repath = 20;
                        boolean found = mob.getNavigation().startMovingTo(goal.x, goal.y, goal.z, mob.speed * mob.hurry());
                        if (!found && flat < 12 * 12 && clearTo(goal)) {
                            direct = true;      // no path to it, but it's close and nothing's in the way: walk straight there
                        }
                    }
                    if (++check >= 20) {
                        check = 0;
                        stuck = mob.getPos().squaredDistanceTo(was) < 0.3 * 0.3 ? stuck + 1 : 0;
                        was = mob.getPos();
                    }
                    if (stuck >= 2) {
                        // two seconds getting nowhere
                        stuck = 0;
                        swaps++;
                        if (flat < 3.0 * 3.0 || swaps >= 4) {
                            // close enough, or it just can't be reached from here: stand still rather than shuffle at it
                            gaveUp = true;
                            if (flat >= 3.0 * 3.0) {
                                Diary.write(mob.getName().getString() + " couldn't get to " + (int) goal.x + " " + (int) goal.y + " " + (int) goal.z
                                        + " (" + (int) Math.sqrt(flat) + " m away) and stopped trying");
                            }
                            mob.getNavigation().stop();
                            direct = false;
                        } else {
                            direct = !direct && clearTo(goal);
                            mob.getNavigation().stop();
                            repath = 0;
                        }
                    }
                    if (direct && !gaveUp) {
                        double close = Math.sqrt(flat);
                        mob.getMoveControl().moveTo(goal.x, goal.y, goal.z, (close < 1.2 ? mob.speed * 0.6 : mob.speed) * mob.hurry());
                        if (mob.horizontalCollision && mob.isOnGround() && dy > 0.4) {
                            mob.getJumpControl().setActive();
                        }
                    }
                }
            }
            if (mob.goal == null && !mob.frozen) {
                mob.getNavigation().stop();     // busy with no orders: stand where you are
            }
            if (mob.lookUntil != Long.MAX_VALUE && mob.world.getTime() > mob.lookUntil) {
                mob.lookEntity = null;
                mob.lookUntil = Long.MAX_VALUE;
            }
            look(walking);
        }

        /** People look where they're going while they walk (a quick glance at someone near and ahead, at most); where they mean to look once they're there. */
        private void look(boolean walking) {
            long now = mob.world.getTime();
            if (mob.startledBy != null && now < mob.startledUntil) {
                mob.getLookControl().lookAt(mob.startledBy.x, mob.startledBy.y + 0.5, mob.startledBy.z, 40.0F, 40.0F);
                return;
            }
            if (now >= mob.noticeFrom && now < mob.noticeUntil) {
                // turning to whoever spoke, or to the noise: quick, the way a head snaps round
                if (mob.noticed != null && mob.noticed.isAlive() && mob.noticed.squaredDistanceTo(mob) < 24.0 * 24.0) {
                    mob.getLookControl().lookAt(mob.noticed, 30.0F, 30.0F);
                    return;
                }
                if (mob.noticedAt != null) {
                    mob.getLookControl().lookAt(mob.noticedAt.x, mob.noticedAt.y, mob.noticedAt.z, 30.0F, 30.0F);
                    return;
                }
            }
            if (mob.paralysed()) {
                // frozen with fear: eyes darting, never resting
                if (now % 4 == 0) {
                    double a = mob.getRandom().nextDouble() * Math.PI * 2.0;
                    mob.idleGaze = mob.getEyePos().add(Math.cos(a) * 3.0, mob.getRandom().nextDouble() - 0.5, Math.sin(a) * 3.0);
                }
                if (mob.idleGaze != null) {
                    mob.getLookControl().lookAt(mob.idleGaze.x, mob.idleGaze.y, mob.idleGaze.z, 25.0F, 25.0F);
                }
                return;
            }
            if (walking) {
                Vec3d ahead = mob.goal;
                net.minecraft.entity.ai.pathing.Path path = mob.getNavigation().getCurrentPath();
                if (!direct && path != null && !path.isFinished()) {
                    ahead = path.getNodePosition(mob);
                }
                Entity glance = mob.lookEntity;
                if (glance != null && glance.isAlive() && mob.squaredDistanceTo(glance) < 7.0 * 7.0 && ahead != null) {
                    Vec3d to = glance.getPos().subtract(mob.getPos());
                    Vec3d way = ahead.subtract(mob.getPos());
                    double dot = to.x * way.x + to.z * way.z;
                    double len = Math.sqrt((to.x * to.x + to.z * to.z) * (way.x * way.x + way.z * way.z));
                    if (len > 0.0001 && dot / len > 0.5) {
                        mob.getLookControl().lookAt(glance, 20.0F, 20.0F);     // they're roughly ahead: look at them
                        return;
                    }
                }
                if (ahead != null && ahead.squaredDistanceTo(mob.getPos()) > 0.25) {
                    mob.getLookControl().lookAt(ahead.x, mob.getEyeY(), ahead.z, 20.0F, 20.0F);
                }
                return;
            }
            if (mob.lookEntity != null && mob.lookEntity.isAlive()) {
                mob.getLookControl().lookAt(mob.lookEntity, 20.0F, 20.0F);
            } else if (mob.lookPoint != null) {
                mob.getLookControl().lookAt(mob.lookPoint.x, mob.lookPoint.y, mob.lookPoint.z, 20.0F, 20.0F);
            } else if (mob.goal == null || gaveUp || mob.goal.squaredDistanceTo(mob.getPos()) < 1.0) {
                // nobody to look at in particular: the eyes wander, the way they do for that person, right now
                if (now >= mob.idleUntil || mob.idleGaze == null) {
                    mob.idleUntil = now + mob.gazeTicks();
                    Vec3d next = mob.idleGaze();
                    if (next != null) {
                        mob.idleGaze = next;
                    }
                }
                if (mob.idleGaze != null) {
                    mob.getLookControl().lookAt(mob.idleGaze.x, mob.idleGaze.y, mob.idleGaze.z, 10.0F, 10.0F);
                }
            }
        }

        /** Nothing solid between here and there, at knee height and at head height, and floor all the way (no walking off an edge). */
        private boolean clearTo(Vec3d goal) {
            for (double t = 0.25; t <= 1.0; t += 0.25) {
                double x = mob.getX() + (goal.x - mob.getX()) * t;
                double z = mob.getZ() + (goal.z - mob.getZ()) * t;
                BlockPos under = new BlockPos((int) Math.floor(x), (int) Math.floor(mob.getY() - 0.5), (int) Math.floor(z));
                if (mob.world.getBlockState(under).getCollisionShape(mob.world, under).isEmpty()) {
                    return false;
                }
            }
            for (double h : new double[]{0.5, 1.5}) {
                Vec3d from = mob.getPos().add(0.0, h, 0.0);
                Vec3d to = new Vec3d(goal.x, mob.getY() + h, goal.z);
                net.minecraft.util.hit.HitResult hit = mob.world.raycast(new net.minecraft.world.RaycastContext(from, to,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, mob));
                if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                    return false;
                }
            }
            return true;
        }
    }
}
