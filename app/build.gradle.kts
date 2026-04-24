plugins {
    id("com.android.application")
}

android {
    namespace = "com.detector.esp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.detector.esp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    val keystoreFile = System.getenv("KEYSTORE_FILE")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("KEY_ALIAS")
    val keyPassword = System.getenv("KEY_PASSWORD")

    signingConfigs {
        create("release") {
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
            }
            if (!keystorePassword.isNullOrBlank()) {
                storePassword = keystorePassword
            }
            if (!keyAlias.isNullOrBlank()) {
                this.keyAlias = keyAlias
            }
            if (!keyPassword.isNullOrBlank()) {
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))

            if (
                !keystoreFile.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }

    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    // TensorFlow Lite + GPU
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // Core
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
