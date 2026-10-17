import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

/* fuse/core — reiner Kotlin-Kern des FUSE-Observers (Spec K1 v0.5 §2).
 * KEINE AAPS-, Android- oder Dagger-Abhaengigkeit: alles hier muss auf der
 * JVM replaybar sein (Frozen-Input-Paritaet, T8/T17). Der AAPS-Adapter und
 * plugins/source IMPORTIEREN von hier — nie umgekehrt. */
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = Versions.javaVersion
    targetCompatibility = Versions.javaVersion
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.org.junit.jupiter)
    testImplementation(libs.org.junit.jupiter.api)
    testRuntimeOnly(libs.org.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
    }
}
