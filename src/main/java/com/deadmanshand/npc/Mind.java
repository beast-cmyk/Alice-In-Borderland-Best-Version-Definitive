package com.deadmanshand.npc;

import com.deadmanshand.Config;
import com.deadmanshand.Visa;
import com.deadmanshand.game.Game;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The others' minds, when there's an AI to lend them one. In a game every stranger is asked, the way you'd ask a person,
 * what they do now: when there's something new to decide, every so often while they walk about, and whenever
 * something happens. They're told the rules, what has happened, what people have said, who's around them and what
 * they can do; they think, they may say something, and they do one thing. The game's own thinking only steps in when
 * the AI can't be reached, runs out of free requests, or takes too long.
 */
public final class Mind {
    /** How long the game's own thinking waits for the AI over one decision before it decides for them. */
    private static final int PATIENCE = 20 * 25;
    /** How often someone walking about thinks again, with nothing new to decide (ticks; plus a little). */
    private static final int WALKING_EVERY = 20 * 12;
    /** How often someone at a table, with a decision in front of them, thinks again after "not yet". */
    private static final int SEATED_EVERY = 20 * 7;
    /** Never sooner than this after their last question, whatever happens. */
    private static final int AT_LEAST = 20 * 3;
    /** "THINK: ... SAY: ... DO: ...", on three lines or one, with or without bold. */
    private static final String LABEL = "(?:THINK|THOUGHT|SAY|SAYS|DO|ACTION|Think|Thought|Say|Says|Do|Action)";
    private static final Pattern LINE = Pattern.compile("(?s)(?<![A-Za-z])(" + LABEL + ")\\s*\\**\\s*:\\s*\\**\\s*(.*?)\\s*(?=\\**\\s*(?<![A-Za-z])" + LABEL
            + "\\s*\\**\\s*:|$)");

    private static final class State {
        /** The game's own options right now, as a key, and since when. */
        String key = "";
        long keySince;
        long askedAt = -100000L;
        int events = -1;
        /** "Not yet"s to the decision in front of them. */
        int waits;
        /** The AI couldn't be asked about this decision: the game's own thinking takes it. */
        boolean failed;
        String thought = "";
        int jitter;
        /** Someone just spoke to them by name, and what they said; and when that last started them thinking. */
        String spokenTo;
        long pokedAt = -100000L;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private Mind() {
    }

    public static void forget(UUID id) {
        STATES.remove(id);
    }

    /** {@code who} spoke to {@code c} by name: they'll think about it (and answer, if they want) next. */
    public static void poke(Contestant c, String who, String line) {
        State s = state(c);
        long now = c.world.getTime();
        if (now - s.pokedAt < 20 * 8) {
            return;     // they're still answering the last one: two of them can't keep each other talking forever
        }
        s.pokedAt = now;
        s.spokenTo = who + " just said to you: \"" + clip(line, 200) + "\"";
    }

    private static State state(Contestant c) {
        return STATES.computeIfAbsent(c.getUuid(), id -> {
            State s = new State();
            s.jitter = c.getRandom().nextInt(80);
            return s;
        });
    }

    private static String keyOf(Map<String, String> options) {
        return String.join(",", new TreeSet<>(options.keySet()));
    }

    /** Brings what {@code c} is deciding up to date (the options may have changed since the last look). */
    private static State current(Game game, Contestant c, long now) {
        State s = state(c);
        String key = keyOf(game.options(c));
        if (!key.equals(s.key)) {
            s.key = key;
            s.keySince = now;
            s.waits = 0;
            s.failed = false;
        }
        return s;
    }

    /**
     * Whether the game's own thinking should leave {@code c}'s decision to the AI for now: it's being asked, or about to
     * be, and hasn't taken too long.
     */
    public static boolean holds(Game game, Contestant c, long now) {
        if (!Config.aiDecides) {
            return false;
        }
        if (Voice.deciding(c)) {
            return true;
        }
        State s = current(game, c, now);
        if (s.key.isEmpty() || s.failed || !Voice.canDecide()) {
            return false;
        }
        return now - s.keySince < PATIENCE && s.waits < 3;
    }

