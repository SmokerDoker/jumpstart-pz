subprojects {
    apply(plugin = "java")

    group = "io.github.jumpstartpz"
    version = (findProperty("jumpstartVersion") as String?) ?: "0.1.0-SNAPSHOT"

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Project Zomboid Build 42 runs on Java 25; class files for 21 run there and on the
        // JDK most contributors already have.
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    // Reproducible archives: same sources -> byte-identical jars (needed for BiteCheck).
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
