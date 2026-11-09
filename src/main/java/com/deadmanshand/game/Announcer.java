package com.deadmanshand.game;

import com.deadmanshand.DeadMansHand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The announcer's voice: the calm woman's voice from every speaker in the venue, as in the show. Every line she has a
 * recording of (the rules of every game, the briefing, the clock, the fixed announcements) is a sound of its own; the
 * recordings are stereo, so they're heard the same wherever you stand, like a public address. Lines with names in them
 * have no recording: those are only written.
 */
public final class Announcer {
    private static final Map<String, Integer> INDEX = new HashMap<>();
    private static final SoundEvent[] SOUNDS = new SoundEvent[AnnouncerLines.KEYS.length];

    static {
        for (int i = 0; i < AnnouncerLines.KEYS.length; i++) {
            INDEX.put(AnnouncerLines.KEYS[i], i);
        }
    }

    private Announcer() {
    }

    /** At start-up (on both sides): one sound for each recording. */
    public static void register() {
        for (int i = 0; i < SOUNDS.length; i++) {
            Identifier id = DeadMansHand.id(String.format(Locale.ROOT, "announcer.a%03d", i));
            SOUNDS[i] = Registry.register(Registry.SOUND_EVENT, id, new SoundEvent(id));
        }
    }

    static String key(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The recording of {@code text}, or -1 if she has none. */
    static int clip(String text) {
        Integer i = INDEX.get(key(text));
        return i == null ? -1 : i;
    }

    /** How long a recording lasts, in ticks. */
    static int ticks(int clip) {
        return clip < 0 ? 0 : AnnouncerLines.TICKS[clip];
    }

    /** How long reading {@code text} out takes: its recording, or as long as it would take to say. */
    static int readingTicks(String text) {
        int clip = clip(text);
        return clip >= 0 ? ticks(clip) : 20 + text.length() * 3 / 2;
    }

    static void play(ServerPlayerEntity player, int clip) {
        if (clip >= 0 && clip < SOUNDS.length && SOUNDS[clip] != null) {
            player.playSound(SOUNDS[clip], SoundCategory.VOICE, 1.0F, 1.0F);
        }
    }
}
