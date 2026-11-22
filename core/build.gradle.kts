plugins {
    `java-library`
}

// Must match the Byte Buddy version bundled in ZombieBuddy, which ships it unrelocated
// and provides it at runtime. Jumpstart never bundles its own copy.
val byteBuddyVersion = "1.18.14"
val junitVersion = "6.1.3"

dependencies {
    // BiteCheck from the submodule in third_party/ (see settings.gradle.kts); the version is ignored.
    implementation("io.github.bitecheckpz:core:0")
    implementation("io.github.bitecheckpz:launcher:0")

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
