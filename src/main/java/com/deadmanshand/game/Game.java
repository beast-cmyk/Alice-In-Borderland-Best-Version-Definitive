package com.deadmanshand.game;

import com.deadmanshand.Borderland;
import com.deadmanshand.Builder;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Hud;
import com.deadmanshand.Laser;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Lines;
import com.deadmanshand.npc.Mind;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One game, from the moment its venue lights up to the last body. The venue goes up; you step in to register; entry
 * closes a minute after the first player; the rules are read out; the game runs to its time limit; whoever cleared it
 * gets the card and the days. Leave the venue while it's running and the laser finds you. Server thread only.
 */
public abstract class Game {
    public enum Phase {BUILDING, OPEN, BRIEFING, RUNNING, ENDED}

    public enum Fate {PLAYING, CLEARED, DEAD}

    /** Entry closes this long after the first player registers. */
    protected static final int ENTRY_TICKS = 20 * 60;
    private static int counter;

    public final int id = ++counter;
    public final Kind kind;
    public final ServerWorld world;
    /** Where the venue stands: its entrance, at ground level. */
    public final BlockPos origin;
    protected final Random random;
    protected final Builder builder;
    /** The venue. Leave it while the game is on and you're lasered. */
    protected Box bounds;
    /** Step in here to register. */
    protected Box entry;
    /** The middle of the entry, where the strangers walk to. */
    protected BlockPos entryPos;
    /** Where new strangers turn up, a little way off. */
    protected BlockPos approach;
    public Phase phase = Phase.BUILDING;
    protected int phaseTicks;
    protected int timeLeft;
    private int entryLeft = -1;
    boolean closed;
    private boolean planned;
    protected final LinkedHashMap<UUID, Fate> seats = new LinkedHashMap<>();
    protected final Set<UUID> players = new HashSet<>();
    protected final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, GameMode> modes = new HashMap<>();
    private final List<UUID> recruits = new ArrayList<>();
    @Nullable
    private ServerBossBar bar;

    protected Game(Kind kind, ServerWorld world, BlockPos origin) {
        this.kind = kind;
        this.world = world;
        this.origin = origin;
        this.random = world.random;
        this.builder = new Builder(world);
    }

    // --- what each game says ----------------------------------------------------------------------------------

    /** Sets {@link #bounds}, {@link #entry}, {@link #entryPos} and {@link #approach}, and queues the venue's blocks. */
    protected abstract void plan(Builder b);

    /** The rules, as the announcer reads them (and the phone shows them). */
    protected abstract String[] rules();

    /** Entry is closed: put everyone where they start. */
    protected abstract void arrange();

    /** The game starts. */
    protected abstract void begin();

    /** Every tick while it runs. */
    protected abstract void running();

    /** Time limit, in ticks. */
    protected abstract int timeLimit();

    /** Time ran out: by default, anyone who hasn't cleared dies. */
    protected void timeUp() {
        for (UUID id : playing()) {
            fail(id, "Time's up.");
        }
    }

    /** For the AI: what's going on in this game right now, from {@code who}'s point of view. */
    public abstract String situation(Contestant who);

    /** A player used a block inside the venue. */
    protected ActionResult use(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        return ActionResult.PASS;
    }

    /** A player said something nearby. */
    protected void heard(ServerPlayerEntity player, String message) {
    }

    /** A player playing used one of the game's items (a match, a slip...). True if the game took it. */
    protected boolean usedItem(ServerPlayerEntity player, ItemStack stack) {
        return false;
    }

    /** A player playing right-clicked someone. True if the game took it. */
    protected boolean touched(ServerPlayerEntity player, Entity target) {
        return false;
    }

    /** A player playing hit someone. True to cancel the hit. */
    protected boolean attacked(ServerPlayerEntity player, Entity target) {
        return false;
    }

    /** A player typed /answer while playing. */
    protected void answered(ServerPlayerEntity player, String answer) {
        player.sendMessage(Text.literal("There's nothing to answer in this game.").formatted(Formatting.GRAY), false);
    }

    /** Extra lines for the phone on screen. */
    protected void more(ServerPlayerEntity player, Hud hud) {
    }

    /** Someone playing died (any cause). */
    protected void died(UUID id) {
    }

    /** The camp's own game: its people play it. */
    protected boolean campGame() {
        return false;
    }

    /** Strangers turn up to fill the empty places when entry closes. */
    protected boolean fillSeats() {
        return true;
    }

    /** Entry closes the moment the first player is in (a game that springs on you). */
    protected boolean instantEntry() {
        return false;
    }

    /** Every tick while the rules are being read. */
    protected void briefingTick(int t) {
    }

    /** It's over: tidy up (dealers, props). */
    protected void ended() {
    }

    // --- what strangers can decide to do, so what they say and what they do are the same thing ----------------

    /**
     * What {@code who} could decide to do right now, for the AI that speaks for them: a tag (one word, capitals) and what it
     * means. When their line ends with one of these tags they really do it. Empty when there's nothing to choose.
     */
    public Map<String, String> options(Contestant who) {
        return Map.of();
    }

    /**
     * Ways of saying each option in plain words (regular expressions, lower case), for when a line commits to one without
     * its tag: "I'm taking the die door".
     */
    protected Map<String, String> optionWords(Contestant who) {
        return Map.of();
    }

    /** {@code who} has said they'll do {@code option}: now they do it. */
    public void chose(Contestant who, String option) {
    }

    private static final java.util.regex.Pattern NEGATED = java.util.regex.Pattern.compile(
            "\\b(not|never|don'?t|won'?t|can'?t|shouldn'?t|wouldn'?t|isn'?t|no way|avoid|stay away|anything but|except)\\b");
    private static final java.util.regex.Pattern DOING = java.util.regex.Pattern.compile(
            "\\b(go|going|goes|gonna|take|taking|try|trying|open|opening|pick|picking|choose|choosing|chose|head|heading|run|running|"
                    + "through|i'?ll|let'?s|follow|following|use|using|answer|answering|vote|voting|come|coming|stay|staying|wait|waiting|stick|"
                    + "strike|striking|light|press|pressing|fight|fighting|hide|hiding|grab|get)\\b");

    /**
     * Which option {@code said} commits to, if exactly one: the clause has to mention the option and something being done
     * about it, and mustn't be a "not".
     */
    @Nullable
    public final String understood(Contestant who, String said) {
        Map<String, String> words = new LinkedHashMap<>(optionWords(who));
        if (followable() && isPlaying(who.getUuid())) {
            words.putIfAbsent("FOLLOW", "\\b(follow you|with you|right behind you|lead the way|i'?ll follow|after you|follow me|come with me|"
                    + "stick with me|stay with me|this way|(i'?m|i am) coming|on my way|coming to you|coming over|be right there|"
                    + "i'?ll come|heading (to|over to|your way)|coming your way|i'?ll find you|i'?ll meet you|come here|come to me|"
                    + "come over|come up|come down|come to the|get over here|meet me|find me)\\b");
            words.putIfAbsent("STAY", "\\b(stay here|wait here|stay put|i'?ll wait|keep watch here|stay there|wait there)\\b");
        }
        if (words.isEmpty() || said == null) {
            return null;
        }
        String line = said.toLowerCase(java.util.Locale.ROOT);
        String found = null;
        for (String clause : line.split("[.!?;]|\\bbut\\b")) {
            if (NEGATED.matcher(clause).find() || !DOING.matcher(clause).find()) {
                continue;
            }
            String here = mentioned(words, clause);
            if ("".equals(here)) {
                // both named ("the LIVE sign's a trap, I'm taking the DIE door"): it's the one after what they're doing
                java.util.regex.Matcher verb = DOING.matcher(clause);
                int at = 0;
                while (verb.find()) {
                    at = verb.start();
                }
                here = mentioned(words, clause.substring(at));
            }
            if (here == null) {
                continue;
            }
            if (here.isEmpty() || found != null && !found.equals(here)) {
                return null;
            }
            found = here;
        }
        // "Left door. I'm going." : one option named, something being done, and no "not" anywhere
        if (found == null && !NEGATED.matcher(line).find() && DOING.matcher(line).find()) {
            String here = mentioned(words, line);
            found = here == null || here.isEmpty() ? null : here;
        }
        return found;
    }