    /** Every tick a game runs, before the game's own thinking. */
    public static void tick(Game game) {
        if (!Config.aiDecides || !Voice.canDecide()) {
            return;
        }
        long now = game.world.getTime();
        if (now % 5 != 0) {
            return;
        }
        int room = Config.aiDecisionsPerMinute - Voice.decisionsLastMinute();
        if (room <= 0) {
            return;
        }
        // who most needs to think: something new to decide first, then something happening, then walking about
        List<Object[]> due = new ArrayList<>();
        int events = game.events();
        boolean walking = game.walksAbout();
        for (Contestant c : game.strangersPlaying()) {
            if (Voice.deciding(c) || c.isBody()) {
                continue;
            }
            State s = current(game, c, now);
            Map<String, String> all = game.allOptions(c);
            if (all.isEmpty() && s.spokenTo == null) {
                continue;
            }
            long since = now - s.askedAt;
            if (since < AT_LEAST) {
                continue;
            }
            int urgency;
            if (s.spokenTo != null) {
                urgency = 3;        // someone's talking to them
            } else if (!s.key.isEmpty() && s.askedAt < s.keySince) {
                urgency = 3;        // a new decision in front of them
            } else if (s.events != events && (walking || !s.key.isEmpty())) {
                urgency = 2;        // something has happened
            } else if (!s.key.isEmpty() && s.waits > 0 && since >= SEATED_EVERY) {
                urgency = 1;        // they said "not yet": ask again
            } else if (walking && since >= WALKING_EVERY + s.jitter) {
                urgency = 1;        // walking about: what now?
            } else {
                continue;
            }
            due.add(new Object[]{c, urgency, since});
        }
        due.sort((a, b) -> {
            int byUrgency = Integer.compare((Integer) b[1], (Integer) a[1]);
            return byUrgency != 0 ? byUrgency : Long.compare((Long) b[2], (Long) a[2]);
        });
        int sent = 0;
        boolean full = false;
        for (Object[] d : due) {
            Contestant c = (Contestant) d[0];
            State s = state(c);
            if (full) {
                // the AI can't take any more right now: anything waiting on it goes to the game's own thinking
                if ((Integer) d[1] == 3) {
                    s.failed = true;
                }
                continue;
            }
            if (sent >= Math.min(3, room)) {
                break;
            }
            if (ask(game, c, now, events)) {
                sent++;
            } else {
                full = true;
                if ((Integer) d[1] == 3) {
                    s.failed = true;
                }
                s.askedAt = now;
            }
        }
    }

    private static boolean ask(Game game, Contestant c, long now, int events) {
        State s = state(c);
        Map<String, String> options = game.allOptions(c);
        boolean urgent = !s.key.isEmpty() && (s.waits >= 2 || now - s.keySince > PATIENCE * 2 / 3);
        boolean gameWait = options.containsKey("WAIT");
        String system = prompt(game, c, options, urgent || gameWait, now);
        String user = (s.spokenTo != null ? s.spokenTo + ". " : "") + (urgent ? "What do you do now? You can't put it off any longer: choose one of them now."
                : "What do you do now?");
        boolean important = s.spokenTo != null || !s.key.isEmpty() && s.askedAt < s.keySince;
        s.spokenTo = null;
        String key = s.key;
        boolean sent = Voice.decide(c, system, user, important, reply -> {
            if (reply == null) {
                State st = state(c);
                if (st.key.equals(key)) {
                    st.failed = true;       // nobody could answer: the game's own thinking takes this one
                }
                return;
            }
            if (game.isPlaying(c.getUuid()) && c.isAlive()) {
                answered(game, c, reply, key);
            }
        });
        if (sent) {
            s.askedAt = now;
            s.events = events;
        }
        return sent;
    }

    /** What they thought, said and did. */
    static void answered(Game game, Contestant c, String reply, String askedKey) {
        State s = state(c);
        String think = null;
        String say = null;
        String act = null;
        Matcher m = LINE.matcher(reply);
        while (m.find()) {
            String label = m.group(1).toUpperCase(Locale.ROOT);
            String value = m.group(2).trim();
            if (label.startsWith("THINK") || label.startsWith("THOUGHT")) {
                think = value;
            } else if (label.startsWith("SAY")) {
                say = value;
            } else {
                act = value;
            }
        }
        if (think == null && say == null && act == null) {
            // didn't keep to the form: treat it as something said, and see if it commits to anything
            say = Voice.clean(reply);
        }
        if (think != null) {
            s.thought = clip(think, 200);
        }
        say = speech(say);
        if (say != null) {
            c.say(say);
        }
        String tag = act == null ? null : tag(act);
        if (tag != null && tag.endsWith("_N")) {
            tag = null;     // the placeholder itself, not a number: not a choice
        }
        long now = game.world.getTime();
        String current = keyOf(game.options(c));
        boolean stale = !current.equals(askedKey);
        String head = game.kind.title + " | " + c.name() + " thinks: " + (think == null ? "-" : think) + " | says: " + (say == null ? "-" : say) + " | ";
        // what they said out loud is what everyone heard: if it plainly commits to something, that's what they do,
        // whatever the DO line says (people believe what they hear, so words and deeds must never part)
        String promised = say == null ? null : game.understood(c, say);
        if (promised != null && !"WAIT".equals(promised) && game.offers(c, promised) && !promised.equals(tag)) {
            game.choose(c, promised);
            s.waits = 0;
            Diary.write(head + "does " + promised + " (what they said; their DO was " + tag + ")");
            return;
        }
        if (tag != null && game.offers(c, tag)) {
            game.choose(c, tag);
            s.waits = 0;
            Diary.write(head + "does " + tag);
            return;
        }
        boolean waiting = tag == null || "WAIT".equals(tag) || "NOTHING".equals(tag) || "NONE".equals(tag);
        // a tag a little off ("PICK 37", "Left door"): what they said they'd do
        String said = game.understood(c, (waiting || act == null ? "" : "I'll " + act + ". ") + (say == null ? "" : say));
        if (said != null && game.offers(c, said)) {
            game.choose(c, said);
            s.waits = 0;
            Diary.write(head + "does " + said + " (from \"" + act + "\")");
            return;
        }
        Diary.write(head + (tag == null || "WAIT".equals(tag) ? "waits" : "wanted " + tag + ", which isn't possible now"));
        if (!stale) {
            s.waits++;
        }
        if (tag != null && !"WAIT".equals(tag) && !"NOTHING".equals(tag) && !stale) {
            s.askedAt = now - SEATED_EVERY + 20;    // something that isn't on the list: ask again soon
        }
    }

