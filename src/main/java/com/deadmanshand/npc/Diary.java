package com.deadmanshand.npc;

import com.deadmanshand.DeadMansHand;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * What the others decided, said and did, and why, written to logs/dead-mans-hand.log in your Minecraft folder. If
 * something they do looks wrong, that file says what they were thinking at the time: send it along with what you saw.
 */
public final class Diary {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long MAX = 3L * 1024L * 1024L;
    private static Path file;
    private static boolean broken;

    private Diary() {
    }

    public static void write(String line) {
        if (broken) {
            return;
        }
        try {
            if (file == null) {
                Path logs = FabricLoader.getInstance().getConfigDir().toAbsolutePath().getParent().resolve("logs");
                Files.createDirectories(logs);
                file = logs.resolve("dead-mans-hand.log");
                if (Files.exists(file) && Files.size(file) > MAX) {
                    Files.move(file, logs.resolve("dead-mans-hand.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, "\n===== Dead Man's Hand " + java.time.LocalDate.now() + " =====\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(file, "[" + LocalTime.now().format(TIME) + "] " + line.replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            broken = true;
            DeadMansHand.LOG.warn("Dead Man's Hand: can't write logs/dead-mans-hand.log: {}", e.toString());
        }
    }
}
