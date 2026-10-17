/* fuse/plugin - the AAPS binding of the FUSE control core.
 *
 * ADDITIVE: FUSE lives in its own modules and touches as little of AAPS as it can. All decisions are
 * in fuse/core (pure JVM); this module only translates: AAPS sources in, APSResult out.
 *
 * v4 port: an Android library like the pump drivers (Metro for DI, Compose for the screens), not a
 * multiplatform module - FUSE runs on the phone only. */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
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

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(project(":shared:tests"))
}
