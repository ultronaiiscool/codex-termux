plugins {
    id("com.android.application")
}

android {
    namespace = "com.boneai.codexquest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.boneai.codexquest"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Android 10+ forbids execve() from writable app storage. Keep the
            // prebuilt PIE executables extracted in the package's read-only
            // nativeLibraryDir instead of copying them into filesDir.
            useLegacyPackaging = true
        }
    }
}