    /**
     * The one option {@code text} mentions; empty if it mentions more than one; null if none. An option whose tag ends in
     * "_N" takes a number: the last number its words matched goes on the end ("PICK_37").
     */
    @Nullable
    private static String mentioned(Map<String, String> words, String text) {
        String found = null;
        for (Map.Entry<String, String> option : words.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(option.getValue()).matcher(text);
            if (m.find()) {
                String key = option.getKey();
                if (key.endsWith("_N")) {
                    java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d{1,4}").matcher(m.group());
                    String number = null;
                    while (digits.find()) {
                        number = digits.group();
                    }
                    if (number == null) {
                        continue;
                    }
                    key = key.substring(0, key.length() - 1) + number;
                }
                if (found != null) {
                    return "";
                }
                found = key;
            }
        }
        return found;
    }

    /** Whether {@code tag} is something {@code who} can do now: one of the options, or a numbered one ("PICK_37" for "PICK_N"). */
    public final boolean offers(Contestant who, String tag) {
        Map<String, String> options = allOptions(who);
        if (options.containsKey(tag)) {
            return true;
        }
        int cut = tag.lastIndexOf('_');
        return cut > 0 && tag.substring(cut + 1).matches("\\d{1,4}") && options.containsKey(tag.substring(0, cut) + "_N");
    }

    // --- running it ----------------------------------------------------------------------------------------

    /** Builds the venue without starting a game (so the city has it standing before anyone plays). */
    Builder prebuild() {
        planned = true;
        plan(builder);
        return builder;
    }

    void tick() {
        if (!planned) {
            planned = true;
            plan(builder);
            if (bounds != null) {
                Screen.clearOld(world, bounds.expand(4.0));
            }
        }
        phaseTicks++;
        voiceTick();
        if (phase != Phase.BUILDING && phaseTicks % 10 == 0) {
            updateScreens();
        }
        switch (phase) {
            case BUILDING -> {
                if (builder.step(6000)) {
                    phase(Phase.OPEN);
                    recruit(Math.max(0, kind.seats - 1));
                    // a screen by the entrance
                    if (entryPos != null && bounds != null) {
                        Screen screen = Screen.place(world, bounds.expand(3.0), Vec3d.ofBottomCenter(entryPos).add(0.0, 1.6, 0.0), true);
                        if (screen != null) {
                            screens.add(screen);
                        }
                    }
                }
            }
            case OPEN -> registration();
            case BRIEFING -> {
                builder.step(6000);
                watch();
                border();
                briefing();
                briefingTick(phaseTicks);
                if (scriptAt >= script.size() && phaseTicks >= nextLineAt) {
                    phase(Phase.RUNNING);
                    timeLeft = timeLimit();
                    begin();
                }
            }
            case RUNNING -> {
                builder.step(6000);
                watch();
                border();
                if (phase == Phase.RUNNING) {
                    timeLeft--;
                    countdown();
                    noteFates();
                    Mind.tick(this);
                    running();
                    if (phase == Phase.RUNNING) {
                        carryOutOrders();
                    }
                }
                if (phase == Phase.RUNNING && timeLeft <= 0) {
                    timeUp();
                    finish();
                }
                if (phase == Phase.RUNNING && playing().isEmpty()) {
                    finish();
                }
                if (phase == Phase.RUNNING && playersPlaying().isEmpty()) {
                    offscreen();
                }
                updateBar();
            }
            case ENDED -> {
                afterwards();
                if (phaseTicks > 20 * 40) {
                    letEveryoneOut();
                    closed = true;
                }
            }
        }
    }

