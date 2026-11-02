plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val keystorePath = providers.environmentVariable("JARVIS_KEYSTORE_PATH").orNull
val keystorePassword = providers.environmentVariable("JARVIS_KEYSTORE_PASSWORD").orNull
val keyAlias = providers.environmentVariable("JARVIS_KEY_ALIAS").orNull
val keyPassword = providers.environmentVariable("JARVIS_KEY_PASSWORD").orNull
val hasStableSigning = listOf(keystorePath, keystorePassword, keyAlias, keyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val localDebugKeystore = file("${rootDir}/debug.keystore")

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
        if (localDebugKeystore.exists()) {
            create("debugConfig") {
                storeFile = localDebugKeystore
                storePassword = "android"
                this.keyAlias = "androiddebugkey"
                this.keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            } else if (localDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")

    // Phase 4 memory: Room owns profile facts, memories (with FTS), conversations,
    // summaries, skills and the action log.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Background memory extraction and the idle review job.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
