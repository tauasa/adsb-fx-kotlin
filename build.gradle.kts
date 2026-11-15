plugins {
    kotlin("jvm") version "2.0.21"
    id("org.openjfx.javafxplugin") version "0.1.0"
    application
}

group = "org.tauasa.apps"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls")
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.tauasa.apps.adsb.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
