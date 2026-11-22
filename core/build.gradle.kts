plugins {
    `java-library`
}

// Must match the Byte Buddy version bundled in ZombieBuddy, which ships it unrelocated
// and provides it at runtime. Jumpstart never bundles its own copy.
val byteBuddyVersion = "1.18.14"
val junitVersion = "6.1.3"

dependencies {
    // BiteCheck from the submodule in third_party/ (see settings.gradle.kts); the version is ignored.
    api("io.github.bitecheckpz:core:0")
    api("io.github.bitecheckpz:launcher:0")

    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // Byte Buddy's class files refer to JSR-305 and SpotBugs annotations; without them javac
    // warns about the missing annotation types and -Werror fails.
    for (annotations in listOf("com.google.code.findbugs:jsr305:3.0.2", "com.github.spotbugs:spotbugs-annotations:4.8.6")) {
        compileOnly(annotations)
        testCompileOnly(annotations)
    }

    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
    // The tests install Byte Buddy's agent into the test JVM to get an Instrumentation,
    // the same object ZombieBuddy hands out inside the game.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// BiteCheck's public keys as Java constants. ZombieBuddy defines a mod's classes but serves none of
// its resources, so the launcher's resource lookup finds no key in game; constants travel with the
// class bytes, which BiteCheck and ZombieBuddy check by hash.
val generatePinnedKeys by tasks.registering {
    val keysDir = rootProject.file("third_party/bitecheck-pz/keys")
    val registryKey = keysDir.resolve("registry.pub")
    val timestampKey = keysDir.resolve("timestamp.pub")
    val outDir = layout.buildDirectory.dir("generated/pinnedKeys")
    inputs.files(registryKey, timestampKey)
    outputs.dir(outDir)
    doLast {
        fun literal(f: File) = f.readText().trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val dir = outDir.get().asFile.resolve("io/github/jumpstartpz/core/gate")
        dir.mkdirs()
        dir.resolve("PinnedKeys.java").writeText(
            "package io.github.jumpstartpz.core.gate;\n\n" +
            "/** BiteCheck's public keys, copied from third_party/bitecheck-pz/keys at build time. */\n" +
            "final class PinnedKeys {\n" +
            "    private PinnedKeys() {\n    }\n\n" +
            "    static final String REGISTRY = \"" + literal(registryKey) + "\";\n" +
            "    static final String TIMESTAMP = \"" + literal(timestampKey) + "\";\n" +
            "}\n")
    }
}
sourceSets["main"].java.srcDir(generatePinnedKeys)
