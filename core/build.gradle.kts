plugins {
    `java-library`
}

// Must match the Byte Buddy version bundled in ZombieBuddy, which ships it unrelocated
// and provides it at runtime. Jumpstart never bundles its own copy.
val byteBuddyVersion = "1.18.14"
val junitVersion = "6.1.3"

dependencies {
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // Byte Buddy's nullability annotations refer to JSR-305; without it javac warns and -Werror fails.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")

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
