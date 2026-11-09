package com.deadmanshand.npc;

import com.deadmanshand.Borderland;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Laser;
import com.deadmanshand.Visa;
import com.deadmanshand.game.Game;
import com.deadmanshand.game.Games;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Another player: someone who woke up over there the same night you did. They have a name, a past, a nature and a
 * visa of their own; they remember you, and how you treated them. Between games they wander; when a venue lights up
 * they come.
 */
public class Contestant extends Puppet {
    private static final TrackedData<Integer> SKIN = DataTracker.registerData(Contestant.class, TrackedDataHandlerRegistry.INTEGER);
    /** A body: lies still where it fell (and is drawn lying down). */
    private static final TrackedData<Boolean> BODY = DataTracker.registerData(Contestant.class, TrackedDataHandlerRegistry.BOOLEAN);
    public static final int SKINS = 16;

    public enum Nature {
        BRAVE("brave: you act when others freeze"),
        TIMID("timid: you scare easily and hate deciding"),
        SELFISH("selfish: you look after yourself first, and you're not ashamed of it"),
        KIND("kind: you'd rather save someone than yourself"),
        CLEVER("clever and calm: you think before you move"),
        RECKLESS("reckless: you act first and think later");

        public final String text;

        Nature(String text) {
            this.text = text;
        }
    }

    private static final String[] NAMES = {"Haruto", "Mei", "Daichi", "Sora", "Ren", "Yui", "Kenji", "Aiko", "Riku", "Hana", "Kaito", "Emi",
            "Sho", "Rina", "Jun", "Mika", "Hiro", "Saki", "Tomo", "Nao", "Kei", "Aya", "Yuto", "Miyu", "Marco", "Lena", "Tariq", "Priya",
            "Owen", "Ines", "Dev", "Zara", "Felix", "Noor", "Mateo", "Ivy"};
    private static final String[] PASTS = {"a convenience-store clerk", "a nurse", "a delivery rider", "a university student", "an office worker",
            "a high-school student", "a cook", "a taxi driver", "a programmer", "a hairdresser", "a builder", "a bartender", "a teacher",
            "a waiter", "out of work", "a musician", "a personal trainer", "an accountant", "a security guard", "a shop owner"};

    public Nature nature = Nature.KIND;
    /** How sharp they are, 0 to 1: who solves a riddle, who panics. */
    public float wits = 0.5F;
    public int years = 25;
    public String past = "an office worker";
    public int visa = Visa.DAY * 2;
    public int cleared;
    /** The game they're playing (or waiting for), or -1. */
    public int gameId = -1;
    /** At the camp: "resident", "guard", "leader" or "dealer"; empty for anyone out on the streets. */
    public String role = "";
    /** Where they live (the camp); they don't wander far from it. */
    @Nullable
    public BlockPos home;
    private final Map<UUID, Float> trust = new HashMap<>();
    /** The last player who talked to them, and when: whoever "come with me" means. */
    @Nullable
    private UUID talkedTo;
    private long talkedAt;
    /** Fear, grief, anger, hope: what they're feeling, and what it does to them. */
    public final Feelings feelings = new Feelings();
    private final Deque<String> memories = new ArrayDeque<>();
    private int quietUntil;
    private int hurtNoted = -1000;

    public Contestant(EntityType<? extends Contestant> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(SKIN, 0);
        dataTracker.startTracking(BODY, false);
    }

    public boolean isBody() {
        return dataTracker.get(BODY);
    }

    /** From now on just a body: no will, no voice, lying where it is. */
    public void makeBody() {
        dataTracker.set(BODY, true);
        stopAll();
        setAiDisabled(true);
        setSilent(true);
        setInvulnerable(true);
    }

    public void talkedTo(UUID player, long now) {
        talkedTo = player;
        talkedAt = now;
    }

    /** Whoever talked to them in the last minute, if anyone. */
    @Nullable
    public UUID lastTalkedTo(long now) {
        return talkedTo != null && now - talkedAt < 20 * 60 ? talkedTo : null;
    }

