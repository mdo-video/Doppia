plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numero progressivo della build su GitHub: fa crescere versionCode a ogni compilazione.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.mdovideo.doppia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mdovideo.doppia"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + runNumber
        versionName = "0.2.$runNumber"
    }

    // Chiave di firma fissa: stessa firma a ogni build, gli aggiornamenti si installano sopra.
    signingConfigs {
        create("fixed") {
            storeFile = file("doppia.p12")
            storeType = "pkcs12"
            storePassword = "vicino"
            keyAlias = "vicino"
            keyPassword = "vicino"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
