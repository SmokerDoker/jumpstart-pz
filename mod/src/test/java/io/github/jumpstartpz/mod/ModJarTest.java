package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import io.github.bitecheckpz.core.Scanner;

/**
 * Jumpstart.jar as it goes into the mod folder: only the BiteCheck launcher classes it needs in
 * game, all of them complete, and nothing that starts a process (BiteCheck's scanner would flag it).
 */
class ModJarTest {
    private static final Pattern LAUNCHER_CLASS = Pattern.compile("io/github/bitecheckpz/launcher/[A-Za-z0-9_$]+");

    private static Path jar() {
        String path = System.getProperty("jumpstart.modJar");
        assertTrue(path != null && !path.isBlank(), "run through Gradle: -Djumpstart.modJar is missing");
        return Path.of(path);
    }

    @Test
    void everyLauncherClassItRefersToIsInside() throws IOException {
        Set<String> missing = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar().toFile())) {
            List<String> classes = new ArrayList<>();
            zip.stream().map(ZipEntry::getName).filter(n -> n.endsWith(".class")).forEach(classes::add);
            for (String name : classes) {
                // class names in the constant pool are plain UTF-8 strings
                String bytes = new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.ISO_8859_1);
                Matcher m = LAUNCHER_CLASS.matcher(bytes);
                while (m.find()) {
                    if (zip.getEntry(m.group() + ".class") == null) {
                        missing.add(m.group() + " (used by " + name + ")");
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void startsNoProcesses() throws IOException {
        Scanner.Report report = Scanner.scan(jar());
        List<String> process = report.findings().stream()
                .filter(f -> f.severity() == Scanner.Severity.HARD && f.rule().equals("process"))
                .map(f -> f.where() + ": " + f.detail()).toList();
        assertEquals(List.of(), process);
    }
}
