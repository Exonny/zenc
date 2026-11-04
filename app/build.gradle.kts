plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zen.gate.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zen.gate.android"
        minSdk = 26          // usesCleartextTraffic + foreground service semantics
        targetSdk = 35       // Android 15; 36 would require 16KB page alignment proof
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // The AAR carries one ABI; armeabi-v7a would need a second gomobile
            // run and doubles the APK for a case this project does not target.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Side-loading only: signing is out of scope, the CI ships debug.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            // gomobile AARs ship already-compressed .so files; keeping them
            // uncompressed lets Android load them directly from the APK.
            useLegacyPackaging = false
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // gomobile bind output, copied in by CI as app/libs/gomobile.aar
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
