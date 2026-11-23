// Jumpstart's installer: a small window with one button (or --install on the command line) that puts
// the ZombieBuddy fork and the Jumpstart mod in place. kit/package-installer.sh turns
// JumpstartSetup.jar into native installers (.msi, .dmg, .deb, .rpm) with their own Java runtime.
//
//   ./gradlew :installer:installerJar -PzombieBuddyMod=<folder from kit/zombiebuddy-mod.sh> [-PkitVersion=...]

plugins {
    application
}

val junitVersion = "6.1.3"

dependencies {
    // BiteCheck's launcher finds Steam, its libraries and Zomboid/ the same way on every system
    implementation("io.github.bitecheckpz:launcher:0")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

application {
    applicationName = "JumpstartSetup"
    mainClass.set("io.github.jumpstartpz.installer.InstallerMain")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val zombieBuddyMod = providers.gradleProperty("zombieBuddyMod")
val kitVersion = providers.gradleProperty("kitVersion").orElse(provider { project.version.toString() })

val payloadVersion by tasks.registering {
    val out = layout.buildDirectory.file("payload-version/VERSION")
    inputs.property("version", kitVersion)
    outputs.file(out)
    doLast { out.get().asFile.writeText(kitVersion.get() + "\n") }
}

// What the installer puts in place: mods/ZombieBuddy (the fork, libs/ZombieBuddy.jar is the agent),
// mods/Jumpstart, VERSION.
val payloadZip by tasks.registering(Zip::class) {
    group = "build"
    description = "Packs the ZombieBuddy fork and the Jumpstart mod for the installer."
    archiveFileName.set("payload.zip")
    destinationDirectory.set(layout.buildDirectory.dir("payload"))
    dependsOn(":mod:modFolder")
    from({ zombieBuddyMod.orNull?.let { file(it) } ?: emptyList<File>() }) { into("mods/ZombieBuddy") }
    from(rootProject.file("mod/build/mod/Jumpstart")) { into("mods/Jumpstart") }
    from(payloadVersion)
    doFirst {
        val dir = zombieBuddyMod.orNull?.let { file(it) }
            ?: throw GradleException("-PzombieBuddyMod=<folder> is missing (build it with kit/zombiebuddy-mod.sh)")
        if (!dir.resolve("libs/ZombieBuddy.jar").isFile || !dir.resolve("42/mod.info").isFile) {
            throw GradleException("$dir is not the fork's ZombieBuddy mod folder (libs/ZombieBuddy.jar, 42/mod.info)")
        }
    }
}

// JumpstartSetup.jar: the installer, BiteCheck's launcher classes and the payload in one file.
// Runs on any Java 21+, also Project Zomboid's own (jre64), e.g. on a Steam Deck.
val installerJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds JumpstartSetup.jar with the payload inside."
    archiveFileName.set("JumpstartSetup.jar")
    destinationDirectory.set(layout.buildDirectory.dir("setup"))
    manifest {
        attributes(
            "Main-Class" to "io.github.jumpstartpz.installer.InstallerMain",
            "Implementation-Version" to kitVersion,
        )
    }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(payloadZip) { into("io/github/jumpstartpz/installer") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
