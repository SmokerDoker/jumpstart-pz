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
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart(""));
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart(null));
    }

    @Test
    void launchOptionsReplaceZombieBuddyAndKeepItsOptions() {
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart("-javaagent:ZombieBuddy.jar --"));
        assertEquals("-javaagent:Jumpstart.jar=policy=deny-new,hotswap=true --",
                LaunchOptions.withJumpstart("-javaagent:ZombieBuddy.jar=policy=deny-new --"));
        // Jumpstart before 0.2
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true --"));
        // both: ZombieBuddy's goes, its options join Jumpstart's
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true,verbosity=2 --",
                LaunchOptions.withJumpstart("-javaagent:Jumpstart.jar=hotswap=true -javaagent:ZombieBuddy.jar=verbosity=2 --"));
    }

    @Test
    void launchOptionsKeepBiteChecksWrapper() {
        String bitecheck = "\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command% -javaagent:ZombieBuddy.jar --";
        String wanted = "\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command% -javaagent:Jumpstart.jar=hotswap=true --";
        assertEquals(wanted, LaunchOptions.withJumpstart(bitecheck));
        assertTrue(LaunchOptions.hasJumpstart(wanted));
        assertEquals(bitecheck, LaunchOptions.withoutJumpstart(wanted, true));
    }

    @Test
    void launchOptionsAlreadySet() {
        String s = "-javaagent:Jumpstart.jar=hotswap=true --";
        assertEquals(s, LaunchOptions.withJumpstart(s));
        assertTrue(LaunchOptions.hasJumpstart(s));
        assertFalse(LaunchOptions.hasJumpstart("-javaagent:Jumpstart.jar --"));
        assertFalse(LaunchOptions.hasJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true --"), "that is the layout before 0.2");
        assertFalse(LaunchOptions.hasJumpstart(""));
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart("-javaagent:Jumpstart.jar --"));
    }

    @Test
    void launchOptionsWithOtherOptions() {
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true -Xmx8g --", LaunchOptions.withJumpstart("-Xmx8g --"));
        assertEquals("-javaagent:Jumpstart.jar=hotswap=true -- -debug", LaunchOptions.withJumpstart("-debug"));
        assertEquals("gamemoderun %command% -javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withJumpstart("gamemoderun %command%"));
        assertEquals("gamemoderun %command% -javaagent:Jumpstart.jar=hotswap=true -- -debug",
                LaunchOptions.withJumpstart("gamemoderun %command% -debug"));
    }

    @Test
    void launchOptionsUninstall() {
        assertEquals("", LaunchOptions.withoutJumpstart("-javaagent:Jumpstart.jar=hotswap=true --", false));
        assertEquals("-javaagent:ZombieBuddy.jar --", LaunchOptions.withoutJumpstart("-javaagent:Jumpstart.jar=hotswap=true --", true));
        assertEquals("-javaagent:ZombieBuddy.jar=policy=deny-new --",
                LaunchOptions.withoutJumpstart("-javaagent:Jumpstart.jar=policy=deny-new,hotswap=true --", true));
        assertEquals("-debug", LaunchOptions.withoutJumpstart("-javaagent:Jumpstart.jar=hotswap=true -- -debug", false));
        assertEquals("gamemoderun %command%", LaunchOptions.withoutJumpstart("gamemoderun %command% -javaagent:Jumpstart.jar=hotswap=true --", false));
        assertEquals("-Xmx8g --", LaunchOptions.withoutJumpstart("-javaagent:Jumpstart.jar=hotswap=true -Xmx8g --", false));
        assertEquals("-Xmx8g --", LaunchOptions.withoutJumpstart("-Xmx8g --", false));
        // Jumpstart before 0.2; a ZombieBuddy without hotswap is the player's and stays
        assertEquals("", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar=hotswap=true --", false));
        assertEquals("-javaagent:ZombieBuddy.jar --", LaunchOptions.withoutJumpstart("-javaagent:ZombieBuddy.jar --", false));
    }

    @Test
    void launchOptionsOnWindowsLoseZombieBuddy() {
        String bitecheck = "\"C:\\BiteCheck.exe\" --wrap %command%";
        assertEquals(bitecheck, LaunchOptions.withoutZombieBuddy(bitecheck + " -agentlib:zbNative --"));
        assertEquals("", LaunchOptions.withoutZombieBuddy("-agentlib:zbNative=policy=deny-new --"));
        assertEquals("-debug", LaunchOptions.withoutZombieBuddy("-debug"));
        assertTrue(LaunchOptions.startsZombieBuddy("-agentlib:zbNative --"));
        assertFalse(LaunchOptions.startsZombieBuddy("-javaagent:Jumpstart.jar=hotswap=true --"));
        assertTrue(LaunchOptions.startsLoader("-javaagent:Jumpstart.jar=hotswap=true --"));
    }

    @Test
    void wrapperFixesAStaleZombieBuddyAgentForThisStart() {
        List<String> mac = List.of("/game/ProjectZomboid", "-javaagent:ZombieBuddy.jar=policy=deny-new,hotswap=true", "--", "-debug");
        assertEquals(List.of("/game/ProjectZomboid", "-javaagent:Jumpstart.jar=policy=deny-new,hotswap=true", "--", "-debug"),
                LaunchOptions.forJumpstart(mac, false));
        List<String> both = List.of("/game", "-javaagent:Jumpstart.jar=hotswap=true", "-javaagent:ZombieBuddy.jar", "--");
        assertEquals(List.of("/game", "-javaagent:Jumpstart.jar=hotswap=true", "--"), LaunchOptions.forJumpstart(both, false));
        // Windows: ProjectZomboid64.json starts Jumpstart; BiteCheck's zbNative goes
        List<String> win = List.of("C:\\BiteCheck.exe", "--wrap", "C:\\PZ\\ProjectZomboid64.exe", "-agentlib:zbNative", "--");
        assertEquals(List.of("C:\\BiteCheck.exe", "--wrap", "C:\\PZ\\ProjectZomboid64.exe", "--"), LaunchOptions.forJumpstart(win, true));
        List<String> clean = List.of("/game", "-javaagent:Jumpstart.jar=hotswap=true", "--");
        assertEquals(clean, LaunchOptions.forJumpstart(clean, false));
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
        String opts = "\"/opt/b c\\d\" --wrap %command% -javaagent:Jumpstart.jar=hotswap=true --";
        String out = LocalConfigVdf.withLaunchOptions(VDF, opts).orElseThrow();
        assertEquals(Optional.of(opts), LocalConfigVdf.launchOptions(out));
        String escaped = "\"\\\"/opt/b c\\\\d\\\" --wrap %command% -javaagent:Jumpstart.jar=hotswap=true --\"";
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
    private static final String AGENT = "-agentlib:jsNative";

    @Test
    void jsonFromJumpstart04And050GetsTheLoader() {
        // 0.4 and 0.5.0 wrote -javaagent, which ProjectZomboid64.exe cannot start on Windows
        String broken = JSON.replace("\"-Xms16g\"",
                "\"-javaagent:c:/program files (x86)/steam/steamapps/common/ProjectZomboid/Jumpstart.jar=verbosity=1,hotswap=true\",\r\n\t\t\"-Xms16g\"");
        assertFalse(LaunchJson.hasJumpstart(broken), "the game does not start with it");
        assertTrue(LaunchJson.startsLoader(broken));
        LaunchJson.Change c = LaunchJson.withJumpstart(broken);
        assertTrue(c.json().contains("\"vmArgs\": [\r\n\t\t\"" + AGENT + "=verbosity=1,hotswap=true\",\r\n\t\t\"-Xms16g\""), c.json());
        assertFalse(c.json().contains("javaagent"), c.json());
        assertEquals(List.of(), c.removed(), "it was Jumpstart's own entry");
        assertTrue(LaunchJson.hasJumpstart(c.json()));
        assertEquals(JSON, LaunchJson.withoutJumpstart(broken, List.of()), "uninstalled without updating first");
    }

    @Test
    void jsonWithoutZombieBuddyGetsTheAgent() {
        LaunchJson.Change c = LaunchJson.withJumpstart(JSON);
        assertTrue(c.json().contains("\"vmArgs\": [\r\n\t\t\"" + AGENT + "=hotswap=true\",\r\n\t\t\"-Xms16g\""), c.json());
        assertEquals(List.of(), c.removed());
        assertTrue(LaunchJson.hasJumpstart(c.json()));
        assertTrue(LaunchJson.startsLoader(c.json()));
        assertEquals(c.json(), LaunchJson.withJumpstart(c.json()).json(), "once is enough");
        assertEquals(JSON, LaunchJson.withoutJumpstart(c.json(), List.of()));
        assertFalse(LaunchJson.startsLoader(JSON));
    }

    @Test
    void jsonWithZombieBuddysInstallerIsReplacedAndRestored() {
        String zb = JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative=policy=deny-new\",\r\n\t\t\"-Xms16g\"");
        LaunchJson.Change c = LaunchJson.withJumpstart(zb);
        assertFalse(c.json().contains("zbNative"), c.json());
        assertTrue(c.json().contains("\"" + AGENT + "=policy=deny-new,hotswap=true\""), c.json());
        assertEquals(List.of("-agentlib:zbNative=policy=deny-new"), c.removed());
        assertEquals(zb, LaunchJson.withoutJumpstart(c.json(), c.removed()));
        assertTrue(LaunchJson.startsLoader(zb));
    }

    @Test
    void jsonFromJumpstartBefore02() {
        // 0.1 added its own agent, or hotswap=true to ZombieBuddy's installer option
        String own = JSON.replace("\"-Xms16g\"", "\"-javaagent:C:/PZ/ZombieBuddy.jar=hotswap=true\",\r\n\t\t\"-Xms16g\"");
        LaunchJson.Change c = LaunchJson.withJumpstart(own);
        assertFalse(c.json().contains("ZombieBuddy.jar"), c.json());
        assertEquals(List.of(), c.removed(), "0.1's entry was never the player's");
        assertEquals(JSON, LaunchJson.withoutJumpstart(own, List.of()), "uninstalled without updating first");

        String zbNative = JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative=hotswap=true\",\r\n\t\t\"-Xms16g\"");
        LaunchJson.Change c2 = LaunchJson.withJumpstart(zbNative);
        assertEquals(List.of("-agentlib:zbNative"), c2.removed(), "the player's option, without 0.1's hotswap=true");
        assertEquals(JSON.replace("\"-Xms16g\"", "\"-agentlib:zbNative\",\r\n\t\t\"-Xms16g\""),
                LaunchJson.withoutJumpstart(zbNative, List.of()));
    }

    @Test
    void jsonBroken() {
        assertNull(LaunchJson.withJumpstart("{}"));
        assertFalse(LaunchJson.hasJumpstart("{}"));
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
