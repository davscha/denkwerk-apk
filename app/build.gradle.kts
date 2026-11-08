plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.davscha.denkwerk"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.davscha.denkwerk"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Aktuelle Tablets sind 64-Bit-ARM – hält das APK klein
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fester Schlüssel, damit sich neue Builds über die alte Version installieren lassen
    signingConfigs {
        getByName("debug") {
            storeFile = file("denkwerk-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        jniLibs {
            pickFirsts += setOf("**/liblitertlm_jni.so")
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT-LM wird mit einer neueren Kotlin-Version gebaut
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("io.coil-kt:coil-compose:2.7.0")          // Bilder im Chat

    // Gemma 4 auf dem Grafikchip (gleiche Version wie Googles AI Edge Gallery)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}
