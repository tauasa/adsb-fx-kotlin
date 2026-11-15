plugins {
    // Lets Gradle auto-download the JDK requested by jvmToolchain(...) if it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "adsb-fx"
