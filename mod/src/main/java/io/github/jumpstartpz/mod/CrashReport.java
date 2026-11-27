package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with what is needed to understand a crash or a mod that misbehaves: the game's console log,
 * the mods with their state, hashes and BiteCheck's word, what the crash protection caught, Java and
 * the system, and the JVM's own crash files from the game's folder. Written to
 * {@code Zomboid/jumpstart/reports/}; nothing is sent anywhere: the player decides whom to give it.
 *
 * <p>Big logs are cut to their end ({@link #MAX_LOG}): the end is where a crash is.
 */
final class CrashReport {
    static final int MAX_LOG = 8 * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private CrashReport() {
    }

    /**
     * @param zomboid   the game's data folder (console.txt, Logs)
     * @param gameDir   the game's install folder (hs_err_pid*.log), or null
     * @param mods      the mods: one line each, written by the caller
     * @param guard     what the crash protection caught (its event lines), may be empty
     */
    static Path write(Path zomboid, Path gameDir, String mods, String guard) throws IOException {
        Path dir = zomboid.resolve("jumpstart").resolve("reports");
        Files.createDirectories(dir);
        Path zip = dir.resolve("jumpstart-report-" + LocalDateTime.now().format(TIME) + ".zip");
        Path tmp = dir.resolve(zip.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            text(z, "README.txt", """
                    Jumpstart report: what Jumpstart, the game and the mods were doing.
                    mods.txt      the mods, their state, the hash of the jar that runs and BiteCheck's word
                    guard.txt     errors the crash protection caught in mods' Java patches
                    system.txt    Java, the system, Jumpstart's version
                    console.txt   the game's log (the end, if it is big)
                    logs/         the game's other logs of today
                    jvm/          the JVM's crash files from the game's folder (hs_err_pid*.log), the newest
                    It may name your computer's user folder in paths. Nothing was sent anywhere.
                    """);
            text(z, "mods.txt", mods);
            text(z, "guard.txt", guard == null || guard.isBlank() ? "(nothing caught)\n" : guard);
            text(z, "system.txt", system());
            tail(z, "console.txt", zomboid.resolve("console.txt"));
            Path logs = zomboid.resolve("Logs");
            if (Files.isDirectory(logs)) {
                String today = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yy"));
                try (Stream<Path> files = Files.list(logs)) {
                    for (Path f : files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().contains(today)).toList()) {
                        tail(z, "logs/" + f.getFileName(), f);
                    }
                }
            }
            if (gameDir != null && Files.isDirectory(gameDir)) {
                List<Path> crashes = new ArrayList<>();
                try (Stream<Path> files = Files.list(gameDir)) {
                    files.filter(f -> f.getFileName().toString().matches("(hs_err_pid|replay_pid)\\d+\\.log")).forEach(crashes::add);
                }
                crashes.sort(Comparator.comparing(CrashReport::modified).reversed());
                for (Path f : crashes.subList(0, Math.min(3, crashes.size()))) {
                    tail(z, "jvm/" + f.getFileName(), f);
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, zip);
        return zip;
    }

    private static long modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static String system() {
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch")) {
            sb.append(key).append(" = ").append(System.getProperty(key)).append('\n');
        }
        Runtime rt = Runtime.getRuntime();
        sb.append("memory max = ").append(rt.maxMemory() / (1024 * 1024)).append(" MB, used = ")
                .append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append(" MB\n");
        sb.append("processors = ").append(rt.availableProcessors()).append('\n');
        Package p = CrashReport.class.getPackage();
        sb.append("jumpstart = ").append(p == null || p.getImplementationVersion() == null ? "dev" : p.getImplementationVersion()).append('\n');
        return sb.toString();
    }

    private static void text(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** A file into the zip; only its last {@link #MAX_LOG} bytes when it is bigger. */
    private static void tail(ZipOutputStream z, String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        long size = Files.size(file);
        z.putNextEntry(new ZipEntry(name));
        try (var ch = Files.newByteChannel(file)) {
            if (size > MAX_LOG) {
                ch.position(size - MAX_LOG);
                z.write(("[... the first " + (size - MAX_LOG) + " bytes left out ...]\n").getBytes(StandardCharsets.UTF_8));
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
            while (ch.read(buf) > 0) {
                buf.flip();
                z.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
        } catch (IOException e) {
            z.write(("(cannot read " + file + ": " + e + ")\n").getBytes(StandardCharsets.UTF_8));
        }
        z.closeEntry();
    }
}
