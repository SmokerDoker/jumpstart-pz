package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import io.github.bitecheckpz.launcher.Platform;

/**
 * Puts Jumpstart in place and takes it out again, the same way the kit's scripts do:
 *
 * <ol>
 *   <li>the ZombieBuddy fork as {@code ZombieBuddy.jar} in the game's Java folder (a ZombieBuddy.jar
 *       of the player's own is kept as {@code ZombieBuddy.jar.before-jumpstart})</li>
 *   <li>Windows: {@code hotswap=true} in ProjectZomboid64.json; macOS and Linux: Steam launch options
 *       ({@link #launchOptions})</li>
 *   <li>the mod folders {@code Zomboid/mods/ZombieBuddy} (the fork: no Workshop subscription) and
 *       {@code Zomboid/mods/Jumpstart}</li>
 *   <li>both mods on in the main menu's mod list</li>
 * </ol>
 *
 * Files the installer changes get a copy {@code *.before-jumpstart} once.
 */
public final class Setup {
    static final String BACKUP = ".before-jumpstart";
    /** In the fork's mod folder: the installer replaces and removes only a folder with this file. */
    static final String FORK_MARKER = "jumpstart-fork.txt";
    private static final String FORK_CLASS = "me/zed_0xff/zombie_buddy/Hotswap.class";
    /** Byte for byte: localconfig.vdf is kept unchanged outside the launch options. */
    private static final Charset VDF = StandardCharsets.ISO_8859_1;

    public enum LaunchResult {
        /** Windows: the options are in ProjectZomboid64.json. */
        NOT_NEEDED,
        /** Set (or already right). */
        DONE,
        /** Steam must be closed first, or the player sets them by hand. */
        STEAM_RUNNING,
        /** No Steam account here has started Project Zomboid yet; the player sets them by hand. */
        NO_STEAM_CONFIG
    }

    /** What is in place now. */
    public record Status(boolean forkJar, boolean jumpstartMod, Optional<String> jumpstartVersion, boolean zombieBuddyMod,
            boolean launchReady, Optional<String> launchOptions, boolean biteCheck, boolean zombieBuddyFromWorkshop) {

        public boolean installed() {
            return forkJar && jumpstartMod && zombieBuddyMod && launchReady;
        }

        public boolean anything() {
            return forkJar || jumpstartMod;
        }
    }

    private final GameLocation loc;
    private final Texts t;
    private final Consumer<String> log;
    private final BooleanSupplier steamRunning;

    public Setup(GameLocation loc, Texts t, Consumer<String> log) {
        this(loc, t, log, () -> Steam.isRunning(loc.os()));
    }

    Setup(GameLocation loc, Texts t, Consumer<String> log, BooleanSupplier steamRunning) {
        this.loc = loc;
        this.t = t;
        this.log = log;
        this.steamRunning = steamRunning;
    }

    public GameLocation location() {
        return loc;
    }

    public Status status() {
        Path mod = loc.modsDir().resolve("Jumpstart");
        Path zbMod = loc.modsDir().resolve("ZombieBuddy");
        boolean launchReady;
        Optional<String> options = Optional.empty();
        if (windows()) {
            launchReady = read(loc.launchJson(), StandardCharsets.UTF_8).map(LaunchJson::hasHotswap).orElse(false);
        } else {
            List<String> all = currentLaunchOptions();
            launchReady = !all.isEmpty() && all.stream().allMatch(LaunchOptions::hasJumpstart);
            options = all.stream().findFirst();
        }
        return new Status(isFork(loc.zombieBuddyJar()), Files.isRegularFile(mod.resolve("42/mod.info")), modVersion(mod),
                Files.isRegularFile(zbMod.resolve("42/mod.info")) || loc.zombieBuddyFromWorkshop(), launchReady, options,
                Files.isDirectory(Path.of(System.getProperty("user.home"), ".bitecheck")), loc.zombieBuddyFromWorkshop());
    }

