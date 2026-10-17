plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.adkimsm.up366.hook"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.adkimsm.up366.hook"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
