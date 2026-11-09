plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numero progressivo della build su GitHub: fa crescere versionCode a ogni compilazione.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
// Repository da cui l'app cerca gli aggiornamenti (su GitHub lo prende da solo).
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: "mdo-video/Doppia"

// Elenco delle novita': a ogni compilazione le sezioni "## Novita' ..." del README diventano un file
// dentro l'app, cosi' il pulsante Novita' segue sempre il README senza doverlo aggiornare a mano.
val changelogDir = layout.buildDirectory.get().asFile.resolve("generated/changelog")
val genChangelog by tasks.registering {
    val readme = rootProject.file("README.md")
    val out = changelogDir.resolve("changelog.txt")
    inputs.files(readme)
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        val sb = StringBuilder()
        var on = false
        if (readme.exists()) {
            for (line in readme.readLines()) {
                if (line.startsWith("## ")) on = line.startsWith("## Novita'")
                if (on) sb.append(line).append('\n')
            }
        }
        out.writeText(sb.toString())
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(genChangelog) }

android {
    namespace = "com.mdovideo.doppia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mdovideo.doppia"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + runNumber
        versionName = "1.18.$runNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(changelogDir)
        }
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
