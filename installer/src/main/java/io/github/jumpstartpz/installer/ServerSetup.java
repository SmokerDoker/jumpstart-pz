package io.github.jumpstartpz.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.bitecheckpz.launcher.Platform;

/**
 * Jumpstart on a dedicated server (SteamCMD app 380870), plus what a server without a console needs:
 *
 * <ul>
 *   <li>Jumpstart.jar (the ZombieBuddy fork with Jumpstart built in) as the only Java agent: Linux
 *       {@code java/Jumpstart.jar} and {@code -javaagent} in ProjectZomboid64.json; Windows
 *       {@code Jumpstart.jar} and {@code -javaagent} in StartServer64.bat. A ZombieBuddy agent there
 *       goes: two agents would load every mod twice.</li>
 *   <li>the fork's mod folder ZombieBuddy (Lua, no jar, no Workshop item) in the server's Zomboid/mods</li>
 *   <li>{@code ZombieBuddy} in {@code Mods=} of the server's .ini (Jumpstart is no mod any more)</li>
 *   <li>with {@code headless}: ZombieBuddy option {@code policy=deny-new} (nobody can answer a question;
 *       Jumpstart itself is the agent and needs no approval)</li>
 * </ul>
 *
 * An install from before 0.2 (the fork as ZombieBuddy.jar, a Jumpstart mod) is moved to this layout.
 * {@link #repair} puts the agent back after SteamCMD replaced ProjectZomboid64.json with a game update.
 */
final class ServerSetup {
    private static final Pattern VM_ARGS = Pattern.compile("\"vmArgs\"\\s*:\\s*\\[");
    private static final Pattern AGENT_LINE = Pattern.compile("\\s*\"-javaagent:[^\"]*(?:ZombieBuddy|Jumpstart)\\.jar[^\"]*\",");
    private static final Pattern AGENT_ARG = Pattern.compile(" -javaagent:\\S*(?:ZombieBuddy|Jumpstart)\\.jar(?:=[^ \\r\\n]*)?");

    /**
     * @param serverDir  the dedicated server's install folder
     * @param name       the server name (its .ini); empty: the only .ini there is
     * @param zomboidDir the server's data folder (~/Zomboid, or what -cachedir= says)
     * @param agentArgs  the agent's (ZombieBuddy's) options; empty: hotswap=true (headless: hotswap=true,policy=deny-new)
     */
    record Options(Path serverDir, Optional<String> name, Path zomboidDir, Optional<String> agentArgs, boolean headless) {
        String args() {
            return agentArgs.orElse(headless ? "hotswap=true,policy=deny-new" : "hotswap=true");
        }
    }

    private final Platform.Os os;
    private final Options o;
    private final Texts t;
    private final Consumer<String> log;

    ServerSetup(Platform.Os os, Options o, Texts t, Consumer<String> log) {
        this.os = os;
        this.o = o;
        this.t = t;
        this.log = log;
    }

    private Path mods() {
        return o.zomboidDir().resolve("mods");
    }

    Path agentJar() {
        return javaDir().resolve("Jumpstart.jar");
    }

    /** The fork as Jumpstart before 0.2 put it there. */
    private Path oldAgentJar() {
        return javaDir().resolve("ZombieBuddy.jar");
    }

    private Path javaDir() {
        return os == Platform.Os.WINDOWS ? o.serverDir() : o.serverDir().resolve("java");
    }

    /** The file that holds the server's JVM options. */
    Path startConfig() throws IOException {
        if (os == Platform.Os.WINDOWS) {
            for (String bat : List.of("StartServer64.bat", "ProjectZomboidServer.bat")) {
                if (Files.isRegularFile(o.serverDir().resolve(bat))) {
                    return o.serverDir().resolve(bat);
                }
            }
            throw new IOException(t.get("server.notFound", o.serverDir(), "StartServer64.bat"));
        }
        Path json = o.serverDir().resolve("ProjectZomboid64.json");
        if (!Files.isRegularFile(json) || !Files.isDirectory(o.serverDir().resolve("java"))) {
            throw new IOException(t.get("server.notFound", o.serverDir(), "ProjectZomboid64.json, java/"));
        }
        return json;
    }

