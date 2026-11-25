package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Jumpstart's wrapper in the launch options, and which versions count as an update. */
class WrapperAndVersionsTest {
    private static final String WIN = "\"C:\\Users\\me\\AppData\\Local\\Jumpstart\\Jumpstart.exe\" --wrap";
    private static final String MAC = "\"/Applications/Jumpstart.app/Contents/MacOS/Jumpstart\" --wrap";
    private static final String JAR = "\"/home/me/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/jre64/bin/java\" -jar \"/home/me/.jumpstart/JumpstartSetup.jar\" --wrap";

    @Test
    void wrapperGoesInFront() {
        assertEquals(WIN + " %command%", LaunchOptions.withWrapper("", WIN));
        assertEquals(WIN + " %command% -agentlib:zbNative --", LaunchOptions.withWrapper("-agentlib:zbNative --", WIN));
        String bitecheck = "\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command% -javaagent:ZombieBuddy.jar --";
        assertEquals(MAC + " " + bitecheck, LaunchOptions.withWrapper(bitecheck, MAC));
        assertTrue(LaunchOptions.hasWrapper(MAC + " " + bitecheck));
        assertFalse(LaunchOptions.hasWrapper(bitecheck), "BiteCheck's wrapper is not Jumpstart's");
    }

    @Test
    void wrapperIsReplacedNotDoubled() {
        String once = LaunchOptions.withWrapper("-javaagent:Jumpstart.jar=hotswap=true --", JAR);
        assertEquals(JAR + " %command% -javaagent:Jumpstart.jar=hotswap=true --", once);
        assertTrue(LaunchOptions.hasWrapper(once));
        assertEquals(once, LaunchOptions.withWrapper(once, JAR));
        // the app moved: the new path replaces the old one
        assertEquals(MAC + " %command% -javaagent:Jumpstart.jar=hotswap=true --", LaunchOptions.withWrapper(once, MAC));
    }

    @Test
    void wrapperAndAgentTogether() {
        // macOS/Linux: the agent first (as Setup does), then the wrapper
        String s = LaunchOptions.withWrapper(LaunchOptions.withJumpstart(""), MAC);
        assertEquals(MAC + " %command% -javaagent:Jumpstart.jar=hotswap=true --", s);
        assertEquals(s, LaunchOptions.withWrapper(LaunchOptions.withJumpstart(s), MAC), "running the installer again changes nothing");
        assertEquals("", LaunchOptions.withoutJumpstart(LaunchOptions.withoutWrapper(s), false));
    }

    @Test
    void wrapperRemoved() {
        assertEquals("", LaunchOptions.withoutWrapper(WIN + " %command%"));
        assertEquals("-agentlib:zbNative --", LaunchOptions.withoutWrapper(WIN + " %command% -agentlib:zbNative --"));
        assertEquals("gamemoderun %command%", LaunchOptions.withoutWrapper(WIN + " gamemoderun %command%"));
        assertEquals("-debug", LaunchOptions.withoutWrapper("-debug"));
    }

    @Test
    void versions() {
        assertTrue(Versions.isRelease("0.2.0"));
        assertFalse(Versions.isRelease("test-20261123-52c6d9a"));
        assertTrue(Versions.compareReleases("0.10.0", "0.9.9") > 0);
        LocalDate day = LocalDate.of(2026, 11, 24);
        assertTrue(Versions.isUpdate("0.2.0", day, Optional.of("0.1.9")));
        assertFalse(Versions.isUpdate("0.2.0", day, Optional.of("0.2.0")), "the same release is not installed again");
        assertFalse(Versions.isUpdate("0.1.0", day, Optional.of("0.2.0")), "never back to an older release");
        assertTrue(Versions.isUpdate("0.2.0", day, Optional.empty()), "an install of an older kit without a version");
        assertTrue(Versions.isUpdate("0.2.0", day, Optional.of("test-20261124-abc1234")), "a release from that day replaces the test build");
        assertFalse(Versions.isUpdate("0.2.0", day, Optional.of("test-20261125-abc1234")), "a newer test build stays");
        assertFalse(Versions.isUpdate("test-20261130-x", day, Optional.empty()), "test builds are never automatic updates");
        assertTrue(InstallerMain.isDowngrade("0.1.0", Optional.of("0.2.0")));
        assertFalse(InstallerMain.isDowngrade("test-20261124-x", Optional.of("0.2.0")));
    }
}
