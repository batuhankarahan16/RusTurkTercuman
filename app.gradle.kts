plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ceviri.rustr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ceviri.rustr"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "5.0"
        ndk {
            // Günümüz telefonlarının hepsi arm64; APK boyutu yarıya iner
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Her derlemede aynı imza: yeni sürüm eskisinin üzerine kurulabilsin
    signingConfigs {
        create("sabit") {
            storeFile = file("imza.jks")
            storePassword = "rustr2026"
            keyAlias = "rustr"
            keyPassword = "rustr2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sabit")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sabit")
        }
    }

    androidResources {
        noCompress.add("onnx")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Cihaz üzerinde Rusça -> Türkçe çeviri
    implementation("com.google.mlkit:translate:17.0.3")
    // Cihaz üzerinde akışlı konuşma tanıma + ses izi (sherpa-onnx)
    implementation(files("libs/sherpa-onnx.aar"))
}
