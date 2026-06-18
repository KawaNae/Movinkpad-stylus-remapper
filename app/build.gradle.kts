plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.stylusremapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.stylusremapper"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "3.1.0"

        // Native EVIOCGRAB helper. Device is aarch64-only (Wacom MovinkPad).
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "MovinkpadStylusRemapper-v${versionName}.apk"
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
