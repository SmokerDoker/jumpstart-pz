// The Jumpstart mod for Project Zomboid: a ZombieBuddy Java mod with a Lua API on top of core.
// `./gradlew :mod:modFolder` builds mod/build/mod/Jumpstart/, ready to copy into Zomboid/mods.

val byteBuddyVersion = "1.18.14" // provided by ZombieBuddy at runtime, see core/build.gradle.kts
val junitVersion = "6.1.3"

dependencies {
    implementation(project(":core"))
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion") // only the Advice annotations
    for (annotations in listOf("com.google.code.findbugs:jsr305:3.0.2", "com.github.spotbugs:spotbugs-annotations:4.8.6")) {
        compileOnly(annotations)
        testCompileOnly(annotations)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The BiteCheck launcher classes Jumpstart uses in game (index, state, approvals, sharing). The others
// (window, game start, Steam setup, Platform) start processes and are never called in game; leaving
// them out keeps BiteCheck's scanner from flagging Jumpstart for them. ModJarTest checks both.
val launcherClassesInGame = listOf("Config", "IndexClient", "LauncherState", "UsageSharing", "ZombieBuddyApprovals")

// One jar with Jumpstart and BiteCheck (core + the launcher classes above).
// Byte Buddy is not included: ZombieBuddy provides it.
val modJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds Jumpstart.jar for the mod folder."
    archiveFileName.set("Jumpstart.jar")
    destinationDirectory.set(layout.buildDirectory.dir("modjar"))
    inputs.property("launcherClassesInGame", launcherClassesInGame)
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
        exclude { e ->
            val p = e.path
            p.startsWith("io/github/bitecheckpz/launcher/") && !e.isDirectory &&
                launcherClassesInGame.none { p == "io/github/bitecheckpz/launcher/$it.class" || p.startsWith("io/github/bitecheckpz/launcher/$it$") }
        }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    dependsOn(modJar)
    systemProperty("jumpstart.modJar", modJar.get().archiveFile.get().asFile.absolutePath)
}

val modFolder by tasks.registering(Sync::class) {
    group = "build"
    description = "Builds the Jumpstart mod folder (mod.info and the jar)."
    into(layout.buildDirectory.dir("mod/Jumpstart"))
    from("src/mod")
    from(modJar) {
        into("42/media/java") // shared: the client and the server (multiplayer) load it
    }
}