    /** Whether they were talking with {@code id} within the last {@code ticks} (so {@code id} would know their voice). */
    public boolean talkedWith(UUID id, long now, long ticks) {
        return id.equals(talkedTo) && now - talkedAt < ticks;
    }

    /** How sharp they are right now: their wits, clouded by fear and grief, steadied a little by hope. */
    public float judgment() {
        float calm = 1.0F - 0.5F * Math.max(0.0F, feelings.fear - 0.3F) - 0.3F * feelings.grief;
        return Math.max(0.05F, Math.min(1.0F, wits * calm + 0.05F * feelings.hope));
    }

    /** How ready they are to take a risk right now: who they are, then fear holding them back, anger and grief pushing them. */
    public float nerve() {
        float base = switch (nature) {
            case RECKLESS -> 0.85F;
            case BRAVE -> 0.7F;
            case CLEVER -> 0.5F;
            case KIND -> 0.45F;
            case SELFISH -> 0.4F;
            case TIMID -> 0.2F;
        };
        return Math.max(0.0F, Math.min(1.0F, base - 0.5F * feelings.fear + 0.3F * feelings.anger + 0.25F * feelings.grief + 0.1F * feelings.hope));
    }

    /** Frozen with fear this moment: no moving, whatever they were told. */
    @Override
    public boolean paralysed() {
        return feelings.frozen > 0;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !world.isClient && isAlive()) {
            feelings.hurt(this, source, amount);
            Game game = game();
            if (game != null && game.isPlaying(getUuid()) && age - hurtNoted > 60) {
                hurtNoted = age;
                net.minecraft.entity.Entity by = source.getAttacker();
                game.note(name() + " got hurt" + (by != null && by != this ? " by " + by.getName().getString() : "") + ".");
            }
        }
        return hurt;
    }

    public boolean free() {
        return gameId < 0 && !isBody();
    }

    /** No shoving: a crowd that keeps pushing itself about looks broken. (Players can still push past them.) */
    @Override
    protected void tickCramming() {
    }

    @Override
    protected boolean busy() {
        return gameId >= 0 && !isBody();
    }

    @Override
    protected double hurry() {
        return 1.0 + 0.25 * Math.max(0.0F, feelings.fear - 0.5F);
    }

    // --- body language: what their feelings do to them, without their deciding it ------------------------------

    /** Everyone walks at their own pace; grief drags at you, anger hurries you along. */
    @Override
    protected float gait() {
        float own = 0.92F + Math.floorMod(getUuid().hashCode(), 17) / 100.0F;
        return own * (1.0F - 0.25F * feelings.grief) * (1.0F + 0.1F * feelings.anger);
    }

    /** Scared eyes never settle; grieving eyes stay down; calm ones rest on people. */
    @Override
    protected int gazeTicks() {
        Random r = getRandom();
        if (feelings.fear > 0.45F) {
            return 8 + (int) ((1.0F - feelings.fear) * 50.0F) + r.nextInt(20);
        }
        if (feelings.grief > 0.5F) {
            return 80 + r.nextInt(80);
        }
        return 50 + r.nextInt(80);
    }

    @Nullable
    @Override
    protected Vec3d idleGaze() {
        Random r = getRandom();
        double yaw = Math.toRadians(getHeadYaw());
        Vec3d ahead = new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw));
        // grief: on the body, or at the floor in front of you
        if (feelings.grief > 0.5F && r.nextFloat() < feelings.grief * 0.8F) {
            Contestant body = nearestBody(10.0);
            if (body != null && r.nextBoolean()) {
                return body.getPos().add(0.0, 0.3, 0.0);
            }
            return getPos().add(ahead.multiply(1.6)).add((r.nextDouble() - 0.5) * 0.8, 0.1, (r.nextDouble() - 0.5) * 0.8);
        }
        // anger: on whoever you blame, if they're here
        if (feelings.angerAt != null && feelings.anger > 0.4F && r.nextFloat() < 0.7F) {
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(16.0), e -> e != this && e.isAlive())) {
                String n = e instanceof Contestant k ? k.name() : e.getEntityName();
                if (n.equals(feelings.angerAt)) {
                    return e.getEyePos();
                }
            }
        }
        // fear: anywhere it could come from, behind you most of all
        if (feelings.fear > 0.45F && r.nextFloat() < feelings.fear) {
            double a = yaw + Math.PI * (0.4 + r.nextDouble() * 1.2) * (r.nextBoolean() ? 1 : -1);
            return getEyePos().add(-Math.sin(a) * 5.0, (r.nextDouble() - 0.4) * 1.5, Math.cos(a) * 5.0);
        }
        // otherwise people: whoever you were last talking with, or the nearest face
        LivingEntity face = null;
        UUID last = lastTalkedTo(world.getTime());
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(10.0),
                e -> e != this && e.isAlive() && (e instanceof PlayerEntity || e instanceof Contestant k && !k.isBody()))) {
            if (e.getUuid().equals(last)) {
                face = e;
                break;
            }
            if (face == null || e.squaredDistanceTo(this) < face.squaredDistanceTo(this)) {
                face = e;
            }
        }
        if (face != null && r.nextFloat() < 0.7F) {
            return face.getEyePos();
        }
        double a = yaw + (r.nextDouble() - 0.5) * Math.PI * 1.4;
        return getEyePos().add(-Math.sin(a) * 4.0, (r.nextDouble() - 0.6) * 0.8, Math.cos(a) * 4.0);
    }

    /** Grief, standing by a body: down on one knee beside it. */
    @Override
    protected boolean kneels() {
        return feelings.grief > 0.7F && nearestBody(3.0) != null;
    }

    @Nullable
    private Contestant nearestBody(double range) {
        Contestant best = null;
        for (Contestant c : world.getEntitiesByClass(Contestant.class, getBoundingBox().expand(range), Contestant::isBody)) {
            if (best == null || c.squaredDistanceTo(this) < best.squaredDistanceTo(this)) {
                best = c;
            }
        }
        return best;
    }

    @Override
    protected void idleGoals() {
        goalSelector.add(5, new WanderAroundFarGoal(this, 0.6));
        goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 10.0F));
        goalSelector.add(7, new LookAroundGoal(this));
    }

    /** Left lying where someone died: their look and name, nothing else. */
    public static void corpse(ServerWorld world, LivingEntity dead) {
        Contestant body = DeadMansHand.CONTESTANT.create(world);
        if (body == null) {
            return;
        }
        if (dead instanceof Contestant c) {
            NbtCompound nbt = new NbtCompound();
            c.writeCustomDataToNbt(nbt);
            nbt.remove("Health");
            nbt.remove("DeathTime");
            nbt.remove("ActiveEffects");
            body.readCustomDataFromNbt(nbt);
            body.setHealth(body.getMaxHealth());
            body.deathTime = 0;
        } else {
            body.randomize(world.random);
            body.dataTracker.set(SKIN, Math.floorMod(dead.getEntityName().hashCode(), SKINS));
            body.setCustomName(Text.literal(dead.getEntityName()));
        }
        body.role = "body";
        body.setCustomNameVisible(false);
        body.refreshPositionAndAngles(dead.getX(), dead.getY(), dead.getZ(), dead.getYaw(), 0.0F);
        body.makeBody();
        world.spawnEntity(body);
        Remains.left(body);
    }

    public int skin() {
        return Math.floorMod(dataTracker.get(SKIN), SKINS);
    }

    @Nullable
    public static Contestant spawn(ServerWorld world, BlockPos pos, Random random) {
        return spawn(world, pos, random, "", null);
    }

    /** Someone new at {@code pos}; with a role and a home if they belong somewhere (the camp). */
    @Nullable
    public static Contestant spawn(ServerWorld world, BlockPos pos, Random random, String role, @Nullable BlockPos home) {
        Contestant contestant = DeadMansHand.CONTESTANT.create(world);
        if (contestant == null) {
            return null;
        }
        contestant.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        contestant.randomize(random);
        contestant.role = role;
        contestant.home = home;
        world.spawnEntity(contestant);
        return contestant;
    }

    private void randomize(Random random) {
        dataTracker.set(SKIN, random.nextInt(SKINS));
        setCustomName(Text.literal(NAMES[random.nextInt(NAMES.length)]));
        nature = Nature.values()[random.nextInt(Nature.values().length)];
        wits = 0.15F + random.nextFloat() * 0.8F;
        if (nature == Nature.CLEVER) {
            wits = Math.max(wits, 0.75F);
        } else if (nature == Nature.RECKLESS) {
            wits = Math.min(wits, 0.5F);
        }
        years = 17 + random.nextInt(40);
        past = PASTS[random.nextInt(PASTS.length)];
        visa = Visa.DAY + random.nextInt(Visa.DAY * 3);
    }

    public String name() {
        Text name = getCustomName();
        return name == null ? "Stranger" : name.getString();
    }

    public float trust(UUID player) {
        return trust.getOrDefault(player, 0.0F);
    }

    public void trust(UUID player, float change) {
        trust.put(player, Math.max(-1.0F, Math.min(1.0F, trust(player) + change)));
    }

    public void remember(String memory) {
        String m = memory.toLowerCase();
        if (m.contains("lied") || m.contains("betray") || m.contains("tricked") || m.contains("set you up")) {
            feelings.betrayed(this, null);
        }
        memories.addLast(memory);
        while (memories.size() > 8) {
            memories.pollFirst();
        }
    }

    public Iterable<String> memories() {
        return memories;
    }

    @Nullable
    public Game game() {
        return gameId < 0 ? null : Games.byId(gameId);
    }

    /** Says it out loud: everyone within earshot reads it in chat. */
    public void say(String line) {
        if (!(world instanceof ServerWorld server) || line.isBlank()) {
            return;
        }
        Text text = Text.literal("<" + name() + "> ").formatted(Formatting.WHITE).append(Text.literal(line).formatted(Formatting.WHITE));
        for (ServerPlayerEntity player : server.getPlayers()) {
            if (player.squaredDistanceTo(this) < 30.0 * 30.0) {
                player.sendMessage(text, false);
            }
        }
        quietUntil = age + 60;
        // heads turn to whoever's speaking
        for (Puppet other : server.getEntitiesByClass(Puppet.class, getBoundingBox().expand(12.0), e -> e != this && e.isAlive())) {
            if (!(other instanceof Contestant c && c.isBody())) {
                other.notice(this, 30 + getRandom().nextInt(30));
            }
        }
        Game game = game();
        if (game != null && game.isPlaying(getUuid())) {
            game.note(name() + " said: " + line, false);
            game.saidBy(this, line);
        }
    }

    /** A line for this moment: the AI writes it if there is one, otherwise {@code fallback}. */
    public void speak(String moment, String fallback) {
        if (age < quietUntil || !isAlive()) {
            return;
        }
        quietUntil = age + 100;
        Voice.line(this, moment, fallback);
    }

    public boolean quiet() {
        return age >= quietUntil;
    }

    /** The stranger you're talking to: the one you're facing, or failing that the nearest. */
    @Nullable
    /** Everyone within {@code range} whose name is in {@code message}, nearest first. */
    public static List<Contestant> named(ServerPlayerEntity player, String message, double range) {
        String lower = " " + message.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        List<Contestant> list = new ArrayList<>();
        for (Contestant c : player.world.getEntitiesByClass(Contestant.class, player.getBoundingBox().expand(range), k -> k.isAlive() && !k.isBody())) {
            String first = c.name().toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")[0];
            if (first.length() >= 2 && lower.contains(" " + first + " ")) {
                list.add(c);
            }
        }
        list.sort((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)));
        return list;
    }

    /** The {@code count} nearest within {@code range}. */
    public static List<Contestant> nearest(ServerPlayerEntity player, double range, int count) {
        List<Contestant> list = new ArrayList<>(player.world.getEntitiesByClass(Contestant.class, player.getBoundingBox().expand(range),
                k -> k.isAlive() && !k.isBody()));
        list.sort((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)));
        return list.subList(0, Math.min(count, list.size()));
    }

    public static Contestant listening(ServerPlayerEntity player, double range) {
        Contestant best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        for (Contestant contestant : player.world.getEntitiesByClass(Contestant.class, player.getBoundingBox().expand(range), k -> k.isAlive() && !k.isBody())) {
            Vec3d to = contestant.getEyePos().subtract(eye);
            double distance = to.length();
            double facing = distance < 0.01 ? 1.0 : look.dotProduct(to.multiply(1.0 / distance));
            double score = distance * (facing > 0.85 ? 0.4 : facing > 0.3 ? 1.0 : 3.0);
            if (score < bestScore) {
                best = contestant;
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public void tick() {
        super.tick();
        if (world.isClient || !isAlive()) {
            return;
        }
        if (isBody()) {
            return;
        }
        feelings.tick(this);
        if (Borderland.is(world) && --visa <= 0) {
            visa = 0;
            if (gameId < 0) {
                Laser.strike(this, null);      // somewhere out there, someone's visa ran out
            }
        }
        if (gameId >= 0 && age % 40 == 0 && game() == null) {
            gameId = -1;
            stopAll();
        }
        // People with a home stay near it between games
        if (home != null && gameId < 0 && age % 60 == 0 && goal() == null && squaredDistanceTo(Vec3d.ofBottomCenter(home)) > 26.0 * 26.0) {
            go(home.add(random.nextInt(13) - 6, 0, random.nextInt(13) - 6), 0.8);
        }
        if (home != null && gameId < 0 && goal() != null && arrived(1.5)) {
            stopAll();
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Skin", skin());
        nbt.putString("Nature", nature.name());
        nbt.putFloat("Wits", wits);
        nbt.putInt("Years", years);
        nbt.putString("Past", past);
        nbt.putInt("Visa", visa);
        nbt.putInt("Cleared", cleared);
        nbt.putString("Role", role);
        nbt.putBoolean("Body", isBody());
        if (home != null) {
            nbt.putIntArray("Home", new int[]{home.getX(), home.getY(), home.getZ()});
        }
        NbtCompound trusts = new NbtCompound();
        for (Map.Entry<UUID, Float> entry : trust.entrySet()) {
            trusts.putFloat(entry.getKey().toString(), entry.getValue());
        }
        nbt.put("Trust", trusts);
        NbtList list = new NbtList();
        for (String memory : memories) {
            list.add(NbtString.of(memory));
        }
        nbt.put("Memories", list);
        feelings.save(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(SKIN, nbt.getInt("Skin"));
        try {
            nature = Nature.valueOf(nbt.getString("Nature"));
        } catch (IllegalArgumentException e) {
            nature = Nature.KIND;
        }
        wits = nbt.contains("Wits") ? nbt.getFloat("Wits") : 0.5F;
        years = nbt.contains("Years") ? nbt.getInt("Years") : 25;
        past = nbt.contains("Past") ? nbt.getString("Past") : past;
        visa = nbt.contains("Visa") ? nbt.getInt("Visa") : Visa.DAY;
        cleared = nbt.getInt("Cleared");
        role = nbt.getString("Role");
        int[] at = nbt.getIntArray("Home");
        home = at.length == 3 ? new BlockPos(at[0], at[1], at[2]) : null;
        if (nbt.getBoolean("Body")) {
            dataTracker.set(BODY, true);
        }
        NbtCompound trusts = nbt.getCompound("Trust");
        for (String key : trusts.getKeys()) {
            try {
                trust.put(UUID.fromString(key), trusts.getFloat(key));
            } catch (IllegalArgumentException ignored) {
                // not an id
            }
        }
        NbtList list = nbt.getList("Memories", NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            memories.addLast(list.getString(i));
        }
        feelings.load(nbt, nature);
        gameId = -1;    // games don't survive a restart
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_PLAYER_DEATH;
    }
}
