package io.github.jumpstartpz.mod;

import java.nio.file.Path;

/** A mod folder with a Java part, as ZombieBuddy would load it. */
record ModEntry(String id, String name, Path jar, String javaPackage) {
}
