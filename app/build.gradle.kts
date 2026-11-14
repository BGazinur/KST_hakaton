plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("com.google.devtools.ksp") version "2.3.6"
}

android {
    namespace = "com.arengineering.inspection"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arengineering.inspection"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    androidResources { noCompress += "onnx" }
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

dependencies {
    implementation(project(":cv"))
    implementation("com.google.ar:core:1.57.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
}
