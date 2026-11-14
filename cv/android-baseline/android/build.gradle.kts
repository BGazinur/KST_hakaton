plugins {
    id("com.android.library") version "9.2.1"
}

android {
    namespace = "com.arengineering.inspection.cv"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" }
    testOptions {
        unitTests.all {
            it.systemProperty("cv.modelDir", project.file("src/main/assets/cv").absolutePath)
            it.systemProperty("cv.fixtures.dir", project.file("src/test/resources/parity").absolutePath)
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ar:core:1.57.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    // Desktop native library only for local JVM tests; never shipped in the APK.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")
}

