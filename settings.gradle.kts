rootProject.name = "jumpstart"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// BiteCheck (git submodule, pinned): its core library and the launcher classes that fetch the
// signed index and edit ZombieBuddy's mod_approvals.json. Same author, same license (GPL-3.0).
includeBuild("third_party/bitecheck-pz") {
    dependencySubstitution {
        substitute(module("io.github.bitecheckpz:core")).using(project(":core"))
        substitute(module("io.github.bitecheckpz:launcher")).using(project(":launcher"))
    }
}

// Planned next to them (see README): bootstrap, market, mp.
include("core", "mod", "installer")
