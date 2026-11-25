package io.github.jumpstartpz.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** On macOS Steam's %command% is the game's .app bundle: the wrapper runs the program inside it. */
class MacAppTest {
    @TempDir
    Path tmp;

    private Path app(String executable, String plistName) throws IOException {
        Path app = tmp.resolve("Project Zomboid.app");
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/MacOS/" + executable), "#!/bin/sh\n");
        if (plistName != null) {
            Files.writeString(app.resolve("Contents/Info.plist"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <plist version="1.0"><dict>
                      <key>CFBundleName</key><string>Project Zomboid</string>
                      <key>CFBundleExecutable</key>
                      <string>%s</string>
                    </dict></plist>
                    """.formatted(plistName));
        }
        return app;
    }

    @Test
    void runsTheBundlesProgramWithTheSameArguments() throws IOException {
        Path app = app("JavaAppLauncher", "JavaAppLauncher");
        Files.writeString(app.resolve("Contents/MacOS/helper"), "x"); // more than one file: the plist decides
        List<String> steam = List.of(app.toString(), "-javaagent:Jumpstart.jar=hotswap=true", "--");
        assertEquals(List.of(app.resolve("Contents/MacOS/JavaAppLauncher").toString(), "-javaagent:Jumpstart.jar=hotswap=true", "--"),
                Wrapper.runnable(steam));
    }

    @Test
    void withoutInfoPlistTheOnlyProgram() throws IOException {
        Path app = app("ProjectZomboid", null);
        assertEquals(List.of(app.resolve("Contents/MacOS/ProjectZomboid").toString()), Wrapper.runnable(List.of(app.toString())));
    }

    @Test
    void otherCommandsStayAsTheyAre() {
        List<String> windows = List.of("C:\\Games\\ProjectZomboid\\ProjectZomboid64.exe", "-debug");
        assertEquals(windows, Wrapper.runnable(windows));
        List<String> other = List.of(tmp.resolve("not-there.app").toString());
        assertEquals(other, Wrapper.runnable(other), "an .app that is not a folder");
        assertEquals(List.of(), Wrapper.runnable(List.of()));
    }
}