    /** Everything except the Steam launch options. */
    public void installFiles(Payload payload) throws IOException {
        Path jar = loc.zombieBuddyJar();
        Path jarBackup = sibling(jar, BACKUP);
        if (Files.isRegularFile(jar) && !isFork(jar) && !Files.exists(jarBackup)) {
            Files.copy(jar, jarBackup);
            log.accept(t.get("log.jarBackup", jarBackup.getFileName()));
        }
        copyFile(payload.zombieBuddyJar(), jar);
        log.accept(t.get("log.jar", loc.javaDir()));

        if (windows()) {
            Path json = loc.launchJson();
            String text = Files.readString(json, StandardCharsets.UTF_8);
            String changed = LaunchJson.withHotswap(text, jar);
            if (changed == null) {
                throw new IOException(t.get("error.json"));
            }
            if (!changed.equals(text)) {
                backupOnce(json);
                write(json, changed, StandardCharsets.UTF_8);
            }
            log.accept(t.get("log.json"));
        }

        Files.createDirectories(loc.modsDir());
        Path mod = loc.modsDir().resolve("Jumpstart");
        deleteTree(mod);
        copyTree(payload.jumpstartMod(), mod);
        log.accept(t.get("log.mod", mod));

        Path zbMod = loc.modsDir().resolve("ZombieBuddy");
        if (Files.exists(zbMod) && !Files.exists(zbMod.resolve(FORK_MARKER))) {
            Path target = loc.backupDir().resolve("ZombieBuddy");
            if (Files.exists(target)) {
                target = loc.backupDir().resolve("ZombieBuddy-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            }
            Files.createDirectories(loc.backupDir());
            Files.move(zbMod, target);
            log.accept(t.get("log.zbmodOwn", target));
        }
        deleteTree(zbMod);
        copyTree(payload.zombieBuddyMod(), zbMod);
        log.accept(t.get("log.zbmod", zbMod));

        Path list = loc.modList();
        String text = Files.isRegularFile(list) ? Files.readString(list, StandardCharsets.UTF_8)
                : (windows() ? ModList.EMPTY.replace("\n", "\r\n") : ModList.EMPTY);
        String changed = ModList.enable(text, List.of("ZombieBuddy", "Jumpstart"));
        if (changed == null) {
            log.accept(t.get("log.listMissing", list));
        } else {
            if (!changed.equals(text) || !Files.exists(list)) {
                if (Files.exists(list)) {
                    backupOnce(list);
                }
                write(list, changed, StandardCharsets.UTF_8);
            }
            log.accept(t.get("log.list"));
        }
        if (loc.zombieBuddyFromWorkshop()) {
            log.accept(t.get("log.workshop"));
        }
    }

    /** The launch options the player should have after installing (shown when they set them by hand). */
    public String suggestedLaunchOptions(boolean install) {
        String current = currentLaunchOptions().stream().findFirst().orElse("");
        return install ? LaunchOptions.withJumpstart(current) : LaunchOptions.withoutJumpstart(current, keepZombieBuddy());
    }

    /**
     * macOS and Linux: sets (or removes) Jumpstart's launch option in every Steam account that has
     * played Project Zomboid. Only while Steam is closed; Steam would overwrite the file on exit.
     */
    public LaunchResult launchOptions(boolean install) throws IOException {
        if (windows()) {
            return LaunchResult.NOT_NEEDED;
        }
        boolean keep = keepZombieBuddy();
        List<Path> files = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        String shown = null;
        boolean any = false;
        for (Path vdf : loc.localConfigs()) {
            String text = Files.readString(vdf, VDF);
            if (!LocalConfigVdf.hasGame(text)) {
                continue;
            }
            any = true;
            String current = LocalConfigVdf.launchOptions(text).orElse("");
            String wanted = install ? LaunchOptions.withJumpstart(current) : LaunchOptions.withoutJumpstart(current, keep);
            if (!wanted.equals(current)) {
                files.add(vdf);
                texts.add(LocalConfigVdf.withLaunchOptions(text, wanted).orElseThrow());
                shown = wanted;
            }
        }
        if (!any) {
            return LaunchResult.NO_STEAM_CONFIG;
        }
        if (files.isEmpty()) {
            log.accept(t.get("log.launchOk"));
            return LaunchResult.DONE;
        }
        if (steamRunning.getAsBoolean()) {
            return LaunchResult.STEAM_RUNNING;
        }
        for (int i = 0; i < files.size(); i++) {
            backupOnce(files.get(i));
            write(files.get(i), texts.get(i), VDF);
        }
        log.accept(t.get("log.launch", utf8(shown)));
        return LaunchResult.DONE;
    }

    /**
     * Removes Jumpstart. Call {@link #launchOptions}(false) first on macOS and Linux: ZombieBuddy.jar
     * is deleted only when nothing starts it any more.
     */
    public void uninstallFiles() throws IOException {
        Path mod = loc.modsDir().resolve("Jumpstart");
        if (Files.exists(mod)) {
            deleteTree(mod);
            log.accept(t.get("log.modRemoved", mod));
        }
        Path zbMod = loc.modsDir().resolve("ZombieBuddy");
        if (Files.exists(zbMod.resolve(FORK_MARKER))) {
            deleteTree(zbMod);
            log.accept(t.get("log.modRemoved", zbMod));
        }
        Path own = loc.backupDir().resolve("ZombieBuddy");
        if (Files.isDirectory(own) && !Files.exists(zbMod)) {
            Files.move(own, zbMod);
            log.accept(t.get("log.zbmodBack"));
        }

        Path list = loc.modList();
        if (Files.isRegularFile(list)) {
            String text = Files.readString(list, StandardCharsets.UTF_8);
            List<String> off = new ArrayList<>(List.of("Jumpstart"));
            if (!Files.exists(zbMod) && !loc.zombieBuddyFromWorkshop()) {
                off.add("ZombieBuddy");
            }
            String changed = text;
            for (String id : off) {
                changed = ModList.disable(changed, id);
            }
            if (!changed.equals(text)) {
                write(list, changed, StandardCharsets.UTF_8);
                log.accept(t.get("log.listOff", String.join(", ", off)));
            }
        }

        boolean stillStarted;
        if (windows()) {
            Path json = loc.launchJson();
            String text = Files.readString(json, StandardCharsets.UTF_8);
            String changed = LaunchJson.withoutHotswap(text);
            if (!changed.equals(text)) {
                write(json, changed, StandardCharsets.UTF_8);
                log.accept(t.get("log.jsonRestored"));
            }
            Files.deleteIfExists(sibling(json, BACKUP));
            stillStarted = LaunchJson.startsZombieBuddy(changed);
        } else {
            stillStarted = currentLaunchOptions().stream().anyMatch(LaunchOptions::startsZombieBuddy);
        }

        Path jar = loc.zombieBuddyJar();
        Path jarBackup = sibling(jar, BACKUP);
        if (Files.isRegularFile(jarBackup)) {
            moveFile(jarBackup, jar);
            log.accept(t.get("log.jarRestored"));
        } else if (isFork(jar)) {
            if (stillStarted) {
                log.accept(t.get("log.jarKept"));
            } else {
                deleteFile(jar);
                log.accept(t.get("log.jarRemoved"));
            }
        }
    }

    /** After uninstalling, ZombieBuddy stays only if the player had it before Jumpstart. */
    private boolean keepZombieBuddy() {
        return Files.isRegularFile(sibling(loc.zombieBuddyJar(), BACKUP));
    }

    private List<String> currentLaunchOptions() {
        List<String> result = new ArrayList<>();
        for (Path vdf : loc.localConfigs()) {
            read(vdf, VDF).filter(LocalConfigVdf::hasGame)
                    .ifPresent(text -> result.add(utf8(LocalConfigVdf.launchOptions(text).orElse(""))));
        }
        return result;
    }

    private boolean windows() {
        return loc.os() == Platform.Os.WINDOWS;
    }

    /** Text read byte for byte (ISO-8859-1) as the UTF-8 it is, for showing it. */
    private static String utf8(String latin1) {
        return latin1 == null ? "" : new String(latin1.getBytes(VDF), StandardCharsets.UTF_8);
    }

    static boolean isFork(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(FORK_CLASS) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The {@code modversion} in a mod folder's 42/mod.info. */
    static Optional<String> modVersion(Path modDir) {
        return read(modDir.resolve("42/mod.info"), StandardCharsets.UTF_8).flatMap(text -> text.lines()
                .filter(l -> l.startsWith("modversion=")).map(l -> l.substring("modversion=".length()).strip()).findFirst());
    }

    private static Optional<String> read(Path file, Charset charset) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, charset)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    private static void backupOnce(Path file) throws IOException {
        Path backup = sibling(file, BACKUP);
        if (!Files.exists(backup)) {
            Files.copy(file, backup);
        }
    }

    /** Through a temporary file next to it, so a half-written file never stays behind. */
    private void write(Path file, String text, Charset charset) throws IOException {
        Path tmp = sibling(file, ".jumpstart-tmp");
        Files.writeString(tmp, text, charset);
        moveFile(tmp, file);
    }

    private void copyFile(Path from, Path to) throws IOException {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            throw new IOException(t.get("error.inUse", to), e);
        }
    }

    private void moveFile(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            throw new IOException(t.get("error.inUse", to), e);
        }
    }

    private void deleteFile(Path file) throws IOException {
        try {
            Files.deleteIfExists(file);
        } catch (FileSystemException e) {
            throw new IOException(t.get("error.inUse", file), e);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                deleteFile(p);
            }
        }
    }
}
