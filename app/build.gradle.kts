import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wbhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wbhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing reads keystore.properties, which is git-ignored. When it is
    // absent the release build falls back to no signing so a fresh clone still
    // compiles; a signed artifact requires the properties file to be present.
    val keystoreProperties = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use {
        keystoreProperties.load(it)
    }

    signingConfigs {
        create("release") {
            // A missing keystore.properties leaves these empty, and the build
            // then falls back to an unsigned artifact rather than failing.
            val store = keystoreProperties.getProperty("storeFile")
            if (!store.isNullOrBlank() && rootProject.file(store).exists()) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The config only exists when keystore.properties was found, so a
            // clone without signing material still produces an unsigned build
            // instead of failing the build.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) {
        enabled = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