    /** What they said, if they really said something. */
    private static String speech(String say) {
        if (say == null) {
            return null;
        }
        String line = Voice.clean(say);
        if (line == null) {
            return null;
        }
        String bare = line.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        if (bare.isEmpty() || bare.equals("nothing") || bare.equals("none") || bare.equals("silence") || bare.equals("staysquiet")
                || bare.equals("quiet") || bare.equals("na") || bare.startsWith("nothingout") || bare.startsWith("saynothing")) {
            return null;
        }
        return line.replaceAll("\\[[^\\]]{0,30}]", "").trim();
    }

    /** "[pick 37]", "PICK 37", "Left." become PICK_37 and LEFT. */
    static String tag(String act) {
        String t = act.replaceAll("[\\[\\]*`\"'.]", " ").trim();
        int cut = t.indexOf(" - ");
        if (cut > 0) {
            t = t.substring(0, cut);
        }
        for (char stop : new char[]{':', '(', ',', ';'}) {
            cut = t.indexOf(stop);
            if (cut > 0) {
                t = t.substring(0, cut);
            }
        }
        t = t.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return t.isEmpty() ? null : t;
    }

    // --- what they're told -------------------------------------------------------------------------------------

    private static final String WORLD = "A thriller. One night fireworks filled the sky; when they stopped, everyone in the world was gone except "
            + "a few strangers. To stay alive you have to play deadly games, each marked by a playing card. Clearing one adds days to your "
            + "visa; losing, or letting the visa run out, means a laser from the sky kills you. People have died in front of you. This is "
            + "your real life, not a show or a video game.";

