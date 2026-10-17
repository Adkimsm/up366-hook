import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "io.github.adkimsm.up366.hook"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.adkimsm.up366.hook"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        if (hasReleaseSigning) {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // lintVital needs extra artifacts fetched from dl.google.com at build time;
        // normal lint is still available via `./gradlew :module:lint`.
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// CI 上没有被 .gitignore 的 flipbook/ 题包样本，用 -PskipSampleTests 跳过依赖样本的两个测试。
tasks.withType<Test>().configureEach {
    if (project.hasProperty("skipSampleTests")) {
        filter {
            excludeTestsMatching("io.github.adkimsm.up366.hook.QuestionDataParserTest")
            excludeTestsMatching("io.github.adkimsm.up366.hook.AnswerIndexerTest")
        }
    }
}