    private void phase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void registration() {
        if (phaseTicks % 10 == 0) {
            beacon();
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || seats.containsKey(player.getUuid()) || !entry.contains(player.getPos())) {
                continue;
            }
            if (Games.gameOf(player) != null) {
                continue;
            }
            if (seats.size() >= kind.seats) {
                player.sendMessage(Text.literal("Entry is full.").formatted(Formatting.RED), true);
                continue;
            }
            register(player);
            if (instantEntry()) {
                closeEntry();
                return;
            }
        }
        for (UUID id : recruits) {
            Contestant contestant = contestant(id);
            if (contestant == null || seats.containsKey(id)) {
                continue;
            }
            // Strangers leave at least one place for a player: whoever wouldn't get a place waits outside, and never
            // sets foot inside (going in is registering; there's no walking back out)
            boolean room = seats.size() < kind.seats - 1;
            if (room && entry.contains(contestant.getPos())) {
                seat(contestant);
            } else if (phaseTicks % 20 == 0) {
                contestant.go(room ? entryPos : approach, room ? 1.0 : 0.9);
            }
        }
        // anyone who's registered and then leaves the venue, player or not, gets the laser
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && bounds != null && !bounds.contains(entity.getPos())) {
                fail(id, "You registered, and then you tried to leave.");
            }
        }
        // once registered, the strangers stay inside, near the entrance, and wait for the game
        if (phaseTicks % 40 == 0) {
            for (Contestant c : strangers()) {
                if (!entry.contains(c.getPos()) && c.goal() == null) {
                    c.go(entryPos, 0.8);
                }
            }
        }
        if (entryLeft < 0 && !players.isEmpty()) {
            entryLeft = ENTRY_TICKS;
            announce("Entry has begun. Entry closes in one minute.");
        }
        if (entryLeft > 0) {
            entryLeft--;
            if (entryLeft == 0 || seats.size() >= kind.seats) {
                closeEntry();
            }
        } else if (players.isEmpty() && phaseTicks > 20 * 60 * 8 && world.isDay()) {
            closed = true;      // nobody came: the lights go out
            release();
        }
    }

    private void register(ServerPlayerEntity player) {
        seats.put(player.getUuid(), Fate.PLAYING);
        players.add(player.getUuid());
        names.put(player.getUuid(), player.getEntityName());
        GameMode mode = player.interactionManager.getGameMode();
        if (mode == GameMode.SURVIVAL) {
            modes.put(player.getUuid(), mode);
            Visa.of(player).restore = mode.getName();
            player.changeGameMode(GameMode.ADVENTURE);
        }
        if (!player.getInventory().containsAny(Set.of(DeadMansHand.PHONE))) {
            player.getInventory().offerOrDrop(new ItemStack(DeadMansHand.PHONE));
        }
        player.sendMessage(text("Entry accepted. Take a phone. The game begins when entry closes.", Formatting.YELLOW), false);
        player.sendMessage(Text.literal("You're registered. Leave the venue now, or at any time until the game is over, and the laser finds you.")
                .formatted(Formatting.RED, Formatting.ITALIC), false);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0F, 1.2F);
    }

    protected void seat(Contestant contestant) {
        seats.put(contestant.getUuid(), Fate.PLAYING);
        names.put(contestant.getUuid(), contestant.name());
        contestant.gameId = id;
        contestant.stopAll();
    }

    /** Free strangers nearby come to play; new ones turn up for the rest. */
    private void recruit(int count) {
        for (Contestant contestant : world.getEntitiesByClass(Contestant.class, new Box(origin).expand(160.0),
                c -> c.isAlive() && c.free() && (c.role.isEmpty() || campGame()) && Games.gameOf(c) == null)) {
            if (recruits.size() >= count) {
                break;
            }
            recruits.add(contestant.getUuid());
        }
        while (recruits.size() < count) {
            BlockPos at = approach.add(random.nextInt(5) - 2, 0, random.nextInt(3) - 1);
            Contestant contestant = Contestant.spawn(world, at, random);
            if (contestant == null) {
                break;
            }
            recruits.add(contestant.getUuid());
        }
    }

    private void release() {
        for (UUID id : recruits) {
            Contestant contestant = contestant(id);
            if (contestant != null && !seats.containsKey(id)) {
                contestant.gameId = -1;
                contestant.stopAll();
            }
        }
    }

    /** Close entry now (or when the minute is up): fill the empty places and read the rules. */
    public boolean closeEntryNow() {
        if (phase != Phase.OPEN || players.isEmpty()) {
            return false;
        }
        closeEntry();
        return true;
    }

    private void closeEntry() {
        int missing = kind.seats - seats.size();
        for (UUID id : recruits) {
            if (missing <= 0) {
                break;
            }
            Contestant contestant = contestant(id);
            if (contestant != null && !seats.containsKey(id)) {
                if (!entry.contains(contestant.getPos())) {
                    contestant.refreshPositionAndAngles(entryPos.getX() + 0.5, entryPos.getY(), entryPos.getZ() + 0.5, contestant.getYaw(), 0.0F);
                }
                seat(contestant);
                missing--;
            }
        }
        while (fillSeats() && missing-- > 0) {
            Contestant contestant = Contestant.spawn(world, entryPos, random);
            if (contestant != null) {
                seat(contestant);
            }
        }
        release();
        phase(Phase.BRIEFING);
        bar = new ServerBossBar(Text.literal(kind.title), BossBar.Color.RED, BossBar.Style.PROGRESS);
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                bar.addPlayer(player);
            }
        }
        arrange();
        // whoever a game put down with their head inside a block is moved out before they can suffocate
        for (LivingEntity entity : living()) {
            unstick(entity);
        }
        // what the announcer will read out, in order
        script.clear();
        script.add("Entry is closed. The game will now begin.");
        script.add("Game: " + kind.title + ".");
        script.add(kind.card.rank() == Card.JOKER ? "Difficulty: the Joker." : "Difficulty: " + kind.card.title() + ".");
        script.addAll(java.util.Arrays.asList(rules()));
        script.add("Game start.");
        scriptAt = 0;
        nextLineAt = 1;
        rulesRead = 0;
        gameNamed = false;
        // and a screen where the players are standing, if the one at the entrance isn't in sight of them
        LivingEntity watcher = null;
        for (UUID id : players) {
            watcher = entity(id);
            if (watcher != null) {
                break;
            }
        }
        if (watcher != null && bounds != null) {
            Vec3d eyes = watcher.getEyePos();
            boolean seen = false;
            for (Screen screen : screens) {
                seen |= screen.center.distanceTo(eyes) < 16.0;
            }
            if (!seen) {
                Screen screen = Screen.place(world, bounds.expand(1.0), eyes, false);
                if (screen != null) {
                    screens.add(screen);
                }
            }
        }
    }

    // --- the announcer and the screens -----------------------------------------------------------------------------

    /** The briefing, line by line: each is read when the one before has been said. */
    private final List<String> script = new ArrayList<>();
    private int scriptAt;
    private int nextLineAt;
    private int rulesRead;
    private boolean gameNamed;
    private final List<Screen> screens = new ArrayList<>();
    /** The last thing the announcer said, and when: it's on the screens for a while. */
    private String reading = "";
    private long readingAt;

    private record Spoken(int clip, long at, @Nullable UUID only) {
    }

    private final ArrayDeque<Spoken> speaking = new ArrayDeque<>();
    private long voiceFreeAt;

    /** She says {@code text} aloud (if she has it recorded), to everyone who should hear, or to {@code only}. */
    protected void voice(String text, @Nullable UUID only) {
        int clip = Announcer.clip(text);
        if (clip >= 0) {
            speaking.add(new Spoken(clip, world.getTime(), only));
        }
    }

    private void voiceTick() {
        long now = world.getTime();
        while (!speaking.isEmpty() && now >= voiceFreeAt) {
            Spoken next = speaking.poll();
            if (now - next.at() > 20 * 8) {
                continue;       // too long ago to say now
            }
            if (next.only() != null) {
                ServerPlayerEntity player = player(next.only());
                if (player != null) {
                    Announcer.play(player, next.clip());
                }
            } else {
                for (ServerPlayerEntity player : audience()) {
                    Announcer.play(player, next.clip());
                }
            }
            voiceFreeAt = now + Announcer.ticks(next.clip()) + 4;
            return;
        }
    }

    private void updateScreens() {
        if (screens.isEmpty()) {
            return;
        }
        Screen.Look look = look();
        for (Screen screen : screens) {
            screen.show(look);
        }
    }

    /** What the screens show now. */
    private Screen.Look look() {
        String difficulty = kind.card.rank() == Card.JOKER ? "JOKER" : kind.card.title().toUpperCase(java.util.Locale.ROOT);
        byte suit = kind.card.suit().red ? Screen.RED : Screen.WHITE;
        boolean recent = world.getTime() - readingAt < 20 * 20;
        return switch (phase) {
            case BUILDING, OPEN -> new Screen.Look("GAME VENUE", "ENTRY", Screen.WHITE,
                    entryLeft > 0 ? "ENTRY CLOSES IN " + Hud.clock(entryLeft) : "STEP INSIDE TO REGISTER", Screen.GOLD,
                    "One phone per person. Once you're registered, there's no leaving until the game is over.", null, null);
            case BRIEFING -> gameNamed
                    ? new Screen.Look("GAME", kind.title, Screen.WHITE, difficulty, suit, reading, kind.card, null)
                    : new Screen.Look("", "ENTRY CLOSED", Screen.RED, "THE GAME WILL NOW BEGIN", Screen.WHITE, "", null, null);
            case RUNNING -> new Screen.Look("GAME", kind.title, Screen.WHITE, difficulty, suit,
                    recent ? reading : "Still playing: " + playing().size() + ".", kind.card, Hud.clock(Math.max(0, timeLeft)));
            case ENDED -> {
                int cleared = (int) seats.values().stream().filter(f -> f == Fate.CLEARED).count();
                yield new Screen.Look(kind.title, cleared > 0 ? "GAME CLEAR" : "GAME OVER", cleared > 0 ? Screen.GREEN : Screen.RED,
                        cleared > 0 ? "SURVIVORS: " + cleared : "NO SURVIVORS", Screen.WHITE, "", null, null);
            }
        };
    }

    /** The venue is closing: the screens come down. */
    void takeDown() {
        for (Screen screen : screens) {
            screen.remove();
        }
        screens.clear();
    }

    private void briefing() {
        if (scriptAt >= script.size() || phaseTicks < nextLineAt) {
            return;
        }
        String line = script.get(scriptAt);
        announce(line);
        if (scriptAt == 0) {
            sound(SoundEvents.BLOCK_BELL_USE, 0.8F);
            titleAll(Text.literal("ENTRY CLOSED").formatted(Formatting.RED), Text.literal("The game will now begin").formatted(Formatting.WHITE));
        } else if (scriptAt == 1) {
            gameNamed = true;
            titleAll(Text.literal("GAME").formatted(Formatting.GOLD), Text.literal(kind.title).formatted(Formatting.WHITE));
        } else if (scriptAt == 2) {
            if (kind.card.rank() == Card.JOKER) {
                titleAll(Text.literal("DIFFICULTY").formatted(Formatting.GOLD), Text.literal("JOKER").formatted(Formatting.LIGHT_PURPLE));
            } else {
                titleAll(Text.literal("DIFFICULTY").formatted(Formatting.GOLD), Text.literal(kind.card.suit().symbol + "  " + kind.card.title().toUpperCase()
                        + "  " + kind.card.suit().symbol).formatted(kind.card.suit().red ? Formatting.RED : Formatting.WHITE));
                for (ServerPlayerEntity player : audience()) {
                    player.sendMessage(Text.literal("   (" + kind.card.suit().title + ": " + kind.card.suit().kind + ".)").formatted(Formatting.DARK_GRAY), false);
                }
            }
        } else if (scriptAt == script.size() - 1) {
            titleAll(Text.literal("GAME START").formatted(Formatting.RED), Text.literal(""));
            sound(SoundEvents.BLOCK_BELL_USE, 1.0F);
        } else {
            rulesRead++;
        }
        nextLineAt = phaseTicks + Announcer.readingTicks(line) + 8;
        scriptAt++;
    }

    /** Anyone playing who has died, vanished or logged off is out. */
    private void watch() {
        boolean check = phaseTicks % 10 == 0;
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity == null || !entity.isAlive()) {
                seats.put(id, Fate.DEAD);
                died(id);
            } else if (check) {
                // nobody is left to suffocate in a wall: twice in a row in one (a moment apart), and they're moved out
                if (entity.isInsideWall()) {
                    if (inWall.merge(id, 1, Integer::sum) >= 2) {
                        unstick(entity);
                        inWall.remove(id);
                    }
                } else {
                    inWall.remove(id);
                }
            }
        }
    }

    private final Map<UUID, Integer> inWall = new HashMap<>();

    /** Moves {@code entity}, if its head is inside a block, to the nearest place close by where it fits, on solid ground. */
    protected final void unstick(LivingEntity entity) {
        if (!entity.isInsideWall()) {
            return;
        }
        Vec3d at = freeSpotNear(entity);
        if (at == null) {
            return;
        }
        com.deadmanshand.npc.Diary.write(nameOf(entity.getUuid()) + " was inside a wall in " + kind.title + " and was moved out, to "
                + Math.round(at.x) + " " + Math.round(at.y) + " " + Math.round(at.z));
        if (entity instanceof ServerPlayerEntity player) {
            player.networkHandler.requestTeleport(at.x, at.y, at.z, player.getYaw(), player.getPitch());
        } else {
            entity.refreshPositionAndAngles(at.x, at.y, at.z, entity.getYaw(), entity.getPitch());
        }
    }

    @Nullable
    private Vec3d freeSpotNear(LivingEntity entity) {
        BlockPos base = entity.getBlockPos();
        double half = entity.getWidth() / 2.0;
        double height = entity.getHeight();
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos feet = base.add(dx, dy, dz);
                    Vec3d at = new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (bounds != null && !bounds.contains(at)) {
                        continue;
                    }
                    BlockPos below = feet.down();
                    if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                        continue;
                    }
                    Box box = new Box(at.x - half, at.y, at.z - half, at.x + half, at.y + height, at.z + half);
                    if (!world.isSpaceEmpty(entity, box)) {
                        continue;
                    }
                    double score = at.squaredDistanceTo(entity.getPos()) + Math.abs(dy) * 2.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = at;
                    }
                }
            }
        }
        return best;
    }

    private void border() {
        if (bounds == null) {
            return;
        }
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null && !bounds.contains(entity.getPos())) {
                if (entity instanceof Contestant c && "citizen".equals(c.role) && phase == Phase.BRIEFING) {
                    continue;       // the game's own people, still taking up their places: nobody runs from the rules read out
                }
                fail(id, "You left the venue during the game.");
            }
        }
    }

    /** No players left in it: the strangers' game plays out without you. */
    private void offscreen() {
        for (UUID id : playing()) {
            if (random.nextFloat() < 0.4F) {
                clear(id);
            } else {
                fail(id, null);
            }
        }
        finish();
    }

    private void updateBar() {
        if (bar == null || phaseTicks % 10 != 0) {
            return;
        }
        int limit = Math.max(1, timeLimit());
        bar.setPercent(Math.max(0.0F, Math.min(1.0F, timeLeft / (float) limit)));
        bar.setName(Text.literal(kind.title + "  -  " + Hud.clock(timeLeft)));
    }

    /** Over: the survivors get their days and their card. */
    protected void finish() {
        if (phase == Phase.ENDED) {
            return;
        }
        for (UUID id : playing()) {
            fail(id, "The game is over.");
        }
        phase(Phase.ENDED);
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                giveBack(player);
            }
        }
        for (UUID id : seats.keySet()) {
            Mind.forget(id);
            Contestant contestant = contestant(id);
            if (contestant != null) {
                contestant.gameId = -1;
                contestant.stopAll();
            }
        }
        if (bar != null) {
            bar.clearPlayers();
        }
        int cleared = (int) seats.values().stream().filter(f -> f == Fate.CLEARED).count();
        announce(cleared == 0 ? "The game is over. There are no survivors." : "The game is over. Survivors: " + cleared + ".");
        ended();
    }

    // --- afterwards: nobody is ever shut in a venue once it's over ------------------------------------------------

    /** Survivors who can't walk out of where the game left them (the roof of the tower, a sealed room...). */
    private final Set<UUID> shutIn = new HashSet<>();

    private void afterwards() {
        if (bounds == null) {
            return;
        }
        if (phaseTicks == 60) {
            for (Map.Entry<UUID, Fate> seat : seats.entrySet()) {
                ServerPlayerEntity player = player(seat.getKey());
                if (seat.getValue() != Fate.CLEARED || player == null || !bounds.contains(player.getPos())) {
                    continue;
                }
                boolean stuck = !canWalkOut(player);
                if (stuck) {
                    shutIn.add(player.getUuid());
                }
                player.sendMessage(Text.literal(stuck ? "There's no way out of here on foot. You'll be shown out in ten seconds, or now: "
                                : "The game is over. ").formatted(Formatting.GRAY)
                        .append(Text.literal("[Leave the venue]").styled(style -> style.withColor(Formatting.GREEN)
                                .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/answer leave"))
                                .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                        Text.literal("Walk out to the street"))))), false);
            }
        } else if (phaseTicks == 260) {
            for (UUID id : shutIn) {
                ServerPlayerEntity player = player(id);
                if (player != null && bounds.contains(player.getPos())) {
                    walkOut(player);
                }
            }
        }
    }

    /** The venue is closing: anyone who survived and is still shut in it is shown out. */
    private void letEveryoneOut() {
        if (bounds == null) {
            return;
        }
        for (Map.Entry<UUID, Fate> seat : seats.entrySet()) {
            if (seat.getValue() != Fate.CLEARED) {
                continue;
            }
            LivingEntity entity = entity(seat.getKey());
            if (entity != null && entity.isAlive() && bounds.contains(entity.getPos()) && !canWalkOut(entity)) {
                walkOut(entity);
            }
        }
    }

    /** /answer leave, after the game: out to the street in front of the venue. */
    boolean leaveAfterwards(ServerPlayerEntity player) {
        if (phase != Phase.ENDED || seats.get(player.getUuid()) != Fate.CLEARED || bounds == null
                || !bounds.expand(2.0).contains(player.getPos())) {
            return false;
        }
        walkOut(player);
        return true;
    }

    private void walkOut(LivingEntity entity) {
        BlockPos street = approach != null ? approach : origin;
        Vec3d at = Vec3d.ofBottomCenter(street);
        for (int r = 0; r <= 4; r++) {
            Vec3d found = null;
            for (int dx = -r; dx <= r && found == null; dx++) {
                for (int dz = -r; dz <= r && found == null; dz++) {
                    for (int dy = -1; dy <= 2 && found == null; dy++) {
                        BlockPos feet = street.add(dx, dy, dz);
                        BlockPos below = feet.down();
                        Vec3d spot = Vec3d.ofBottomCenter(feet);
                        if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()
                                && world.isSpaceEmpty(entity, entity.getBoundingBox().offset(spot.subtract(entity.getPos())))) {
                            found = spot;
                        }
                    }
                }
            }
            if (found != null) {
                at = found;
                break;
            }
        }
        unfloat(entity);
        entity.fallDistance = 0.0F;
        if (entity instanceof ServerPlayerEntity player) {
            player.networkHandler.requestTeleport(at.x, at.y, at.z, player.getYaw(), player.getPitch());
            player.sendMessage(Text.literal("You're shown out of the venue, onto the street.").formatted(Formatting.GRAY), false);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 0.8F, 1.0F);
        } else {
            entity.refreshPositionAndAngles(at.x, at.y, at.z, entity.getYaw(), entity.getPitch());
        }
        shutIn.remove(entity.getUuid());
    }

    /**
     * Whether {@code who} could walk out of the venue from where they stand: along floors, up stairs and ladders, through
     * doors anyone can open, down drops of up to four blocks. Not across gaps, not over fences or walls. A venue the size
     * of the city never shuts anyone in.
     */
    private boolean canWalkOut(LivingEntity who) {
        Box area = bounds.expand(0.5);
        if (area.getXLength() * area.getZLength() > 220.0 * 220.0) {
            return true;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        BlockPos start = who.getBlockPos();
        if (!passable(start)) {
            start = start.up();
        }
        queue.add(start);
        seen.add(start.asLong());
        int budget = 150_000;
        while (!queue.isEmpty()) {
            if (--budget <= 0) {
                return true;        // somewhere this big has a way out
            }
            BlockPos at = queue.poll();
            if (!area.contains(Vec3d.ofCenter(at))) {
                return true;
            }
            boolean climbing = world.getBlockState(at).isIn(net.minecraft.tag.BlockTags.CLIMBABLE);
            BlockPos below = at.down();
            boolean standing = climbing || !passable(below) || world.getBlockState(below).isIn(net.minecraft.tag.BlockTags.CLIMBABLE);
            if (!standing) {
                // a drop: straight down to where they'd land; further than four blocks is no way out, it's a fall
                BlockPos land = below;
                int drop = 1;
                while (drop <= 5 && passable(land.down()) && !world.getBlockState(land).isIn(net.minecraft.tag.BlockTags.CLIMBABLE)) {
                    land = land.down();
                    drop++;
                }
                if (drop <= 4 && seen.add(land.asLong())) {
                    queue.add(land);
                }
                continue;
            }
            if (climbing && passable(at.up()) && seen.add(at.up().asLong())) {
                queue.add(at.up());
            }
            for (net.minecraft.util.math.Direction d : new net.minecraft.util.math.Direction[]{net.minecraft.util.math.Direction.NORTH,
                    net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
                BlockPos next = at.offset(d);
                if (passable(next) && passable(next.up())) {
                    if (seen.add(next.asLong())) {
                        queue.add(next);
                    }
                } else if (passable(next.up()) && passable(next.up(2)) && passable(at.up(2))
                        && world.getBlockState(next).getCollisionShape(world, next).getMax(net.minecraft.util.math.Direction.Axis.Y) <= 1.0) {
                    BlockPos step = next.up();      // a step up (a stair, a slab, a block; not a fence or a wall)
                    if (seen.add(step.asLong())) {
                        queue.add(step);
                    }
                }
            }
        }
        return false;
    }

    /** A body can be in this block: nothing solid, or a door, gate or hatch anyone can open, or something to climb. */
    private boolean passable(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty()
                || state.isIn(net.minecraft.tag.BlockTags.WOODEN_DOORS)
                || state.isIn(net.minecraft.tag.BlockTags.FENCE_GATES)
                || state.isIn(net.minecraft.tag.BlockTags.WOODEN_TRAPDOORS)
                || state.isIn(net.minecraft.tag.BlockTags.CLIMBABLE);
    }

    /** The server is stopping or the game is being thrown away. */
    void abandon() {
        takeDown();
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                giveBack(player);
            }
        }
        if (bar != null) {
            bar.clearPlayers();
        }
        ended();
    }

    private void giveBack(ServerPlayerEntity player) {
        GameMode mode = modes.remove(player.getUuid());
        if (mode != null && player.interactionManager.getGameMode() == GameMode.ADVENTURE) {
            player.changeGameMode(mode);
        }
        Visa.of(player).restore = "";
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    /** {@code id} has cleared the game. */
    protected void clear(UUID id) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.CLEARED);
        // someone made it: that lifts everyone still in it a little, and whoever it was a lot
        for (UUID other : seats.keySet()) {
            Contestant c = contestant(other);
            if (c != null) {
                c.feelings.lifted(other.equals(id) ? 0.4F : 0.08F);
            }
        }
        ServerPlayerEntity player = player(id);
        if (player != null) {
            Visa.Data data = Visa.of(player);
            data.visa += kind.card.days() * Visa.DAY;
            data.cards.add(kind.card.code());
            data.cleared++;
            player.getInventory().offerOrDrop(kind.card.stack());
            giveBack(player);
            Borderland.title(player, Text.literal("GAME CLEAR").formatted(Formatting.GREEN),
                    Text.literal("+" + kind.card.days() + (kind.card.days() == 1 ? " day" : " days") + " on your visa").formatted(Formatting.WHITE));
            world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
            voice("Game clear.", id);
        }
        Contestant contestant = contestant(id);
        if (contestant != null) {
            contestant.visa += kind.card.days() * Visa.DAY;
            contestant.cleared++;
        }
    }

    /** {@code id} loses: the laser. */
    protected void fail(UUID id, @Nullable String why) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.DEAD);
        LivingEntity entity = entity(id);
        if (entity != null && entity.isAlive()) {
            Laser.strike(entity, why);
        }
        if (players.contains(id)) {
            voice("Game over.", id);
        }
        died(id);
    }

    // --- choices every walking-about game shares: go with someone, stay put, or go your own way ------------------

    /** The rules as they were read out, in one go: for the voices. */
    public final String rulesBrief() {
        String[] rules = rules();
        if (phase == Phase.OPEN || phase == Phase.BUILDING) {
            return "";     // nobody has been told anything yet
        }
        if (phase == Phase.BRIEFING) {
            // only what's been read out so far
            return String.join(" ", java.util.Arrays.copyOf(rules, Math.min(rules.length, rulesRead)));
        }
        return String.join(" ", rules);
    }

    /** Whether the game has been announced yet (its name and card), to the people inside. */
    public final boolean announced() {
        return phase == Phase.RUNNING || phase == Phase.ENDED || phase == Phase.BRIEFING && gameNamed;
    }

    /**
     * What {@code who} can know about where they are right now. Before entry closes, nobody knows what the game is;
     * while the rules are being read, only what's been read; once it's running, everything the game tells them.
     */
    public final String situationNow(Contestant who) {
        if (phase == Phase.OPEN || phase == Phase.BUILDING) {
            return "You've come into a building that lit up in the night: a game venue. At the entrance you were registered and "
                    + "handed a phone. Nobody knows what the game is yet, or what the card is; it's announced when entry closes. Once "
                    + "you've registered, leaving the venue means the laser. Others may still be coming in.";
        }
        if (phase == Phase.BRIEFING) {
            String read = rulesBrief();
            return (announced() ? "Entry has closed and the game has been announced: " + kind.title + ", difficulty " + (kind.card.rank() == Card.JOKER
                    ? "the Joker" : kind.card.title()) + "." : "Entry has just closed; the voice is about to announce the game.")
                    + (read.isEmpty() ? "" : " The rules being read out so far: " + read) + " Nobody has started yet.";
        }
        return situation(who);
    }

    /** What {@code who} has committed to (out loud, or by doing it), in words, if anything: for the voices. */
    @Nullable
    public String decided(Contestant who) {
        UUID id = who.getUuid();
        if (leader.containsKey(id)) {
            return "you agreed to go with " + nameOf(leader.get(id)) + ".";
        }
        if (approaching.containsKey(id)) {
            return "you're going over to " + nameOf(approaching.get(id)) + ".";
        }
        if (avoiding.containsKey(id)) {
            return "you're keeping away from " + nameOf(avoiding.get(id)) + ".";
        }
        if (headingTo.containsKey(id)) {
            return "you're on your way to " + headingTo.get(id) + ".";
        }
        if (stayAt.containsKey(id)) {
            return "you decided to stay where you are for now.";
        }
        String t = told.get(id);
        return t == null ? null : "you said you'd " + t.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ".";
    }

    /** The clock, out loud: a minute, thirty seconds, then every second of the last ten, with your heart in your ears. */
    private void countdown() {
        if (timeLeft == 60 * 20) {
            announce("One minute left.");
            for (ServerPlayerEntity p : audience()) {
                p.playSound(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1.0F, 0.6F);
            }
        } else if (timeLeft == 30 * 20) {
            announce("Thirty seconds.");
        } else if (timeLeft > 0 && timeLeft <= 10 * 20 && timeLeft % 20 == 0) {
            int seconds = timeLeft / 20;
            for (ServerPlayerEntity p : audience()) {
                p.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 1.0F, 1.0F + (10 - seconds) * 0.06F);
                p.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.MASTER, 1.0F, 1.0F);
                if (seats.get(p.getUuid()) == Fate.PLAYING) {
                    Borderland.title(p, Text.literal(String.valueOf(seconds)).formatted(Formatting.DARK_RED), Text.literal(""));
                }
            }
        }
    }

    /** A name as part of a tag: "Riku" becomes "RIKU". */
    protected static String tagOf(String name) {
        return name.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    /** What strangers have said they'll do, for games that only need to remember it: the tag, by stranger. */
    protected final Map<UUID, String> told = new HashMap<>();
    private final Map<UUID, UUID> leader = new HashMap<>();
    private final Map<UUID, Long> orderEnds = new HashMap<>();
    private final Map<UUID, Vec3d> stayAt = new HashMap<>();
    /** Going over to someone, keeping away from someone, or making for a place. */
    private final Map<UUID, UUID> approaching = new HashMap<>();
    private final Map<UUID, UUID> avoiding = new HashMap<>();
    private final Map<UUID, Vec3d> heading = new HashMap<>();
    private final Map<UUID, String> headingTo = new HashMap<>();

    /** Whether strangers here can be asked to come with someone, or to stay where they are: games where people walk about freely. */
    protected boolean followable() {
        return false;
    }

    /** For the minds: whether people walk about freely here (so where to go is theirs to decide). */
    public final boolean walksAbout() {
        return followable();
    }

    /**
     * Places in the venue worth naming for someone deciding where to go ("the haunted house", "the blue base"): a tag
     * (capitals, no spaces) and the place. Walking-about games only.
     */
    protected Map<String, Place> places(Contestant who) {
        return Map.of();
    }

    /** A place someone can decide to go to. */
    protected record Place(Vec3d pos, String what) {
        static Place at(BlockPos pos, String what) {
            return new Place(Vec3d.ofBottomCenter(pos), what);
        }
    }

    /** Everything {@code who} could decide to do now: this game's own options, and, where people walk about, where to go. */
    public final Map<String, String> allOptions(Contestant who) {
        Map<String, String> all = new LinkedHashMap<>(options(who));
        UUID id = who.getUuid();
        if (followable() && isPlaying(id) && !who.held()) {
            long now = world.getTime();
            UUID asker = who.lastTalkedTo(now);
            if (asker != null && isPlaying(asker) && !asker.equals(id)) {
                all.putIfAbsent("FOLLOW", "go with " + nameOf(asker) + " (who's been talking to you) and stay close to them for the next minute");
            }
            for (Map.Entry<String, Place> place : places(who).entrySet()) {
                all.putIfAbsent("GO_" + place.getKey(), "go to " + place.getValue().what());
            }
            int people = 0;
            for (LivingEntity other : nearestPlaying(who, 40.0)) {
                if (people++ >= 3) {
                    break;
                }
                String name = nameOf(other.getUuid());
                String t = tagOf(name);
                all.putIfAbsent("FOLLOW_" + t, "go with " + name + " and stay close to them for the next minute");
                all.putIfAbsent("GO_TO_" + t, "walk over to " + name);
                all.putIfAbsent("AWAY_FROM_" + t, "get away from " + name + " and keep your distance");
            }
            all.putIfAbsent("EXPLORE", "look around a part of the place you haven't been to");
            all.putIfAbsent("STAY", "stay right where you are for the next half minute");
            if (ordered(id)) {
                all.putIfAbsent("OWN_WAY", "stop what you said you'd do (following, waiting, going somewhere) and do what you think is best");
            }
        }
        return all;
    }

    private boolean ordered(UUID id) {
        return leader.containsKey(id) || stayAt.containsKey(id) || approaching.containsKey(id) || avoiding.containsKey(id) || heading.containsKey(id);
    }

    /** Everyone else still playing within {@code range} of {@code who}, nearest first. */
    protected final List<LivingEntity> nearestPlaying(LivingEntity who, double range) {
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : living()) {
            if (e != who && e.squaredDistanceTo(who) < range * range && !(e instanceof Contestant c && c.isBody())) {
                list.add(e);
            }
        }
        list.sort((a, b) -> Double.compare(a.squaredDistanceTo(who), b.squaredDistanceTo(who)));
        return list;
    }

    @Nullable
    private UUID byTag(String t) {
        for (UUID other : playing()) {
            if (tagOf(nameOf(other)).equals(t)) {
                return other;
            }
        }
        return null;
    }

    private void clearOrders(UUID id) {
        leader.remove(id);
        stayAt.remove(id);
        approaching.remove(id);
        avoiding.remove(id);
        heading.remove(id);
        headingTo.remove(id);
        orderEnds.remove(id);
    }

    /** {@code who} has committed to {@code option}: the shared ones are handled here, the rest by the game. */
    public final void choose(Contestant who, String option) {
        UUID id = who.getUuid();
        long now = world.getTime();
        Map<String, String> own = options(who);
        if (own.containsKey(option) || offersNumbered(own, option)) {
            told.put(id, option);
            chose(who, option);
            return;
        }
        if (option.equals("FOLLOW") || option.startsWith("FOLLOW_")) {
            UUID lead = option.equals("FOLLOW") ? who.lastTalkedTo(now) : byTag(option.substring(7));
            if (lead != null && !lead.equals(id)) {
                clearOrders(id);
                leader.put(id, lead);
                orderEnds.put(id, now + 20 * 60);
                who.trust(lead, 0.05F);
            }
        } else if (option.startsWith("GO_TO_")) {
            UUID to = byTag(option.substring(6));
            if (to != null && !to.equals(id)) {
                clearOrders(id);
                approaching.put(id, to);
                orderEnds.put(id, now + 20 * 30);
            }
        } else if (option.startsWith("AWAY_FROM_")) {
            UUID from = byTag(option.substring(10));
            if (from != null && !from.equals(id)) {
                clearOrders(id);
                avoiding.put(id, from);
                orderEnds.put(id, now + 20 * 25);
            }
        } else if (option.startsWith("GO_") && places(who).containsKey(option.substring(3))) {
            Place place = places(who).get(option.substring(3));
            clearOrders(id);
            heading.put(id, place.pos());
            headingTo.put(id, place.what());
            orderEnds.put(id, now + 20 * 45);
        } else if (option.equals("EXPLORE")) {
            Vec3d spot = somewhereElse(who);
            if (spot != null) {
                clearOrders(id);
                heading.put(id, spot);
                headingTo.put(id, "a part of the place you hadn't seen");
                orderEnds.put(id, now + 20 * 30);
            }
        } else if (option.equals("STAY")) {
            clearOrders(id);
            stayAt.put(id, who.getPos());
            orderEnds.put(id, now + 20 * 30);
        } else if (option.equals("OWN_WAY")) {
            clearOrders(id);
        } else {
            told.put(id, option);
            chose(who, option);
        }
    }

    private static boolean offersNumbered(Map<String, String> options, String tag) {
        int cut = tag.lastIndexOf('_');
        return cut > 0 && tag.substring(cut + 1).matches("\\d{1,4}") && options.containsKey(tag.substring(0, cut) + "_N");
    }

    /** Somewhere in the venue a little way off that someone could walk to: solid ground, room to stand. */
    @Nullable
    private Vec3d somewhereElse(LivingEntity who) {
        if (bounds == null) {
            return null;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            double x = bounds.minX + 2 + random.nextDouble() * Math.max(1.0, bounds.getXLength() - 4);
            double z = bounds.minZ + 2 + random.nextDouble() * Math.max(1.0, bounds.getZLength() - 4);
            if ((x - who.getX()) * (x - who.getX()) + (z - who.getZ()) * (z - who.getZ()) < 8 * 8 && attempt < 12) {
                continue;
            }
            int top = (int) Math.floor(who.getY()) + 4;
            for (int y = top; y > top - 10 && y > bounds.minY; y--) {
                BlockPos floor = new BlockPos((int) Math.floor(x), y - 1, (int) Math.floor(z));
                if (!world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()
                        && world.getBlockState(floor.up()).getCollisionShape(world, floor.up()).isEmpty()
                        && world.getBlockState(floor.up(2)).getCollisionShape(world, floor.up(2)).isEmpty()) {
                    return new Vec3d(Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
                }
            }
        }
        return null;
    }

    /** Until when each stranger is running on instinct (fleeing something right on top of them): that beats any plan. */
    private final Map<UUID, Long> reflexUntil = new HashMap<>();

    /**
     * {@code c} is running from something close, by instinct, this tick: for the next second, nothing they'd decided
     * (following someone, going somewhere) gets in the way of it.
     */
    protected final void reflex(Contestant c) {
        reflexUntil.put(c.getUuid(), world.getTime() + 20);
    }

    /** After the game's own orders each tick: anyone who's decided where to go (or who to stick with) does that instead. */
    private void carryOutOrders() {
        long now = world.getTime();
        tellWhoIsComing(now);
        if (orderEnds.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(orderEnds.keySet())) {
            Contestant c = contestant(id);
            UUID lead = leader.get(id);
            UUID to = approaching.get(id);
            UUID from = avoiding.get(id);
            UUID other = lead != null ? lead : to != null ? to : from;
            LivingEntity them = other == null ? null : entity(other);
            boolean over = c == null || !isPlaying(id) || now > orderEnds.get(id) || other != null && (them == null || !isPlaying(other));
            if (over) {
                if (c != null && lead != null && isPlaying(id) && now > orderEnds.get(id) && random.nextFloat() < 0.5F) {
                    c.say(Lines.pick(random, "I'll find my own way from here.", "Alright, I'm going on alone.", "Thanks. I've got it from here."));
                }
                clearOrders(id);
                continue;
            }
            if (c.held() || now < reflexUntil.getOrDefault(id, 0L)) {
                continue;       // the game has them standing still, or they're running for their life: that comes first
            }
            if (lead != null) {
                if (c.squaredDistanceTo(them) > 2.5 * 2.5) {
                    c.go(them.getPos(), them.isSprinting() ? 1.35 : 1.15);
                } else {
                    c.go(c.getPos(), 1.0);
                    c.look(them);
                }
            } else if (to != null) {
                if (c.squaredDistanceTo(them) > 2.2 * 2.2) {
                    c.go(them.getPos(), 1.1);
                } else {
                    c.go(c.getPos(), 1.0);
                    c.look(them);
                    orderEnds.put(id, Math.min(orderEnds.get(id), now + 20 * 6));
                }
            } else if (from != null) {
                double d = Math.sqrt(c.squaredDistanceTo(them));
                if (d < 14.0) {
                    Vec3d away = c.getPos().subtract(them.getPos()).multiply(1.0, 0.0, 1.0);
                    away = away.lengthSquared() < 0.01 ? new Vec3d(1, 0, 0) : away.normalize();
                    Vec3d target = c.getPos().add(away.multiply(8.0));
                    if (bounds != null) {
                        target = new Vec3d(Math.max(bounds.minX + 1.5, Math.min(bounds.maxX - 1.5, target.x)), target.y,
                                Math.max(bounds.minZ + 1.5, Math.min(bounds.maxZ - 1.5, target.z)));
                    }
                    c.go(target, d < 6.0 ? 1.35 : 1.1);
                } else {
                    c.go(c.getPos(), 1.0);
                    c.lookAt(them.getEyePos());
                }
            } else if (heading.containsKey(id)) {
                Vec3d where = heading.get(id);
                if (c.squaredDistanceTo(where) > 1.3 * 1.3) {
                    c.go(where, 1.05);
                } else {
                    // there: wait here a little, then it's theirs to decide again
                    heading.remove(id);
                    headingTo.remove(id);
                    stayAt.put(id, where);
                    orderEnds.put(id, Math.min(orderEnds.get(id), now + 20 * 12));
                }
            } else if (stayAt.containsKey(id)) {
                c.go(stayAt.get(id), 1.0);
            }
        }
    }

    /**
     * Anyone on their way to you (following you, or coming over) and out of sight: every few seconds, a line on your
     * screen says who, how far, and how many floors away, so a promise to come isn't just words you can't check.
     */
    private void tellWhoIsComing(long now) {
        if (now % 60 != 0) {
            return;
        }
        Map<UUID, List<String>> coming = new HashMap<>();
        for (Map.Entry<UUID, Long> order : orderEnds.entrySet()) {
            UUID id = order.getKey();
            UUID to = leader.containsKey(id) ? leader.get(id) : approaching.get(id);
            ServerPlayerEntity player = to == null ? null : player(to);
            Contestant c = contestant(id);
            if (player == null || c == null || !isPlaying(id)) {
                continue;
            }
            double d = Math.sqrt(c.squaredDistanceTo(player));
            if (d < 10.0 && player.canSee(c)) {
                continue;
            }
            int floors = (int) Math.round((c.getY() - player.getY()) / 4.0);
            coming.computeIfAbsent(to, k -> new ArrayList<>()).add(c.name() + " " + (int) d + " m" + (floors == 0 ? "" : floors > 0
                    ? ", " + floors + (floors == 1 ? " floor up" : " floors up") : ", " + -floors + (floors == -1 ? " floor down" : " floors down")));
        }
        for (Map.Entry<UUID, List<String>> e : coming.entrySet()) {
            ServerPlayerEntity player = player(e.getKey());
            if (player != null) {
                player.sendMessage(Text.literal("On their way to you: " + String.join(" · ", e.getValue())).formatted(Formatting.GRAY), true);
            }
        }
    }

    // --- what the minds are told: what's happened ------------------------------------------------------------

    private record Event(long at, String text) {
    }

    private final Deque<Event> log = new ArrayDeque<>();
    private int events;
    private final Set<UUID> fatesNoted = new HashSet<>();

    /** Something everyone here would know has happened (announced, seen, said by a player): everyone thinks again. */
    public final void note(String text) {
        note(text, true);
    }

    /**
     * Something everyone here would know has happened. {@code matters}: it's worth everyone stopping to think again
     * (not just someone else's chatter).
     */
    public final void note(String text, boolean matters) {
        if (phase != Phase.RUNNING && phase != Phase.BRIEFING) {
            return;
        }
        log.addLast(new Event(world.getTime(), text.length() > 220 ? text.substring(0, 217) + "..." : text));
        while (log.size() > 30) {
            log.pollFirst();
        }
        if (matters) {
            events++;
        }
    }

    /** How many things have happened: when it changes, the minds look again. */
    public final int events() {
        return events;
    }

    /** The last {@code max} things that happened, oldest first, with how long ago. */
    public final List<String> recent(long now, int max) {
        List<String> list = new ArrayList<>();
        int skip = Math.max(0, log.size() - max);
        for (Event e : log) {
            if (skip-- > 0) {
                continue;
            }
            long seconds = Math.max(0, (now - e.at()) / 20);
            list.add((seconds < 2 ? "just now" : seconds < 90 ? seconds + "s ago" : seconds / 60 + " min ago") + ": " + e.text());
        }
        return list;
    }

    /** Deaths and clears, as they happen, for the minds; and for you, a death you'd have heard. */
    private void noteFates() {
        for (Map.Entry<UUID, Fate> seat : seats.entrySet()) {
            if (seat.getValue() != Fate.PLAYING && fatesNoted.add(seat.getKey())) {
                note(nameOf(seat.getKey()) + (seat.getValue() == Fate.DEAD ? " died." : " cleared the game and is safe."));
                if (seat.getValue() == Fate.DEAD) {
                    heardDying(seat.getKey());
                }
                com.deadmanshand.npc.Diary.write(kind.title + " | " + nameOf(seat.getKey()) + (seat.getValue() == Fate.DEAD ? " died" : " cleared"));
            }
        }
    }

    /**
     * Someone died: every player still in the game who was close enough hears it (a scream, the laser) and roughly
     * where; and if it was someone they'd been talking to, they know the voice.
     */
    private void heardDying(UUID dead) {
        LivingEntity body = entity(dead);
        if (body == null) {
            return;
        }
        long now = world.getTime();
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player == null || id.equals(dead) || !isPlaying(id)) {
                continue;
            }
            double d = Math.sqrt(player.squaredDistanceTo(body));
            if (d > 64.0 || d < 6.0 && player.canSee(body)) {
                continue;       // too far to hear, or right in front of you (you saw it)
            }
            double dy = body.getY() - player.getY();
            String where = dy > 3.0 ? "somewhere above you" : dy < -3.0 ? "somewhere below you" : d < 20.0 ? "close by" : "some way off";
            boolean known = body instanceof Contestant c && c.talkedWith(id, now, 20L * 60L * 5L);
            player.sendMessage(Text.literal("You hear someone scream, " + where + ", and then nothing."
                    + (known ? " It was " + nameOf(dead) + "'s voice." : "")).formatted(Formatting.DARK_RED), false);
        }
    }

    /**
     * A stranger said {@code line} out loud: anyone they named who's close enough to hear turns to answer, and the game
     * hears it too (for games where what people tell each other matters).
     */
    public final void saidBy(Contestant who, String line) {
        if (phase != Phase.RUNNING) {
            return;
        }
        String lower = " " + line.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        for (Contestant other : strangers()) {
            if (other == who || other.squaredDistanceTo(who) > 20.0 * 20.0) {
                continue;
            }
            String first = other.name().toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")[0];
            if (first.length() >= 2 && lower.contains(" " + first + " ")) {
                Mind.poke(other, who.name(), line);
            }
        }
        heardStranger(who, line);
    }

    /** A stranger said something out loud, here. */
    protected void heardStranger(Contestant who, String line) {
    }

    /** The strangers still playing, for the minds. */
    public final List<Contestant> strangersPlaying() {
        return strangers();
    }

    /** Whether the AI is making the strangers' decisions in this game right now (so the game shouldn't make them up for them). */
    protected final boolean minds() {
        return com.deadmanshand.Config.aiDecides && com.deadmanshand.npc.Voice.canDecide();
    }

    /** Whether {@code c}'s decision is the AI's to make right now: the game's own thinking waits while it is. */
    protected final boolean aiHolds(Contestant c) {
        return Mind.holds(this, c, world.getTime());
    }

    /** {@code id} loses, killed by the game itself ({@code how}: a shock, a blast, gas) rather than by the laser. */
    protected void kill(UUID id, net.minecraft.entity.damage.DamageSource how, @Nullable String why) {
        if (seats.get(id) != Fate.PLAYING) {
            return;
        }
        seats.put(id, Fate.DEAD);
        LivingEntity entity = entity(id);
        if (entity instanceof ServerPlayerEntity player && (player.isCreative() || player.isSpectator())) {
            tell(player, (why == null ? "" : why + " ") + "(You'd be dead, but you're in creative.)", Formatting.RED);
        } else if (entity != null && entity.isAlive()) {
            if (why != null) {
                tell(entity, why, Formatting.RED);
            }
            entity.damage(how, 1000.0F);
        }
        died(id);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    public boolean has(UUID id) {
        return seats.containsKey(id);
    }

    public boolean isPlaying(UUID id) {
        return seats.get(id) == Fate.PLAYING;
    }

    public List<UUID> playing() {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Fate> entry : seats.entrySet()) {
            if (entry.getValue() == Fate.PLAYING) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    private List<UUID> playersPlaying() {
        List<UUID> list = new ArrayList<>();
        for (UUID id : players) {
            if (seats.get(id) == Fate.PLAYING) {
                list.add(id);
            }
        }
        return list;
    }

    /** Everyone still playing who is here. */
    protected List<LivingEntity> living() {
        List<LivingEntity> list = new ArrayList<>();
        for (UUID id : playing()) {
            LivingEntity entity = entity(id);
            if (entity != null) {
                list.add(entity);
            }
        }
        return list;
    }

    protected List<Contestant> strangers() {
        List<Contestant> list = new ArrayList<>();
        for (UUID id : playing()) {
            Contestant contestant = contestant(id);
            if (contestant != null) {
                list.add(contestant);
            }
        }
        return list;
    }

    @Nullable
    protected LivingEntity entity(UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        if (player != null) {
            return player.world == world ? player : null;
        }
        Entity entity = world.getEntity(id);
        return entity instanceof LivingEntity living ? living : null;
    }

    @Nullable
    protected ServerPlayerEntity player(UUID id) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
        return player != null && player.world == world ? player : null;
    }

    @Nullable
    protected Contestant contestant(UUID id) {
        Entity entity = world.getEntity(id);
        return entity instanceof Contestant contestant && contestant.isAlive() ? contestant : null;
    }

    protected String nameOf(UUID id) {
        return names.getOrDefault(id, "someone");
    }

    /** Everyone who should hear the announcer: the players in it, and anyone close. */
    protected List<ServerPlayerEntity> audience() {
        List<ServerPlayerEntity> list = new ArrayList<>();
        Box near = (bounds != null ? bounds : new Box(origin)).expand(32.0);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (players.contains(player.getUuid()) || near.contains(player.getPos())) {
                list.add(player);
            }
        }
        return list;
    }

    /** The announcer: a bright, calm voice from every speaker. */
    public void announce(String message) {
        note("Announced: " + message);
        MutableText line = Text.literal("[" + kind.card.suit().symbol + "] ").formatted(kind.card.suit().red ? Formatting.RED : Formatting.GRAY)
                .append(Text.literal(message).formatted(Formatting.GOLD));
        for (ServerPlayerEntity player : audience()) {
            player.sendMessage(line, false);
        }
        voice(message, null);
        reading = message;
        readingAt = world.getTime();
    }

    protected void titleAll(Text title, Text subtitle) {
        for (UUID id : players) {
            ServerPlayerEntity player = player(id);
            if (player != null) {
                Borderland.title(player, title, subtitle);
            }
        }
    }

    protected void tell(LivingEntity who, String message, Formatting color) {
        if (who instanceof ServerPlayerEntity player) {
            player.sendMessage(Text.literal(message).formatted(color), false);
        }
    }

    protected void sound(net.minecraft.sound.SoundEvent sound, float pitch) {
        Vec3d at = Vec3d.ofCenter(entryPos != null ? entryPos : origin);
        for (ServerPlayerEntity player : audience()) {
            player.playSound(sound, SoundCategory.MASTER, 1.0F, pitch);
        }
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.BLOCKS, 0.5F, pitch);
    }

    protected static MutableText text(String message, Formatting color) {
        return Text.literal(message).formatted(color);
    }

    /** The venue's light, a pale column over the entrance you can see from far off. */
    private void beacon() {
        double x = entryPos.getX() + 0.5;
        double z = entryPos.getZ() + 0.5;
        for (ServerPlayerEntity player : world.getPlayers()) {
            double distance = player.squaredDistanceTo(x, entryPos.getY(), z);
            if (distance > 320.0 * 320.0 || distance < 6.0 * 6.0) {
                continue;
            }
            for (int h = 0; h < 48; h += 2) {
                world.spawnParticles(player, ParticleTypes.END_ROD, true, x, entryPos.getY() + 3 + h, z, 1, 0.05, 0.4, 0.05, 0.0);
            }
        }
    }

    // --- what the manager asks ---------------------------------------------------------------------------------

    public void hud(ServerPlayerEntity player, Hud hud) {
        boolean secret = phase == Phase.BUILDING || phase == Phase.OPEN;
        hud.line(secret ? "§6??? §fGAME: unknown until entry closes" : kind.card.shortName() + " §f" + kind.title);
        switch (phase) {
            case OPEN -> hud.line(entryLeft > 0 ? "§eENTRY CLOSES " + Hud.clock(entryLeft) : "§7Entry is open");
            case BRIEFING -> hud.line("§eLISTEN TO THE RULES");
            case RUNNING -> hud.line("§fTIME " + Hud.clock(timeLeft));
            case ENDED -> hud.line("§7GAME OVER");
            default -> {
            }
        }
        if (seats.get(player.getUuid()) == Fate.CLEARED) {
            hud.line("§aGAME CLEAR");
        } else if (phase == Phase.BRIEFING || phase == Phase.RUNNING) {
            more(player, hud);
        }
    }

    /** The phone: the rules again. */
    public void phone(ServerPlayerEntity player) {
        if (phase == Phase.BUILDING || phase == Phase.OPEN) {
            player.sendMessage(Text.literal("GAME: ???   Nobody knows what it is until entry closes. Then the rules are read out.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        player.sendMessage(Text.literal("GAME: " + kind.title + "   DIFFICULTY: " + kind.card.title().toUpperCase())
                .formatted(kind.card.suit().red ? Formatting.RED : Formatting.WHITE), false);
        for (String rule : rules()) {
            player.sendMessage(Text.literal("  " + rule).formatted(Formatting.GOLD), false);
        }
        if (phase == Phase.RUNNING) {
            player.sendMessage(Text.literal("  Time left: " + Hud.clock(timeLeft)).formatted(Formatting.GRAY), false);
        }
    }

    public boolean inside(BlockPos pos) {
        return bounds != null && bounds.contains(Vec3d.ofCenter(pos));
    }

    public boolean near(Entity entity, double margin) {
        return bounds != null && bounds.expand(margin).contains(entity.getPos());
    }

    public BlockPos entrance() {
        return entryPos != null ? entryPos : origin;
    }

    public Box bounds() {
        return bounds;
    }

    ActionResult useBlock(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (phase == Phase.BRIEFING || phase == Phase.RUNNING) {
            return use(player, pos, state);
        }
        return ActionResult.PASS;
    }

    void hear(ServerPlayerEntity player, String message) {
        if (phase == Phase.RUNNING && seats.containsKey(player.getUuid())) {
            note(player.getEntityName() + " said: " + message);
            heard(player, message);
        }
    }

    boolean use(ServerPlayerEntity player, ItemStack stack) {
        return (phase == Phase.BRIEFING || phase == Phase.RUNNING) && isPlaying(player.getUuid()) && usedItem(player, stack);
    }

    boolean attack(ServerPlayerEntity player, Entity target) {
        return (phase == Phase.BRIEFING || phase == Phase.RUNNING) && isPlaying(player.getUuid()) && attacked(player, target);
    }

    /** Clickable choices in chat: each runs /answer with its word. */
    protected static void choices(ServerPlayerEntity player, String prompt, String... options) {
        MutableText line = Text.literal(prompt + " ").formatted(Formatting.GOLD);
        for (String option : options) {
            String[] parts = option.split("\\|", 2);
            String label = parts[0];
            String answer = parts.length > 1 ? parts[1] : parts[0];
            line.append(Text.literal("[" + label + "]").styled(style -> style.withColor(Formatting.AQUA)
                    .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/answer " + answer))
                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("/answer " + answer)))));
            line.append(Text.literal(" "));
        }
        player.sendMessage(line, false);
    }

    /** One of this place's citizens: a face card's owner, playing their own game. */
    protected Contestant citizen(String name, BlockPos at) {
        Contestant c = Contestant.spawn(world, at, random, "citizen", null);
        if (c != null) {
            c.setCustomName(Text.literal(name));
            c.wits = 0.95F;
            seat(c);
        }
        return c;
    }

    boolean touch(ServerPlayerEntity player, Entity target) {
        return phase == Phase.RUNNING && isPlaying(player.getUuid()) && touched(player, target);
    }

    void answer(ServerPlayerEntity player, String answer) {
        if (isPlaying(player.getUuid()) && (phase == Phase.BRIEFING || phase == Phase.RUNNING)) {
            answered(player, answer);
        }
    }

    /** Puts {@code who} back to normal after anything a game did to them (floating, effects). */
    protected static void unfloat(LivingEntity who) {
        if (who.hasNoGravity()) {
            who.setNoGravity(false);
        }
    }
}
