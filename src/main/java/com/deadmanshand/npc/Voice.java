package com.deadmanshand.npc;

import com.deadmanshand.Config;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Visa;
import com.deadmanshand.game.Game;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the other players say, written by a real AI when one is set up (Groq's free models, or Ollama on your own
 * computer), otherwise by {@link Lines}. The game never waits for it. Server thread, except the HTTP callbacks, which
 * hop back onto it.
 */
public final class Voice {
    private static final URI GROQ = URI.create("https://api.groq.com/openai/v1/chat/completions");
    /** Google AI Studio's Gemini API, through its OpenAI-style door (same requests and answers as Groq's). */
    private static final URI GEMINI = URI.create("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
    /** Gemini models go in the list as "gemini:" plus the model's name, so they're never mixed up with Groq's. */
    private static final String G = "gemini:";
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"");
    private static final Duration GROQ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(45);
    /** Lines nobody asked for (reactions, remarks) may use the AI this often a minute; the rest is kept for answering you and for deciding. */
    private static final int AMBIENT_PER_MINUTE = 9;
    /** What a request is for: answering a player comes first, then a stranger making up their mind, then remarks. */
    static final int AMBIENT = 0;
    static final int DECIDE = 1;
    static final int REPLY = 2;
    private static final int OLLAMA_PER_MINUTE = 8;
    private static final Pattern TRY_AGAIN = Pattern.compile("try again in (?:(\\d+)m)?([\\d.]+)s");
    private static final Pattern REFUSAL = Pattern.compile("^(i'?m sorry|sorry, (but )?i can|i can'?t (help|assist|do)|i cannot|as an ai|i won'?t)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> RESTING = new HashMap<>();
    private static final Deque<Long> CLOUD_RECENT = new ArrayDeque<>();
    private static final Deque<Long> DECIDE_RECENT = new ArrayDeque<>();
    /** Strangers whose decision is with the AI right now. */
    private static final Set<UUID> DECIDING = new HashSet<>();
    private static final Deque<Long> OLLAMA_RECENT = new ArrayDeque<>();
    private static final Set<UUID> BUSY = new HashSet<>();
    /** Answers to players in flight, and the latest thing said to someone while they were still answering. */
    private static final Set<UUID> REPLYING = new HashSet<>();
    private static final Map<UUID, Object[]> PENDING = new HashMap<>();
    /** Tokens each model has been sent in the last minute: (time, tokens). */
    private static final Map<String, Deque<long[]>> SPENT = new HashMap<>();
    private static long toldLimitsAt;
    private static final Map<UUID, Deque<String[]>> TALK = new HashMap<>();
    private static boolean groqRejected;
    private static boolean geminiRejected;
    private static long ollamaDownUntil;
    private static int ollamaBusy;
    /** Ollama has answered at least once, so it's really there. */
    private static boolean ollamaSeen;
    @Nullable
    private static MinecraftServer server;

    private Voice() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            BUSY.clear();
            DECIDING.clear();
            REPLYING.clear();
            PENDING.clear();
            TALK.clear();
            RESTING.clear();
            ollamaBusy = 0;
        });
    }

    /** Every model there's a key for, in the order to try them: Gemini's then Groq's, or the other way round (ai_first). */
    private static List<String> models() {
        List<String> groq = new ArrayList<>();
        if (!Config.groqKey.isEmpty() && !groqRejected) {
            for (String name : Config.groqModels.split(",")) {
                if (!name.isBlank()) {
                    groq.add(name.trim());
                }
            }
        }
        List<String> gemini = new ArrayList<>();
        if (!Config.geminiKey.isEmpty() && !geminiRejected) {
            for (String name : Config.geminiModels.split(",")) {
                if (!name.isBlank()) {
                    gemini.add(G + name.trim().replaceFirst("^models/", ""));
                }
            }
        }
        List<String> list = new ArrayList<>("groq".equals(Config.aiFirst) ? groq : gemini);
        list.addAll("groq".equals(Config.aiFirst) ? gemini : groq);
        return list;
    }

    // --- what they say -------------------------------------------------------------------------------------------

    /** A line for the moment: {@code moment} says what just happened; {@code fallback} is said if there's no AI. */
    public static void line(Contestant who, String moment, String fallback) {
        String user = "(" + moment + ") Say your next line, out loud, to the people around you.";
        if (!ask(who, null, user, false, reply -> act(who, reply != null ? reply : fallback))) {
            act(who, fallback);
        }
    }

    private static final Pattern TAG = Pattern.compile("\\[\\s*([A-Za-z][A-Za-z0-9 _-]{0,24})\\s*\\]");

    /**
     * Says {@code reply} and does what it says: a tag at the end ("[LEFT]") is a decision the game carries out, and a line
     * that plainly commits to something without the tag counts too. Returns what was said, without the tag.
     */
    static String act(Contestant who, String reply) {
        String tag = null;
        Matcher matcher = TAG.matcher(reply);
        while (matcher.find()) {
            tag = matcher.group(1).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        }
        String said = TAG.matcher(reply).replaceAll("").replaceAll("\\s+", " ").trim();
        if (!said.isEmpty()) {
            who.say(said);
        }
        Game game = who.game();
        String done = "no action";
        if (game != null && game.isPlaying(who.getUuid())) {
            // what they said out loud wins over the tag, if the two disagree: you heard the words
            String promised = game.understood(who, said);
            String choice = promised != null && game.offers(who, promised) ? promised : tag != null && game.offers(who, tag) ? tag : null;
            if (choice != null && game.offers(who, choice)) {
                game.choose(who, choice);
                done = "does " + choice;
            } else if (tag != null) {
                done = "tag " + tag + " not possible now";
            }
        }
        Diary.write((game == null ? "city" : game.kind.title) + " | " + who.name() + " says: \"" + said + "\" | " + done);
        return said;
    }

    /** {@code player} said something to {@code who}. */
    public static void reply(Contestant who, ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(thank|thanks|sorry|trust|together|help you|i'll help|we can do this|stay with me)\\b.*")) {
            who.trust(player.getUuid(), 0.08F);
        } else if (lower.matches(".*\\b(shut up|idiot|stupid|die|kill you|useless|coward)\\b.*")) {
            who.trust(player.getUuid(), -0.15F);
        }
        who.glance(player, 100);
        who.talkedTo(player.getUuid(), player.world.getTime());
        if (REPLYING.contains(who.getUuid())) {
            // still answering the last thing: answer this next, rather than talking over themselves
            PENDING.put(who.getUuid(), new Object[]{player, message});
            return;
        }
        // Asked to do something they can do: whether they will is up to them (and to how much they trust whoever asked)
        Game game = who.game();
        String asked = game != null && game.isPlaying(who.getUuid()) ? game.understood(who, lower) : null;
        boolean willing = asked != null && (who.trust(player.getUuid()) > 0.15F || who.nature == Contestant.Nature.TIMID
                || who.nature == Contestant.Nature.KIND || who.getRandom().nextFloat() < 0.35F);
        String fallback = asked != null ? (willing ? Lines.agree(who, game.allOptions(who).getOrDefault(asked,
                asked.replaceAll("_\\d+$", "_N").equals(asked) ? null : asked.toLowerCase(Locale.ROOT).replace('_', ' '))) + " [" + asked + "]" : Lines.refuse(who))
                : Lines.reply(who, player, lower);
        if (ask(who, player, message, true, reply -> {
            String said = act(who, reply != null ? reply : fallback);
            remember(who, player, message, said);
            Object[] next = PENDING.remove(who.getUuid());
            if (next != null && ((ServerPlayerEntity) next[0]).isAlive()) {
                reply(who, (ServerPlayerEntity) next[0], (String) next[1]);
            }
        })) {
            player.sendMessage(Text.literal(who.name() + " is thinking...").formatted(Formatting.GRAY, Formatting.ITALIC), true);
        } else {
            String said = act(who, fallback);
            remember(who, player, message, said);
        }
    }

    private static void remember(Contestant who, ServerPlayerEntity player, String said, String answered) {
        Deque<String[]> talk = TALK.computeIfAbsent(who.getUuid(), id -> new ArrayDeque<>());
        talk.addLast(new String[]{player.getEntityName() + ": " + said, answered});
        while (talk.size() > 6) {
            talk.pollFirst();
        }
    }

    // --- asking ----------------------------------------------------------------------------------------------

    private static boolean ask(Contestant who, @Nullable ServerPlayerEntity player, String user, boolean priority, Consumer<String> done) {
        if (server == null || (priority ? REPLYING : BUSY).contains(who.getUuid())) {
            return false;
        }
        int tier = priority ? REPLY : AMBIENT;
        long now = System.currentTimeMillis();
        prune(CLOUD_RECENT, now);
        prune(OLLAMA_RECENT, now);
        if (!priority && CLOUD_RECENT.size() >= AMBIENT_PER_MINUTE) {
            return false;       // keep the budget for answering people and for deciding
        }
        JsonArray cloudMessages = messages(who, player, user, true);
        int tokens = cloudMessages.toString().length() / 4 + 200;
        boolean ollamaFirst = "main".equals(Config.ollama);
        String model = ollamaFirst ? null : cloudModel(now, tier, tokens);
        boolean ollama = model == null && ollamaReady(now, tier);
        if (model == null && !ollama) {
            if (priority) {
                tellLimits(now);
            }
            return false;
        }
        (priority ? REPLYING : BUSY).add(who.getUuid());
        Consumer<String> finish = reply -> {
            (priority ? REPLYING : BUSY).remove(who.getUuid());
            if (who.isAlive()) {
                done.accept(reply);
            }
        };
        if (model != null) {
            if (!priority) {
                CLOUD_RECENT.addLast(now);
            }
            groq(cloudMessages, tokens, model, tier, false, finish);
        } else {
            OLLAMA_RECENT.addLast(now);
            ollama(messages(who, player, user, false), 70, false, finish);
        }
        return true;
    }

    // --- deciding ----------------------------------------------------------------------------------------------

    /** Whether the AI can make the strangers' decisions right now (it's switched on and there's somewhere to ask). */
    public static boolean canDecide() {
        if (server == null || !Config.aiDecides) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!"off".equals(Config.ollama) && now >= ollamaDownUntil && "main".equals(Config.ollama)) {
            return true;
        }
        for (String m : models()) {
            if (now >= RESTING.getOrDefault(m, 0L)) {
                return true;
            }
        }
        return !"off".equals(Config.ollama) && now >= ollamaDownUntil && ollamaSeen;
    }

    /** Whether {@code who}'s decision is with the AI right now. */
    public static boolean deciding(Contestant who) {
        return DECIDING.contains(who.getUuid());
    }

    /** How many decisions have gone to the AI in the last minute. */
    public static int decisionsLastMinute() {
        prune(DECIDE_RECENT, System.currentTimeMillis());
        return DECIDE_RECENT.size();
    }

    /**
     * Asks the AI what {@code who} does now. {@code done} gets the raw answer (several lines), or null if nobody could
     * answer. False if it couldn't be asked at all (no AI, or every free limit used up for the moment).
     */
    public static boolean decide(Contestant who, String system, String user, Consumer<String> done) {
        return decide(who, system, user, false, done);
    }

    /**
     * As {@link #decide(Contestant, String, String, Consumer)}; {@code important}: a real decision (a new choice in front
     * of them, someone speaking to them), which goes to the best thinker free, the way the replies do.
     */
    public static boolean decide(Contestant who, String system, String user, boolean important, Consumer<String> done) {
        if (server == null || DECIDING.contains(who.getUuid())) {
            return false;
        }
        long now = System.currentTimeMillis();
        prune(OLLAMA_RECENT, now);
        prune(DECIDE_RECENT, now);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        int tokens = messages.toString().length() / 4 + 260;
        boolean ollamaFirst = "main".equals(Config.ollama);
        String model = ollamaFirst ? null : cloudModel(now, important ? REPLY : DECIDE, tokens);
        boolean ollama = model == null && ollamaReady(now, DECIDE);
        if (model == null && !ollama) {
            return false;
        }
        DECIDING.add(who.getUuid());
        DECIDE_RECENT.addLast(now);
        Consumer<String> finish = reply -> {
            DECIDING.remove(who.getUuid());
            done.accept(who.isAlive() ? reply : null);
        };
        if (model != null) {
            groq(messages, tokens, model, DECIDE, true, finish);
        } else {
            OLLAMA_RECENT.addLast(now);
            ollama(messages, 150, true, finish);
        }
        return true;
    }

    /** Free limits used up everywhere: say so (now and then), so silence or stock answers aren't a mystery. */
    private static void tellLimits(long now) {
        if (server == null || models().isEmpty() || now - toldLimitsAt < 20L * 60L * 1000L) {
            return;
        }
        boolean allResting = true;
        for (String m : models()) {
            allResting &= now < RESTING.getOrDefault(m, 0L);
        }
        if (!allResting) {
            return;
        }
        toldLimitsAt = now;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal("[Dead Man's Hand] The free AI limits are used up for now, so the others are using their built-in "
                    + "thinking and lines until they reset. (A second free key, Groq or Google AI Studio, spreads the load.)").formatted(Formatting.GRAY), false);
        }
    }

    /** About how many tokens a minute a model's free tier allows. */
    private static int perMinute(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.startsWith(G)) {
            return 250000;
        }
        if (m.contains("scout")) {
            return 30000;
        }
        if (m.contains("70b")) {
            return 12000;
        }
        if (m.contains("kimi")) {
            return 10000;
        }
        if (m.contains("8b") || m.contains("instant")) {
            return 6000;
        }
        return 8000;
    }

    private static boolean room(String model, long now, int tokens) {
        Deque<long[]> spent = SPENT.computeIfAbsent(model, k -> new ArrayDeque<>());
        while (!spent.isEmpty() && now - spent.peekFirst()[0] > 60_000L) {
            spent.pollFirst();
        }
        long sum = 0;
        for (long[] s : spent) {
            sum += s[1];
        }
        return sum + tokens <= perMinute(model) * 0.85 && spent.size() < requestsPerMinute(model) * 0.85;
    }

    private static void prune(Deque<Long> recent, long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L) {
            recent.pollFirst();
        }
    }

    /** About how many requests a minute a model's free tier allows. */
    private static int requestsPerMinute(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.startsWith(G)) {
            return m.contains("lite") ? 15 : m.contains("pro") ? 5 : 10;     // Gemini's free tier: a handful a minute each
        }
        return m.contains("qwen") || m.contains("kimi") ? 60 : 30;
    }

    @Nullable
    private static String cloudModel(long now, int tier, int tokens) {
        List<String> order = models();
        if (tier == AMBIENT) {
            // remarks go to the small, cheap models first, saving the good ones' limits for conversations
            order.sort((a, b) -> Boolean.compare(!small(a), !small(b)));
        } else if (tier == DECIDE && order.size() > 1) {
            // decisions use everything but the best talker first, so it's still free when you speak to someone
            order.add(order.remove(0));
        }
        for (String model : order) {
            if (now >= RESTING.getOrDefault(model, 0L) && room(model, now, tokens)) {
                return model;
            }
        }
        return null;
    }

    private static boolean small(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.contains("8b") || m.contains("instant") || m.contains("20b") || m.startsWith(G) && m.contains("lite");
    }

    private static boolean ollamaReady(long now, int tier) {
        if ("off".equals(Config.ollama) || now < ollamaDownUntil) {
            return false;
        }
        return switch (tier) {
            case REPLY -> ollamaBusy < 2;
            case DECIDE -> ollamaBusy < ("main".equals(Config.ollama) ? 2 : 1);
            default -> ollamaBusy == 0 && OLLAMA_RECENT.size() < OLLAMA_PER_MINUTE;
        };
    }

    private static void groq(JsonArray messages, int tokens, String model, int tier, boolean raw, Consumer<String> done) {
        boolean gemini = model.startsWith(G);
        String id = gemini ? model.substring(G.length()) : model;
        JsonObject body = new JsonObject();
        body.addProperty("model", id);
        body.add("messages", messages);
        SPENT.computeIfAbsent(model, k -> new ArrayDeque<>()).addLast(new long[]{System.currentTimeMillis(), tokens});
        if (gemini) {
            // 2.5 models can skip thinking altogether; the newer ones always think a little, so leave room for it
            body.addProperty("reasoning_effort", id.contains("2.5") && !id.contains("pro") ? "none" : "low");
            body.addProperty("max_tokens", raw ? 2048 : 1536);
            body.addProperty("temperature", raw ? 0.9 : 0.8);
        } else if (model.startsWith("openai/gpt-oss")) {
            body.addProperty("reasoning_effort", "low");
            body.addProperty("include_reasoning", false);
            body.addProperty("max_completion_tokens", raw ? 480 : 380);
        } else {
            if (model.startsWith("qwen/")) {
                body.addProperty("reasoning_effort", "none");
                body.addProperty("reasoning_format", "hidden");
            }
            body.addProperty("max_completion_tokens", raw ? 300 : tier == REPLY ? 110 : 70);
            body.addProperty("temperature", raw ? 0.9 : 0.8);
        }
        Http.post(gemini ? GEMINI : GROQ, new String[]{"authorization", "Bearer " + (gemini ? Config.geminiKey : Config.groqKey)}, body.toString(),
                GROQ_TIMEOUT, (status, text, retryAfter) -> {
            String reply = status == 200 ? (raw ? unthink(openAiText(text)) : clean(openAiText(text))) : null;
            back(() -> {
                if (reply != null && !reply.isEmpty() && !REFUSAL.matcher(reply).find()) {
                    done.accept(reply);
                    return;
                }
                long now = System.currentTimeMillis();
                String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
                if (gemini && (status == 400 || status == 401 || status == 403) && (lower.contains("api key") || lower.contains("api_key"))) {
                    geminiRejected = true;
                    tellKeyRejected("Google AI Studio", "gemini_api_key");
                } else if (status == 429) {
                    RESTING.put(model, now + (gemini && lower.contains("perday") ? 2L * 3600L * 1000L : rest(retryAfter, text)));
                } else if ((status == 400 || status == 404 || status == 403) && lower.contains("model")) {
                    RESTING.put(model, now + 24L * 3600L * 1000L);
                    DeadMansHand.LOG.warn("Dead Man's Hand: {} won't run {} ({}); skipping it. Check {} in deadmanshand.properties",
                            gemini ? "Google AI Studio" : "Groq", id, status, gemini ? "gemini_models" : "groq_models");
                } else if (status == 401 && !gemini) {
                    groqRejected = true;
                    tellKeyRejected("Groq", "groq_api_key");
                } else if (status == 200) {
                    RESTING.put(model, now + 5L * 60L * 1000L);     // it refused: let the next model play the part
                } else {
                    RESTING.put(model, now + 20_000L);
                }
                // Straight on to the next model, or to Ollama
                String next = cloudModel(now, tier == AMBIENT ? AMBIENT : REPLY, tokens);
                if (next != null) {
                    groq(messages, tokens, next, tier, raw, done);
                } else if (!"off".equals(Config.ollama) && ollamaReady(now, tier)) {
                    ollama(messages, raw ? 150 : 70, raw, done);
                } else {
                    done.accept(null);
                }
            });
        });
    }

    /** An answer that may run to several lines: just without any thinking it wrote out. */
    @Nullable
    private static String unthink(@Nullable String reply) {
        return reply == null ? null : reply.replaceAll("(?s)<think>.*?</think>", "").replace("\r", "").trim();
    }

    /** How long a model rests after "too many requests": what Groq says, or a minute. */
    private static long rest(long retryAfterSeconds, @Nullable String text) {
        if (retryAfterSeconds > 0L) {
            return Math.min(retryAfterSeconds, 6L * 3600L) * 1000L + 500L;
        }
        if (text != null) {
            Matcher delay = RETRY_DELAY.matcher(text);
            if (delay.find()) {
                return Math.min(Long.parseLong(delay.group(1)), 6L * 3600L) * 1000L + 500L;     // Gemini says how long
            }
            Matcher matcher = TRY_AGAIN.matcher(text);
            if (matcher.find()) {
                double seconds = (matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) * 60 : 0) + Double.parseDouble(matcher.group(2));
                return (long) (seconds * 1000.0) + 500L;
            }
        }
        return 60_000L;
    }

    private static void tellKeyRejected(String who, String setting) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal("[Dead Man's Hand] " + who + " rejected your API key. Check " + setting + " in config/deadmanshand.properties.")
                    .formatted(Formatting.GRAY), false);
        }
    }

    private static void ollama(JsonArray messages, int predict, boolean raw, Consumer<String> done) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.ollamaModel);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "30m");
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", predict);
        options.addProperty("temperature", 0.85);
        options.addProperty("num_ctx", raw ? 4096 : 2048);
        body.add("options", options);
        ollamaBusy++;
        URI uri;
        try {
            uri = URI.create(Config.ollamaUrl + "/api/chat");
        } catch (IllegalArgumentException e) {
            ollamaBusy--;
            ollamaDownUntil = Long.MAX_VALUE;
            done.accept(null);
            return;
        }
        Http.post(uri, new String[0], body.toString(), OLLAMA_TIMEOUT, (status, text, retryAfter) -> {
            String reply = status == 200 ? (raw ? unthink(ollamaText(text)) : clean(ollamaText(text))) : null;
            back(() -> {
                ollamaBusy = Math.max(0, ollamaBusy - 1);
                if (status <= 0) {
                    ollamaDownUntil = System.currentTimeMillis() + 60_000L;     // not running: try again in a minute
                } else if (status == 200) {
                    ollamaSeen = true;
                }
                done.accept(reply != null && !reply.isEmpty() && !REFUSAL.matcher(reply).find() ? reply : null);
            });
        });
    }

    private static void back(Runnable action) {
        MinecraftServer current = server;
        if (current != null) {
            current.execute(action);
        }
    }

    // --- the prompt ------------------------------------------------------------------------------------------

    private static final String WORLD = "A thriller. One night fireworks filled the sky; when they stopped, everyone in the world was gone except a "
            + "few strangers. The only way to stay alive is to play the deadly games that open at night, each marked by a playing card: "
            + "clubs are teamwork games, diamonds games of the mind, hearts games of betrayal, spades games of the body; the number says how "
            + "hard. Clearing one adds days to your visa. When your visa runs out, a red laser falls from the sky and kills you; losing a "
            + "game kills you the same way. You live in this world: never call it a show, a video game or Minecraft.";
    private static final String WORLD_SHORT = "A thriller. Everyone vanished one night; the few left must win deadly games marked by playing cards "
            + "to add days to their visa. If the visa runs out, or you lose, a laser from the sky kills you. You live in this world; never "
            + "call it a game show or Minecraft.";
    private static final String HOW_REPLY = "How to answer: like a real, frightened, intelligent person who is actually listening. Answer exactly "
            + "what they said or asked, using what you know above: if they ask a question, answer it; if they ask your opinion or what to do, "
            + "give a real opinion and the reason for it; if they ask you to do something, say clearly whether you will, and why. Stay "
            + "consistent with what you've said and decided before. One to three short sentences, under 45 words, plain everyday words. "
            + "Let your feelings show. Use their name now and then. No quotation marks, no name label, no stage directions, never mention "
            + "AI. Dark talk is fine; never gory.";
    private static final String HOW = "How to talk: like a real, frightened person. One or two short sentences in plain everyday words, under "
            + "30 words. Answer exactly what was said to you. Let your feelings show. No quotation marks, no name label, no stage "
            + "directions, never mention AI. Dark talk is fine; never gory.";

    private static JsonArray messages(Contestant who, @Nullable ServerPlayerEntity player, String user, boolean rich) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", system(who, player, rich)));
        if (player != null) {
            Deque<String[]> talk = TALK.get(who.getUuid());
            if (talk != null) {
                int skip = rich ? 0 : Math.max(0, talk.size() - 3);
                for (String[] exchange : talk) {
                    if (skip-- > 0) {
                        continue;
                    }
                    messages.add(message("user", exchange[0]));
                    messages.add(message("assistant", exchange[1]));
                }
            }
            messages.add(message("user", player.getEntityName() + ": " + clip(user, 240)));
        } else {
            messages.add(message("user", user));
        }
        return messages;
    }

    private static String system(Contestant who, @Nullable ServerPlayerEntity player, boolean rich) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(rich ? WORLD : WORLD_SHORT).append("\n\n");
        prompt.append("You are ").append(who.name()).append(", ").append(who.years).append(". Before all this you were ").append(who.past)
                .append(". You are ").append(who.nature.text).append(". Your visa has ").append(Visa.describe(Math.max(0, who.visa)))
                .append(" left. Games you've survived: ").append(who.cleared).append(".\n");
        if (player != null) {
            float trust = who.trust(player.getUuid());
            prompt.append("You're talking to ").append(player.getEntityName()).append(", another player. ")
                    .append(trust > 0.4F ? "You trust them; they've been good to you." : trust < -0.2F ? "You don't trust them." : "You barely know them.")
                    .append('\n');
        }
        prompt.append("How you feel right now: ").append(who.feelings.describe()).append(" Let it show in how you talk.\n");
        int keep = rich ? 6 : 2;
        List<String> memories = new ArrayList<>();
        who.memories().forEach(memories::add);
        if (!memories.isEmpty()) {
            prompt.append("What you remember:\n");
            for (String memory : memories.subList(Math.max(0, memories.size() - keep), memories.size())) {
                prompt.append("- ").append(memory).append('\n');
            }
        }
        Game game = who.game();
        prompt.append(game != null ? "Right now: " + clip(game.situationNow(who), 700) : "Right now there's no game: you're in the empty streets, waiting for the next venue to light up.")
                .append('\n');
        if (game != null && !game.announced()) {
            prompt.append("You do NOT know what the game is or what its rules will be. Don't guess or name one; nobody has said.\n");
        }
        if (game != null && rich && !game.rulesBrief().isEmpty()) {
            prompt.append("The rules, as they were read out: ").append(clip(game.rulesBrief(), 500)).append('\n');
            String decided = game.decided(who);
            if (decided != null) {
                prompt.append("What you've already decided or said you'd do: ").append(decided).append('\n');
            }
        }
        if (rich) {
            List<String> near = new ArrayList<>();
            for (LivingEntity e : who.world.getEntitiesByClass(LivingEntity.class, who.getBoundingBox().expand(16.0),
                    e -> e != who && e.isAlive() && (e instanceof ServerPlayerEntity || e instanceof Contestant c && !c.isBody()))) {
                near.add(e instanceof Contestant c ? c.name() : e.getEntityName());
            }
            if (!near.isEmpty()) {
                prompt.append("Near you: ").append(String.join(", ", near.subList(0, Math.min(8, near.size())))).append(".\n");
            }
        }
        prompt.append('\n').append(player != null ? HOW_REPLY : HOW);
        Map<String, String> options = game != null && game.isPlaying(who.getUuid()) ? game.allOptions(who) : Map.of();
        if (!options.isEmpty()) {
            prompt.append("\n\nYou don't only talk: you act. If your line means you're doing one of these right now, or agreeing to do it, "
                    + "end the line with its tag in square brackets, exactly one tag, and you really will do it:\n");
            options.forEach((tag, meaning) -> prompt.append('[').append(tag).append("] ").append(meaning).append('\n'));
            prompt.append("If you're not doing any of them yet, use no tag. Never say you'll do something and leave its tag off, and never "
                    + "tag something you didn't say you'd do.");
        }
        return prompt.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    // --- reading answers -------------------------------------------------------------------------------------

    @Nullable
    private static String openAiText(@Nullable String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            return content == null || content.isJsonNull() ? null : content.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String ollamaText(@Nullable String json) {
        try {
            JsonElement content = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("message").get("content");
            return content == null || content.isJsonNull() ? null : content.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One line: no name label, no quotes, no thinking tags, no stage directions, not too long. */
    @Nullable
    static String clean(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        String line = reply.replaceAll("(?s)<think>.*?</think>", "").replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        line = line.replaceAll("\\*[^*]{0,60}\\*", "").replaceAll("\\([^)]{0,60}\\)", "").trim();
        int colon = line.indexOf(':');
        if (colon > 0 && colon < 20 && !line.substring(0, colon).contains(" ")) {
            line = line.substring(colon + 1).trim();
        }
        line = line.replace("\"", "").replace("“", "").replace("”", "").trim();
        return clip(line, 220);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3).trim() + "...";
    }

    /** Everything that touches Java's HTTP client. */
    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        interface Done {
            void done(int status, @Nullable String body, long retryAfterSeconds);
        }

        static void post(URI uri, String[] headers, String json, Duration timeout, Done done) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            for (int i = 0; i + 1 < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            CLIENT.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) -> {
                if (response == null) {
                    done.done(-1, null, 0L);
                    return;
                }
                long retry = 0L;
                try {
                    retry = (long) Math.ceil(Double.parseDouble(response.headers().firstValue("retry-after").orElse("0")));
                } catch (NumberFormatException ignored) {
                    // not a number
                }
                done.done(response.statusCode(), response.body(), retry);
            });
        }
    }
}
