plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shay.backup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shay.backup"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // App Insights connection string is injected at build time from a GHA secret.
        // Local builds with no env var get an empty string and silently disable telemetry.
        val aiConn = System.getenv("AI_CONNECTION_STRING").orEmpty()
        buildConfigField("String", "AI_CONN_STR", "\"${aiConn.replace("\"", "\\\"")}\"")

        // GHA injects GITHUB_RUN_NUMBER and GITHUB_SHA automatically; local builds fall back.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER").orEmpty().ifBlank { "dev" }
        val gitSha = System.getenv("GITHUB_SHA").orEmpty().take(7)
        buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
        buildConfigField("String", "GIT_SHA",      "\"$gitSha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