    Optional<String> installedVersion() {
        try {
            for (Path v : List.of(mods().resolve("ZombieBuddy").resolve(Setup.VERSION_FILE),
                    mods().resolve("Jumpstart").resolve(Setup.VERSION_FILE))) {
                if (Files.isRegularFile(v)) {
                    return Optional.of(Files.readString(v, StandardCharsets.UTF_8).strip());
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    boolean isInstalled() {
        return Setup.isFork(agentJar()) || (Setup.isFork(oldAgentJar()) && Files.isDirectory(mods().resolve("Jumpstart")));
    }

    void install(Payload payload) throws IOException {
        Path config = startConfig();
        Files.createDirectories(agentJar().getParent());
        if (Setup.isFork(oldAgentJar())) {
            Files.delete(oldAgentJar());
            log.accept(t.get("log.oldFork", oldAgentJar().getFileName()));
        }
        Files.copy(payload.jumpstartJar(), agentJar(), StandardCopyOption.REPLACE_EXISTING);
        log.accept(t.get("log.jar", agentJar().getParent()));
        repair(config);

        Files.createDirectories(mods());
        Path mod = mods().resolve("Jumpstart");
        if (Files.exists(mod)) {
            Setup.removeTree(mod);
            log.accept(t.get("log.modRemoved", mod));
        }
        Path zb = mods().resolve("ZombieBuddy");
        if (Files.exists(zb) && !Files.exists(zb.resolve(Setup.FORK_MARKER))) {
            Path backup = o.zomboidDir().resolve("jumpstart-backup/ZombieBuddy-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(backup.getParent());
            Files.move(zb, backup);
            log.accept(t.get("log.zbmodOwn", backup));
        }
        Setup.removeTree(zb);
        Setup.copyTree(payload.zombieBuddyMod(), zb);
        Files.writeString(zb.resolve(Setup.VERSION_FILE), payload.version() + "\n", StandardCharsets.UTF_8);
        log.accept(t.get("log.zbmod", zb));

        ini().ifPresentOrElse(ini -> {
            try {
                addToIni(ini);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, () -> log.accept(t.get("server.noIni", o.zomboidDir().resolve("Server"))));
    }

    /** Puts the agent into the start configuration if it is not there (after a game update). True if it changed it. */
    boolean repair() throws IOException {
        return repair(startConfig());
    }

    private boolean repair(Path config) throws IOException {
        String original = Files.readString(config, StandardCharsets.UTF_8);
        if (original.contains("Jumpstart.jar") && !original.contains("ZombieBuddy.jar")) {
            return false;
        }
        // a ZombieBuddy agent (Jumpstart before 0.2, or the server's own) goes: Jumpstart has it built in
        String text = withoutAgents(original);
        String changed;
        if (os == Platform.Os.WINDOWS) {
            // the .bat changes into its own folder first, so the relative path works with spaces in it
            int i = text.indexOf("java.exe\"");
            if (i < 0) {
                throw new IOException(t.get("server.noJava", config));
            }
            int end = i + "java.exe\"".length();
            changed = text.substring(0, end) + " -javaagent:Jumpstart.jar=" + o.args() + text.substring(end);
        } else {
            Matcher vm = VM_ARGS.matcher(text);
            if (!vm.find()) {
                throw new IOException(t.get("error.json"));
            }
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            changed = text.substring(0, vm.end()) + nl + "\t\t\"-javaagent:" + agentJar().toAbsolutePath().toString().replace('\\', '/')
                    + "=" + o.args() + "\"," + text.substring(vm.end());
        }
        Path backup = config.resolveSibling(config.getFileName() + Setup.BACKUP);
        if (!Files.exists(backup)) {
            Files.writeString(backup, original, StandardCharsets.UTF_8);
        }
        Files.writeString(config, changed, StandardCharsets.UTF_8);
        log.accept(t.get("server.agent", config.getFileName(), o.args()));
        return true;
    }

    void uninstall() throws IOException {
        Path config = startConfig();
        String text = Files.readString(config, StandardCharsets.UTF_8);
        String changed = withoutAgents(text);
        if (!changed.equals(text)) {
            Files.writeString(config, changed, StandardCharsets.UTF_8);
            log.accept(t.get("server.agentRemoved", config.getFileName()));
        }
        Files.deleteIfExists(config.resolveSibling(config.getFileName() + Setup.BACKUP));
        if (Setup.isFork(agentJar())) {
            Files.delete(agentJar());
            log.accept(t.get("log.jarRemoved"));
        }
        if (Setup.isFork(oldAgentJar())) {
            Files.delete(oldAgentJar());
            log.accept(t.get("log.oldFork", oldAgentJar().getFileName()));
        }
        Path mod = mods().resolve("Jumpstart");
        if (Files.exists(mod)) {
            Setup.removeTree(mod);
            log.accept(t.get("log.modRemoved", mod));
        }
        Path zb = mods().resolve("ZombieBuddy");
        if (Files.exists(zb.resolve(Setup.FORK_MARKER))) {
            Setup.removeTree(zb);
            log.accept(t.get("log.modRemoved", zb));
        }
        Optional<Path> ini = ini();
        if (ini.isPresent()) {
            String s = Files.readString(ini.get(), StandardCharsets.UTF_8);
            String out = s.lines().map(l -> l.startsWith("Mods=") ? removeMods(l, List.of("Jumpstart", "ZombieBuddy")) : l)
                    .reduce((a, b) -> a + lineEnd(s) + b).orElse("") + (s.endsWith("\n") ? lineEnd(s) : "");
            if (!out.equals(s)) {
                Files.writeString(ini.get(), out, StandardCharsets.UTF_8);
                log.accept(t.get("log.listOff", "Jumpstart, ZombieBuddy") + " (" + ini.get().getFileName() + ")");
            }
        }
    }

    /** The start configuration without Jumpstart's or a ZombieBuddy agent. */
    private String withoutAgents(String text) {
        return os == Platform.Os.WINDOWS ? AGENT_ARG.matcher(text).replaceAll("") : AGENT_LINE.matcher(text).replaceAll("");
    }

    /** The server's .ini: by name (created if new), else the only one there is. */
    private Optional<Path> ini() throws IOException {
        Path dir = o.zomboidDir().resolve("Server");
        if (o.name().isPresent()) {
            Path ini = dir.resolve(o.name().get() + ".ini");
            if (!Files.exists(ini)) {
                // a new server: the game fills in every other option on its first start
                Files.createDirectories(dir);
                Files.writeString(ini, "Mods=\nWorkshopItems=\n", StandardCharsets.UTF_8);
            }
            return Optional.of(ini);
        }
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> inis = files.filter(p -> p.getFileName().toString().endsWith(".ini")).toList();
            return inis.size() == 1 ? Optional.of(inis.get(0)) : Optional.empty();
        }
    }

    private void addToIni(Path ini) throws IOException {
        String s = Files.readString(ini, StandardCharsets.UTF_8);
        String nl = lineEnd(s);
        List<String> lines = new ArrayList<>(s.lines().toList());
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Mods=")) {
                lines.set(i, addMods(removeMods(lines.get(i), List.of("Jumpstart")), List.of("ZombieBuddy")));
                found = true;
            }
        }
        if (!found) {
            lines.add(addMods("Mods=", List.of("ZombieBuddy")));
        }
        String out = String.join(nl, lines) + nl;
        if (!out.equals(s)) {
            Path backup = ini.resolveSibling(ini.getFileName() + Setup.BACKUP);
            if (!Files.exists(backup)) {
                Files.copy(ini, backup);
            }
            Files.writeString(ini, out, StandardCharsets.UTF_8);
        }
        log.accept(t.get("server.ini", ini, lines.stream().filter(l -> l.startsWith("Mods=")).findFirst().orElse("")));
    }

    /** Adds mods to a Mods= line once, in the style of the list (Build 42 may write mods as \Id). */
    static String addMods(String line, List<String> ids) {
        String value = line.substring("Mods=".length());
        List<String> parts = new ArrayList<>(Stream.of(value.split(";")).filter(p -> !p.isEmpty()).toList());
        String prefix = parts.stream().anyMatch(p -> p.startsWith("\\")) ? "\\" : "";
        for (String id : ids) {
            if (parts.stream().noneMatch(p -> p.replaceFirst("^\\\\", "").equals(id))) {
                parts.add(prefix + id);
            }
        }
        return "Mods=" + String.join(";", parts);
    }

    static String removeMods(String line, List<String> ids) {
        String value = line.substring("Mods=".length());
        return "Mods=" + String.join(";", Stream.of(value.split(";"))
                .filter(p -> !p.isEmpty() && !ids.contains(p.replaceFirst("^\\\\", ""))).toList());
    }

    private static String lineEnd(String s) {
        return s.contains("\r\n") ? "\r\n" : "\n";
    }
}