    static String prompt(Game game, Contestant c, Map<String, String> options, boolean noWait, long now) {
        StringBuilder p = new StringBuilder(WORLD).append("\n\n");
        p.append("You are ").append(c.name()).append(", ").append(c.years).append(". Before all this you were ").append(c.past)
                .append(". You are ").append(c.nature.text).append(". ").append(wits(c)).append(" Your visa has ")
                .append(Visa.describe(Math.max(0, c.visa))).append(" left; games you've survived: ").append(c.cleared).append(".\n");
        p.append("How you feel right now: ").append(c.feelings.describe()).append('\n');
        List<String> memories = new ArrayList<>();
        c.memories().forEach(memories::add);
        if (!memories.isEmpty()) {
            p.append("You remember:\n");
            for (String memory : memories.subList(Math.max(0, memories.size() - 4), memories.size())) {
                p.append("- ").append(memory).append('\n');
            }
        }
        p.append("\nThe game: ").append(game.kind.title).append(". The rules, as they were read out: ").append(clip(game.rulesBrief(), 900)).append('\n');
        p.append("Right now: ").append(clip(game.situation(c), 900)).append('\n');
        List<String> recent = game.recent(now, 12);
        if (!recent.isEmpty()) {
            p.append("What has happened lately, oldest first:\n");
            for (String line : recent) {
                p.append("- ").append(line).append('\n');
            }
        }
        String around = around(game, c);
        if (!around.isEmpty()) {
            p.append("Around you: ").append(around).append('\n');
        }
        String decided = game.decided(c);
        if (decided != null) {
            p.append("What you've already decided or said you'd do: ").append(decided).append('\n');
        }
        State s = state(c);
        if (!s.thought.isEmpty()) {
            p.append("What you were thinking a moment ago: ").append(s.thought).append('\n');
        }
        p.append("\nYou are a real person in this place, not a piece in a game. Decide what you do now the way a real, intelligent "
                + "person would: reason it through properly from the rules and the facts (what you've seen, what's been said, what the "
                + "numbers are), work out what's most likely to keep you alive, then let who you are and how you feel push you one way "
                + "or the other. Fear makes people rush, freeze or cling to someone; grief makes them reckless or numb; anger makes them "
                + "blame and lash out; hope makes them brave. People change their minds, bluff, follow someone braver, or lie, if that's "
                + "who they are. Keep to what you said you'd do unless something has changed. Don't do anything pointless: if nothing "
                + "needs doing, stay where you are or stay with someone.\n");
        p.append("\nWhat you can do right now (choose exactly one):\n");
        options.forEach((tag, meaning) -> p.append('[').append(tag).append("] ").append(meaning).append('\n'));
        if (options.isEmpty() || !noWait && !options.containsKey("WAIT")) {
            p.append("[WAIT] don't commit to anything yet: keep watching and thinking\n");
        }
        p.append("\nAnswer in exactly three lines, nothing else:\n"
                + "THINK: what goes through your head, in two or three short sentences: what you notice, what you work out from the "
                + "rules and from what people have done, and what you feel about it\n"
                + "SAY: what you say out loud now, in under 25 plain words, or - to stay quiet. It must agree with your DO: never say "
                + "you'll do one thing and then do another, and don't say you'll do something unless your DO is that. Only speak when a real person would (if "
                + "someone has just spoken to you, answer them); say someone's name when you speak to them; don't give away a secret "
                + "choice unless you mean to. No stage directions.\n"
                + "DO: one tag from the list, exactly as written (for a tag ending in _N, put your number in place of N, like PICK_37)");
        return p.toString();
    }

    private static String wits(Contestant c) {
        float j = c.judgment();
        return j > 0.75F ? "You're sharp, and you think things through." : j > 0.5F ? "You're no fool." : j > 0.3F
                ? "You're not a quick thinker, and fear is getting in the way." : "You can hardly think straight right now.";
    }

    /** Who's around them, where, and what they're doing; and the dead. */
    private static String around(Game game, Contestant c) {
        List<String> people = new ArrayList<>();
        List<LivingEntity> near = new ArrayList<>(c.world.getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(40.0, 16.0, 40.0),
                e -> e != c && e.isAlive() && (e instanceof ServerPlayerEntity || e instanceof Contestant)));
        near.sort((a, b) -> Double.compare(a.squaredDistanceTo(c), b.squaredDistanceTo(c)));
        int bodies = 0;
        for (LivingEntity e : near) {
            if (e instanceof Contestant k && k.isBody()) {
                bodies++;
                continue;
            }
            if (people.size() >= 7 || e instanceof ServerPlayerEntity sp && sp.isSpectator()) {
                continue;
            }
            String name = e instanceof Contestant k ? k.name() : e.getEntityName();
            boolean in = game.isPlaying(e.getUuid());
            int metres = (int) Math.round(Math.sqrt(e.squaredDistanceTo(c)));
            double speed = e.getVelocity().horizontalLength();
            String doing = speed > 0.2 ? "running" : speed > 0.05 ? "walking" : "standing still";
            if (e instanceof Contestant k && k.role.equals("citizen")) {
                name += " (one of the citizens who run this game)";
            }
            people.add(name + " (" + metres + " m " + direction(c, e.getPos()) + ", " + doing + (in ? "" : ", not playing") + ")");
        }
        String text = String.join("; ", people);
        if (bodies > 0) {
            text += (text.isEmpty() ? "" : ". ") + (bodies == 1 ? "A dead body lies near you." : bodies + " dead bodies lie near you.");
        }
        return text;
    }

    private static String direction(LivingEntity from, Vec3d to) {
        double dx = to.x - from.getX();
        double dz = to.z - from.getZ();
        if (dx * dx + dz * dz < 2.0) {
            return "beside you";
        }
        double angle = Math.toDegrees(Math.atan2(-dx, dz)) - from.getHeadYaw();
        angle = ((angle % 360.0) + 540.0) % 360.0 - 180.0;
        double a = Math.abs(angle);
        String side = angle > 0 ? "right" : "left";
        return a < 30 ? "ahead of you" : a < 70 ? "ahead to your " + side : a < 120 ? "to your " + side : a < 155 ? "behind you to the " + side : "behind you";
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3).trim() + "...";
    }
}
