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
 * Puts Jumpstart in place and takes it out again:
 *
 * <ol>
 *   <li>{@code Jumpstart.jar} in the game's Java folder: the ZombieBuddy fork with Jumpstart built in,
 *       the game's only Java agent. A ZombieBuddy.jar of the player's own is kept as
 *       {@code ZombieBuddy.jar.before-jumpstart}: BiteCheck asks about every loader jar there, and
 *       two agents must not start.</li>
 *   <li>Windows: {@code jsNative.dll} next to it and {@code -agentlib:jsNative=hotswap=true} in
 *       ProjectZomboid64.json, instead of a ZombieBuddy agent (the game's exe cannot start
 *       {@code -javaagent} itself); macOS and Linux: Steam launch options ({@link #launchOptions})</li>
 *   <li>the mod folder {@code Zomboid/mods/ZombieBuddy} (the fork's Lua, without a jar; other mods
 *       require it), on in the main menu's mod list</li>
 * </ol>
 *
 * An install from before 0.2 (the fork as ZombieBuddy.jar, a mod folder Zomboid/mods/Jumpstart) is
 * moved to this layout. Files the installer changes get a copy {@code *.before-jumpstart} once.
 */
public final class Setup {
    static final String BACKUP = ".before-jumpstart";
    /** In the fork's mod folder: the installer replaces and removes only a folder with this file. */
    static final String FORK_MARKER = "jumpstart-fork.txt";
    /** In the fork's mod folder: the version the installer put there (for automatic updates). */
    static final String VERSION_FILE = "jumpstart-version.txt";
    private static final String FORK_CLASS = "me/zed_0xff/zombie_buddy/Hotswap.class";
    /** Byte for byte: localconfig.vdf is kept unchanged outside the launch options. */
    private static final Charset VDF = StandardCharsets.ISO_8859_1;

    public enum LaunchResult {
        /** Nothing to set: Windows without automatic updates (the options are in ProjectZomboid64.json). */
        NOT_NEEDED,
        /** Set (or already right). */
        DONE,
        /** Steam must be closed first, or the player sets them by hand. */
        STEAM_RUNNING,
        /** No Steam account here has started Project Zomboid yet; the player sets them by hand. */
        NO_STEAM_CONFIG
    }

    /** What is in place now. {@code oldLayout}: parts of an install from before 0.2. */
    public record Status(boolean jumpstartJar, Optional<String> jumpstartVersion, boolean zombieBuddyMod,
            boolean launchReady, Optional<String> launchOptions, boolean biteCheck, boolean zombieBuddyFromWorkshop,
            boolean autoUpdate, boolean oldLayout) {

        public boolean installed() {
            return jumpstartJar && zombieBuddyMod && launchReady;
        }

        public boolean anything() {
            return jumpstartJar || oldLayout;
        }
    }

    private final GameLocation loc;
    private final Texts t;
    private final Consumer<String> log;
    private final BooleanSupplier steamRunning;
    /** Jumpstart's wrapper for the launch options ({@code "<app>" --wrap}), or empty: no automatic updates. */
    private final Optional<String> wrapper;

    public Setup(GameLocation loc, Texts t, Consumer<String> log, Optional<String> wrapper) {
        this(loc, t, log, () -> Steam.isRunning(loc.os()), wrapper);
    }

    Setup(GameLocation loc, Texts t, Consumer<String> log, BooleanSupplier steamRunning, Optional<String> wrapper) {
        this.loc = loc;
        this.t = t;
        this.log = log;
        this.steamRunning = steamRunning;
        this.wrapper = wrapper;
    }

    public GameLocation location() {
        return loc;
    }

    private Path zombieBuddyMod() {
        return loc.modsDir().resolve("ZombieBuddy");
    }

    /** The Jumpstart mod folder of an install from before 0.2. */
    private Path oldJumpstartMod() {
        return loc.modsDir().resolve("Jumpstart");
    }

    public Status status() {
        boolean launchReady;
        List<String> all = currentLaunchOptions();
        if (windows()) {
            launchReady = Files.isRegularFile(loc.nativeLoader())
                    && read(loc.launchJson(), StandardCharsets.UTF_8).map(LaunchJson::hasJumpstart).orElse(false);
        } else {
            launchReady = !all.isEmpty() && all.stream().allMatch(LaunchOptions::hasJumpstart);
        }
        boolean autoUpdate = !all.isEmpty() && all.stream().allMatch(LaunchOptions::hasWrapper);
        boolean oldLayout = isFork(loc.zombieBuddyJar()) || Files.isDirectory(oldJumpstartMod());
        return new Status(isFork(loc.jumpstartJar()), installedVersion(),
                Files.isRegularFile(zombieBuddyMod().resolve("42/mod.info")) || loc.zombieBuddyFromWorkshop(), launchReady,
                all.stream().findFirst(), Files.isDirectory(Path.of(System.getProperty("user.home"), ".bitecheck")),
                loc.zombieBuddyFromWorkshop(), autoUpdate, oldLayout);
    }

    /** The version the installer (or an automatic update) put in place; empty for older installs. */
    public Optional<String> installedVersion() {
        return read(zombieBuddyMod().resolve(VERSION_FILE), StandardCharsets.UTF_8)
                .or(() -> read(oldJumpstartMod().resolve(VERSION_FILE), StandardCharsets.UTF_8))
                .map(String::strip).filter(v -> !v.isEmpty())
                .or(() -> modVersion(oldJumpstartMod()));
    }

    /** True if Jumpstart is in place, also as installed before 0.2 (so an update moves it to the new layout). */
    public boolean isInstalled() {
        return isFork(loc.jumpstartJar()) || (isFork(loc.zombieBuddyJar()) && Files.isDirectory(oldJumpstartMod()));
    }

    /**
     * After a game update Steam may have replaced ProjectZomboid64.json (Windows): puts Jumpstart's
     * agent back if Jumpstart.jar is in place. Also brings an install of 0.4 or 0.5.0 (which wrote
     * {@code -javaagent}, so the game did not start) to the loader, taken from the payload inside
     * this installer: their automatic update installs the new payload with their own code. True if
     * it changed something.
     */
    public boolean repair() throws IOException {
        if (!windows() || !isFork(loc.jumpstartJar())) {
            return false;
        }
        Optional<Path> loader = Optional.empty();
        if (!Files.isRegularFile(loc.nativeLoader())) {
            Optional<Payload> bundled = Payload.bundled();
            loader = bundled.map(Payload::nativeLoader).filter(Files::isRegularFile);
        }
        return repair(loader);
    }

    /** @param loader jsNative.dll to put in place if it is missing (empty: it is there, or none to hand) */
    boolean repair(Optional<Path> loader) throws IOException {
        if (!windows() || !isFork(loc.jumpstartJar())) {
            return false;
        }
        boolean changed = false;
        if (!Files.isRegularFile(loc.nativeLoader())) {
            if (loader.isEmpty()) {
                return false; // without the loader the game would not start; leave the json as it is
            }
            copyFile(loader.get(), loc.nativeLoader());
            log.accept(t.get("log.native", loc.javaDir()));
            changed = true;
        }
        Path json = loc.launchJson();
        String text = Files.readString(json, StandardCharsets.UTF_8);
        LaunchJson.Change c = LaunchJson.withJumpstart(text);
        if (c == null || c.json().equals(text)) {
            return changed;
        }
        write(json, c.json(), StandardCharsets.UTF_8);
        recordReplaced(c.removed());
        log.accept(t.get("log.json"));
        return true;
    }

    /** Everything except the Steam launch options, and ZombieBuddy on in the mod list. */
    public void installFiles(Payload payload) throws IOException {
        installFiles(payload, true);
    }

    /**
     * Everything except the Steam launch options.
     *
     * @param enableMods false for an automatic update: the player's mod list stays as it is (only an
     *                   old Jumpstart entry goes, as its folder is gone)
     */
    public void installFiles(Payload payload, boolean enableMods) throws IOException {
        if (windows() && !Files.isRegularFile(payload.nativeLoader())) {
            throw new IOException(t.get("error.native"));
        }
        moveAside(loc.zombieBuddyJar());
        moveAside(sibling(loc.zombieBuddyJar(), ".new")); // a ZombieBuddy update waiting for zbNative
        Path jar = loc.jumpstartJar();
        copyFile(payload.jumpstartJar(), jar);
        log.accept(t.get("log.jar", loc.javaDir()));

        if (windows()) {
            if (!sameContent(payload.nativeLoader(), loc.nativeLoader())) {
                copyFile(payload.nativeLoader(), loc.nativeLoader());
            }
            log.accept(t.get("log.native", loc.javaDir()));
            Path json = loc.launchJson();
            String text = Files.readString(json, StandardCharsets.UTF_8);
            LaunchJson.Change c = LaunchJson.withJumpstart(text);
            if (c == null) {
                throw new IOException(t.get("error.json"));
            }
            if (!c.json().equals(text)) {
                backupOnce(json);
                write(json, c.json(), StandardCharsets.UTF_8);
                recordReplaced(c.removed());
            }
            log.accept(t.get("log.json"));
        }

        Files.createDirectories(loc.modsDir());
        if (Files.exists(oldJumpstartMod())) {
            deleteTree(oldJumpstartMod());
            log.accept(t.get("log.modRemoved", oldJumpstartMod()));
        }
        Path zbMod = zombieBuddyMod();
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
        write(zbMod.resolve(VERSION_FILE), payload.version() + "\n", StandardCharsets.UTF_8);
        log.accept(t.get("log.zbmod", zbMod));

        Path list = loc.modList();
        if (!enableMods && !Files.isRegularFile(list)) {
            return;
        }
        String text = Files.isRegularFile(list) ? Files.readString(list, StandardCharsets.UTF_8)
                : (windows() ? ModList.EMPTY.replace("\n", "\r\n") : ModList.EMPTY);
        String withoutOld = ModList.disable(text, "Jumpstart");
        String changed = enableMods ? ModList.enable(withoutOld, List.of("ZombieBuddy")) : withoutOld;
        if (changed == null) {
            log.accept(t.get("log.listMissing", list));
        } else {
            if (!changed.equals(text) || !Files.exists(list)) {
                if (Files.exists(list)) {
                    backupOnce(list);
                }
                write(list, changed, StandardCharsets.UTF_8);
            }
            if (enableMods) {
                log.accept(t.get("log.list"));
            }
        }
        if (loc.zombieBuddyFromWorkshop()) {
            log.accept(t.get("log.workshop"));
        }
    }

    /**
     * Moves a ZombieBuddy jar out of the game folder: the player's own is kept as
     * {@code *.before-jumpstart} (a newer one replaces an older copy); the fork an older Jumpstart
     * put there is deleted.
     */
    private void moveAside(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            return;
        }
        if (isFork(jar)) {
            deleteFile(jar);
            log.accept(t.get("log.oldFork", jar.getFileName()));
            return;
        }
        Path backup = sibling(jar, BACKUP);
        moveFile(jar, backup);
        log.accept(t.get("log.jarBackup", backup.getFileName()));
    }

    /** Adds the ZombieBuddy entries taken out of ProjectZomboid64.json to the record, for uninstalling. */
    private void recordReplaced(List<String> removed) throws IOException {
        if (removed.isEmpty()) {
            return;
        }
        List<String> all = new ArrayList<>(replaced());
        for (String r : removed) {
            if (!all.contains(r)) {
                all.add(r);
            }
        }
        write(loc.replacedAgents(), String.join("\n", all) + "\n", StandardCharsets.UTF_8);
    }

    private List<String> replaced() {
        return read(loc.replacedAgents(), StandardCharsets.UTF_8)
                .map(s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).toList()).orElse(List.of());
    }

    /** The launch options the player should have after installing (shown when they set them by hand). */
    public String suggestedLaunchOptions(boolean install) {
        return wanted(currentLaunchOptions().stream().findFirst().orElse(""), install, keepZombieBuddy());
    }

    /**
     * Jumpstart's part of the launch options: on macOS and Linux Jumpstart's agent with hotswap=true
     * (Windows has it in ProjectZomboid64.json, so a ZombieBuddy agent in Steam's options goes);
     * everywhere Jumpstart's wrapper for automatic updates, if wanted.
     */
    private String wanted(String current, boolean install, boolean keepZombieBuddy) {
        String s = current;
        if (!windows()) {
            s = install ? LaunchOptions.withJumpstart(s) : LaunchOptions.withoutJumpstart(s, keepZombieBuddy);
        } else if (install) {
            s = LaunchOptions.withoutZombieBuddy(s);
        }
        if (install && wrapper.isPresent()) {
            s = LaunchOptions.withWrapper(s, wrapper.get());
        } else if (!install) {
            s = LaunchOptions.withoutWrapper(s);
        }
        return s;
    }

    /**
     * Sets (or removes) Jumpstart's launch options in every Steam account that has played Project
     * Zomboid: on macOS and Linux Jumpstart's agent with hotswap=true, everywhere the wrapper for
     * automatic updates. Only while Steam is closed; Steam would overwrite the file on exit.
     */
    public LaunchResult launchOptions(boolean install) throws IOException {
        if (windows() && install && wrapper.isEmpty()
                && currentLaunchOptions().stream().noneMatch(LaunchOptions::startsZombieBuddy)) {
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
            String wanted = wanted(current, install, keep);
            if (!wanted.equals(current)) {
                files.add(vdf);
                texts.add(LocalConfigVdf.withLaunchOptions(text, wanted).orElseThrow());
                shown = wanted;
            }
        }
        if (!any) {
            // Windows works without launch options (ProjectZomboid64.json); only the updates need them
            return windows() && !install ? LaunchResult.NOT_NEEDED : LaunchResult.NO_STEAM_CONFIG;
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
     * Removes Jumpstart, also one installed before 0.2. Call {@link #launchOptions}(false) first on
     * macOS and Linux: Jumpstart.jar is deleted only when nothing starts it any more.
     */
    public void uninstallFiles() throws IOException {
        Path mod = oldJumpstartMod();
        if (Files.exists(mod)) {
            deleteTree(mod);
            log.accept(t.get("log.modRemoved", mod));
        }
        Path zbMod = zombieBuddyMod();
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

        boolean jumpstartStarted;
        boolean zombieBuddyStarted;
        if (windows()) {
            Path json = loc.launchJson();
            String text = Files.readString(json, StandardCharsets.UTF_8);
            String changed = LaunchJson.withoutJumpstart(text, replaced());
            if (!changed.equals(text)) {
                write(json, changed, StandardCharsets.UTF_8);
                log.accept(t.get("log.jsonRestored"));
            }
            Files.deleteIfExists(loc.replacedAgents());
            Files.deleteIfExists(sibling(json, BACKUP));
            jumpstartStarted = false;
            zombieBuddyStarted = LaunchJson.startsLoader(changed);
        } else {
            List<String> options = currentLaunchOptions();
            jumpstartStarted = options.stream().anyMatch(LaunchOptions::startsJumpstart);
            zombieBuddyStarted = options.stream().anyMatch(LaunchOptions::startsZombieBuddy);
        }

        Path zbJar = loc.zombieBuddyJar();
        Path zbBackup = sibling(zbJar, BACKUP);
        if (Files.isRegularFile(zbBackup)) {
            moveFile(zbBackup, zbJar);
            log.accept(t.get("log.jarRestored"));
        } else if (isFork(zbJar)) {
            // the fork from Jumpstart before 0.2
            if (zombieBuddyStarted) {
                log.accept(t.get("log.zbJarKept"));
            } else {
                deleteFile(zbJar);
                log.accept(t.get("log.oldFork", zbJar.getFileName()));
            }
        }
        Path pending = sibling(zbJar, ".new");
        if (Files.isRegularFile(sibling(pending, BACKUP))) {
            moveFile(sibling(pending, BACKUP), pending);
        }

        Path jar = loc.jumpstartJar();
        if (Files.isRegularFile(jar)) {
            if (jumpstartStarted) {
                log.accept(t.get("log.jarKept"));
            } else {
                deleteFile(jar);
                log.accept(t.get("log.jarRemoved"));
            }
        }
        if (!Files.exists(jar)) {
            Files.deleteIfExists(loc.nativeLoader()); // Windows: the loader goes with the jar it starts
        }
    }

    /** True if both files exist and hold the same bytes (an update need not touch a loader the game may hold). */
    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.isRegularFile(a) && Files.isRegularFile(b) && Files.mismatch(a, b) == -1;
        } catch (IOException e) {
            return false;
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

    /** True if the jar holds the ZombieBuddy fork: Jumpstart.jar, or ZombieBuddy.jar from Jumpstart before 0.2. */
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

    /** Deletes a folder with everything in it (no "in use" message; for servers and updates). */
    static void removeTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
