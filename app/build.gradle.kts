import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.morph.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.morph.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "1.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = signingProperty("MORPH_KEYSTORE", "storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingProperty("MORPH_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingProperty("MORPH_KEY_ALIAS", "keyAlias")
                keyPassword = signingProperty("MORPH_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // The app is small and R8 has no measured benefit here; keeping the
            // release unobfuscated makes field diagnostics straightforward.
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingProperty("MORPH_KEYSTORE", "storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
