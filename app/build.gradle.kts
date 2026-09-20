plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tulipskun.droidmcp"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.tulipskun.droidmcp"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("stableDebug") {
            val stableKeystore = file("droid-mcp-debug.keystore")
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storePassword = "droidmcpdebug"
                keyAlias = "droid-mcp-debug"
                keyPassword = "droidmcpdebug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val stableKeystore = file("droid-mcp-debug.keystore")
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.android.material:material:1.14.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
