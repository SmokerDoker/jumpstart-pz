package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The text changes: launch options, localconfig.vdf, ProjectZomboid64.json, default.txt. */
class EditsTest {

    // --- Steam launch options (macOS, Linux) ---

    @Test
    void launchOptionsFromNothing() {
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true --", LaunchOptions.withJumpstart(""));
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true --", LaunchOptions.withJumpstart(null));
    }

    @Test
    void launchOptionsWithPlainZombieBuddy() {
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true --", LaunchOptions.withJumpstart("-javaagent:ZombieBuddy.jar --"));
        assertEquals("-javaagent:ZombieBuddy.jar=policy=deny-new,hotswap=true --",
                LaunchOptions.withJumpstart("-javaagent:ZombieBuddy.jar=policy=deny-new --"));
    }

    @Test
    void launchOptionsKeepBiteChecksWrapper() {
        String bitecheck = "\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command% -javaagent:ZombieBuddy.jar --";
        String wanted = "\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command% -javaagent:ZombieBuddy.jar=hotswap=true --";
        assertEquals(wanted, LaunchOptions.withJumpstart(bitecheck));
        assertTrue(LaunchOptions.hasJumpstart(wanted));
        assertEquals(bitecheck, LaunchOptions.withoutJumpstart(wanted, true));
    }

    @Test
    void launchOptionsAlreadySet() {
        String s = "-javaagent:ZombieBuddy.jar=hotswap=true --";
        assertEquals(s, LaunchOptions.withJumpstart(s));
        assertTrue(LaunchOptions.hasJumpstart(s));
        assertFalse(LaunchOptions.hasJumpstart("-javaagent:ZombieBuddy.jar --"));
        assertFalse(LaunchOptions.hasJumpstart(""));
    }

    @Test
    void launchOptionsWithOtherOptions() {
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true -Xmx8g --", LaunchOptions.withJumpstart("-Xmx8g --"));
        assertEquals("-javaagent:ZombieBuddy.jar=hotswap=true -- -debug", LaunchOptions.withJumpstart("-debug"));
        assertEquals("gamemoderun %command% -javaagent:ZombieBuddy.jar=hotswap=true --", LaunchOptions.withJumpstart("gamemoderun %command%"));
        assertEquals("gamemoderun %command% -javaagent:ZombieBuddy.jar=hotswap=true -- -debug",
                LaunchOptions.withJumpstart("gamemoderun %command% -debug"));
    }

