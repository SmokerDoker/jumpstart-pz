// The Jumpstart mod for Project Zomboid: a ZombieBuddy Java mod with a Lua API on top of core.
// `./gradlew :mod:agentJar -PforkJar=...` builds Jumpstart.jar for the game folder (see below).

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
    description = "Builds the Jumpstart mod jar alone (tests; players get agentJar)."
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

// Jumpstart.jar for players: the ZombieBuddy fork (the Java agent) with Jumpstart and its BiteCheck
// classes built in, so the game folder holds this one jar and nothing else. The fork loads the mod
// package from its entries under embedded-mod/ (manifest attribute ZombieBuddy-Embedded-Mod), like a
// mod jar; core and BiteCheck stay at the root, where the game's class loader finds them. Plain
// entries, not a jar in the jar, so BiteCheck's scanner sees every class.
//
//   ./gradlew :mod:agentJar -PforkJar=<the fork's shadowJar>   -> mod/build/agent/Jumpstart.jar
val forkJar = providers.gradleProperty("forkJar")
// the fork's checkout (git submodule), for its license file
val forkDir = providers.gradleProperty("forkDir").orElse(rootProject.file("third_party/zombiebuddy-jumpstart").path)

val forkManifest by tasks.registering(Copy::class) {
    description = "The fork jar's manifest (Premain-Class and the rest), the base of Jumpstart.jar's."
    from({ zipTree(file(forkJar.orNull ?: throw GradleException("-PforkJar=<the ZombieBuddy fork's shadowJar> is missing"))) }) {
        include("META-INF/MANIFEST.MF")
        eachFile { path = name }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("fork-manifest"))
}

val agentJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds Jumpstart.jar: the ZombieBuddy fork with Jumpstart inside (needs -PforkJar)."
    archiveFileName.set("Jumpstart.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    dependsOn(forkManifest)
    inputs.property("launcherClassesInGame", launcherClassesInGame)
    manifest {
        from(layout.buildDirectory.file("fork-manifest/MANIFEST.MF"))
        attributes(
            "ZombieBuddy-Embedded-Mod" to "io.github.jumpstartpz.mod",
            "Jumpstart-Version" to project.version.toString(),
        )
    }
    // the fork: agent, its patches and Byte Buddy; signatures of a signed fork jar would no longer match
    from({ zipTree(file(forkJar.get())) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/*.DSA")
    }
    from({ file(forkDir.get()).resolve("LICENSE.txt") }) {
        into("META-INF")
        rename { "LICENSE-ZombieBuddy.txt" } // MIT, Andrey "Zed" Zaikin
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-Jumpstart.txt" } // GPL-3.0, the whole jar
    }
    from(sourceSets["main"].output) { into("embedded-mod") }
    doFirst {
        if (!file(forkDir.get()).resolve("LICENSE.txt").isFile) {
            throw GradleException("no ZombieBuddy fork checkout at ${forkDir.get()} (git submodule update --init, or -PforkDir)")
        }
    }
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

// For the in-game test scripts only (tools/gametest.ps1, servertest.ps1, lualivetest.ps1): Jumpstart as
// a separate mod folder next to a plain fork jar, as before 0.2, so a test can use the fork's
// experimental build. Players get agentJar.
val modFolder by tasks.registering(Sync::class) {
    group = "verification"
    description = "Builds a Jumpstart mod folder for the in-game test scripts (not shipped)."
    into(layout.buildDirectory.dir("mod/Jumpstart"))
    from("src/mod")
    from(modJar) {
        into("42/media/java")
    }
}
