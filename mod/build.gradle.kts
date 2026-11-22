// The Jumpstart mod for Project Zomboid: a ZombieBuddy Java mod with a Lua API on top of core.
// `./gradlew :mod:modFolder` builds mod/build/mod/Jumpstart/, ready to copy into Zomboid/mods.

dependencies {
    implementation(project(":core"))
}

// One jar with Jumpstart and BiteCheck (core + launcher classes and the pinned keys).
// Byte Buddy is not included: ZombieBuddy provides it.
val modJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds Jumpstart.jar for the mod folder."
    archiveFileName.set("Jumpstart.jar")
    destinationDirectory.set(layout.buildDirectory.dir("modjar"))
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val modFolder by tasks.registering(Sync::class) {
    group = "build"
    description = "Builds the Jumpstart mod folder (mod.info and the jar)."
    into(layout.buildDirectory.dir("mod/Jumpstart"))
    from("src/mod")
    from(modJar) {
        into("42/media/java/client")
    }
}
