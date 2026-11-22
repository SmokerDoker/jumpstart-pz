package io.github.jumpstartpz.core.gate.fixtures;

import java.io.IOException;

/** Gate test fixture: starts a process, a hard finding for BiteCheck's scanner. Never executed. */
public final class ShadyFixtureMod {
    private ShadyFixtureMod() {
    }

    public static void run() throws IOException {
        new ProcessBuilder("cmd.exe", "/c", "echo hi").start();
    }
}