    @Test
    void launchOptionsUninstall() {
        assertEquals("", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true --", false));
        assertEquals("-javaagent:ZombieBuddy.jar --", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true --", true));
        assertEquals("-debug", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true -- -debug", false));
        assertEquals("gamemoderun %command%", LaunchOptions.withoutJumpstart("gamemoderun %command% -javaagent:ZombieBuddy.jar=hotswap=true --", false));
        assertEquals("-Xmx8g --", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true -Xmx8g --", false));
        assertEquals("-Xmx8g --", LaunchOptions.withoutJumpstart("-Xmx8g --", false));
    }

    // --- localconfig.vdf ---

    private static final String VDF = """
            "UserLocalConfigStore"
            {
            \t"Software"
            \t{
            \t\t"Valve"
            \t\t{
            \t\t\t"Steam"
            \t\t\t{
            \t\t\t\t"apps"
            \t\t\t\t{
            \t\t\t\t\t"892970"
            \t\t\t\t\t{
            \t\t\t\t\t\t"LaunchOptions"\t\t"-other"
            \t\t\t\t\t}
            \t\t\t\t\t"108600"
            \t\t\t\t\t{
            \t\t\t\t\t\t"LastPlayed"\t\t"1795381647"
            \t\t\t\t\t\t"cloud"
            \t\t\t\t\t\t{
            \t\t\t\t\t\t\t"last_sync_state"\t\t"synchronized"
            \t\t\t\t\t\t}
            \t\t\t\t\t\t"LaunchOptions"\t\t"\\"C:\\\\Users\\\\me\\\\BiteCheck.exe\\" --wrap %command% -agentlib:zbNative --"
            \t\t\t\t\t}
            \t\t\t\t}
            \t\t\t}
            \t\t}
            \t}
            \t"WebStorage"
            \t{
            \t\t"apps"
            \t\t{
            \t\t\t"108600"
            \t\t\t{
            \t\t\t\t"LaunchOptions"\t\t"-agentlib:zbNative --"
            \t\t\t}
            \t\t}
            \t}
            \t"apptickets"
            \t{
            \t\t"108600"\t\t"3200000004"
            \t}
            }
            """;

    @Test
    void vdfReadsTheOptionsSteamUses() {
        assertTrue(LocalConfigVdf.hasGame(VDF));
        assertEquals(Optional.of("\"C:\\Users\\me\\BiteCheck.exe\" --wrap %command% -agentlib:zbNative --"), LocalConfigVdf.launchOptions(VDF));
    }

    @Test
    void vdfWritesBothCopiesAndNothingElse() {
        String opts = "\"/opt/b c\\d\" --wrap %command% -javaagent:ZombieBuddy.jar=hotswap=true --";
        String out = LocalConfigVdf.withLaunchOptions(VDF, opts).orElseThrow();
        assertEquals(Optional.of(opts), LocalConfigVdf.launchOptions(out));
        String escaped = "\"\\\"/opt/b c\\\\d\\\" --wrap %command% -javaagent:ZombieBuddy.jar=hotswap=true --\"";
        assertEquals(2, out.split(java.util.regex.Pattern.quote(escaped), -1).length - 1, out);
        assertTrue(out.contains("\"LaunchOptions\"\t\t\"-other\""));
        assertTrue(out.contains("\"108600\"\t\t\"3200000004\""));
        // only the two values changed
        assertEquals(VDF.lines().count(), out.lines().count());
    }

    @Test
    void vdfAddsLaunchOptionsWhenThereAreNone() {
        String noOptions = VDF.replace("\t\t\t\t\t\t\"LaunchOptions\"\t\t\"\\\"C:\\\\Users\\\\me\\\\BiteCheck.exe\\\" --wrap %command% -agentlib:zbNative --\"\n", "");
        assertEquals(Optional.empty(), LocalConfigVdf.launchOptions(noOptions));
        String out = LocalConfigVdf.withLaunchOptions(noOptions, "-javaagent:ZombieBuddy.jar=hotswap=true --").orElseThrow();
        assertEquals(Optional.of("-javaagent:ZombieBuddy.jar=hotswap=true --"), LocalConfigVdf.launchOptions(out));
        assertTrue(out.contains("\t\t\t\t\t{\n\t\t\t\t\t\t\"LaunchOptions\"\t\t\"-javaagent:ZombieBuddy.jar=hotswap=true --\"\n\t\t\t\t\t\t\"LastPlayed\""), out);
    }

    @Test
    void vdfWithoutTheGame() {
        String other = "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t{\n\t\"Steam\"\n\t{\n\t\"apps\"\n\t{\n\t}\n\t}\n\t}\n\t}\n}\n";
        assertFalse(LocalConfigVdf.hasGame(other));
        assertEquals(Optional.empty(), LocalConfigVdf.withLaunchOptions(other, "x"));
    }

    // --- ProjectZomboid64.json (Windows) ---

    private static final String JSON = "{\r\n\t\"mainClass\": \"zombie/gameStates/MainScreenState\",\r\n\t\"vmArgs\": [\r\n\t\t\"-Xms16g\",\r\n\t\t\"-Xmx16g\"\r\n\t]\r\n}\r\n";

    @Test
    void jsonWithoutZombieBuddyGetsTheAgent() {
        Path jar = Path.of("PZ", "ZombieBuddy.jar").toAbsolutePath();
        String out = LaunchJson.withHotswap(JSON, jar);
        String agent = "-javaagent:" + jar.toString().replace('\\', '/') + "=hotswap=true";
        assertTrue(out.contains("\"vmArgs\": [\r\n\t\t\"" + agent + "\",\r\n\t\t\"-Xms16g\""), out);
        assertTrue(LaunchJson.hasHotswap(out));
        assertEquals(JSON, LaunchJson.withoutHotswap(out));
        assertFalse(LaunchJson.startsZombieBuddy(LaunchJson.withoutHotswap(out)));
    }

    @Test
    void jsonWithZombieBuddysInstaller() {
        String zb = JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative\",\r\n\t\t\"-Xms16g\"");
        String out = LaunchJson.withHotswap(zb, Path.of("x"));
        assertTrue(out.contains("\"-agentlib:zbNative=hotswap=true\""));
        assertEquals(zb, LaunchJson.withoutHotswap(out));
        assertTrue(LaunchJson.startsZombieBuddy(zb));

        String withOptions = JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative=policy=deny-new\",\r\n\t\t\"-Xms16g\"");
        String out2 = LaunchJson.withHotswap(withOptions, Path.of("x"));
        assertTrue(out2.contains("\"-agentlib:zbNative=policy=deny-new,hotswap=true\""));
        assertEquals(withOptions, LaunchJson.withoutHotswap(out2));
    }

    @Test
    void jsonAlreadySetOrBroken() {
        String set = JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative=hotswap=true\",\r\n\t\t\"-Xms16g\"");
        assertEquals(set, LaunchJson.withHotswap(set, Path.of("x")));
        assertNull(LaunchJson.withHotswap("{}", Path.of("x")));
    }

    // --- default.txt ---

    @Test
    void modListEnableAndDisable() {
        String list = "VERSION = 1,\r\n\r\nmods\r\n{\r\n    mod = \\Minimap,\r\n}\r\n\r\nmaps\r\n{\r\n}\r\n";
        String on = ModList.enable(list, List.of("ZombieBuddy", "Jumpstart"));
        assertEquals("VERSION = 1,\r\n\r\nmods\r\n{\r\n    mod = ZombieBuddy,\r\n    mod = Jumpstart,\r\n    mod = \\Minimap,\r\n}\r\n\r\nmaps\r\n{\r\n}\r\n", on);
        assertEquals(on, ModList.enable(on, List.of("ZombieBuddy", "Jumpstart")));
        assertTrue(ModList.contains(list, "Minimap"));
        assertEquals(list, ModList.disable(ModList.disable(on, "Jumpstart"), "ZombieBuddy"));
        assertNull(ModList.enable("nothing", List.of("Jumpstart")));
        // a mod whose id contains another one's
        assertFalse(ModList.contains("mods\n{\n    mod = ZombieBuddyExtra,\n}\n", "ZombieBuddy"));
    }
}
