/* fuse/plugin — AAPS-Anbindung des FUSE-Regelkerns.
 *
 * ADDITIV: FUSE liegt in eigenen Modulen und beruehrt vom AAPS-Bestand nur
 * settings.gradle, PluginsListModule, Versions.kt und den Algorithm-Enum. Das
 * ist Absicht — die autoISF-Patches greifen tief in bestehende Dateien ein,
 * weshalb jede Upstream-Migration teuer ist (115 Cherry-Picks zuletzt). Diese
 * Trennung haelt kuenftige AAPS-Spruenge billig.
 *
 * Alle Entscheidungen liegen in fuse/core (pure JVM). Hier steht nur die
 * Uebersetzung: AAPS-Quellen rein, APSResult raus. */
plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.fuse.plugin"
}

dependencies {
    implementation(project(":fuse:core"))
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    // Nur fuer den Einstellungsbildschirm (AdaptiveDoublePreference/AdaptiveIntPreference).
    // Der Regelpfad kennt dieses Modul nicht.
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))

    testImplementation(project(":shared:tests"))
    // NUR fuer den Test: KC2-61 vergleicht den Einheitskern gegen das ECHTE
    // aktive Insulinplugin. Ein Vergleich gegen eine nachgebaute Formel wuerde
    // genau den Fehler nicht finden, den C1.1 verhindern soll.
    testImplementation(project(":plugins:insulin"))
}
