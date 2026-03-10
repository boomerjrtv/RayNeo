plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rayneo.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rayneo.client"
        minSdk = 29          // Android 10 — USB host + OpenGL ES 3 guaranteed
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Server address — override via local.properties or build flavor
        buildConfigField("String", "SERVER_HOST", "\"lifelog-server\"")
        buildConfigField("int", "SERVER_PORT", "7777")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.stream.webrtc)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.ktx)
}
