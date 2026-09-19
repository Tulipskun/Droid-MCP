plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    }

    dependencies {
        androidTestImplementation("androidx.test:runner:1.6.2")
        androidTestImplementation("androidx.test:rules:1.6.1")
        androidTestImplementation("androidx.test.ext:junit:1.2.1")
    }
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
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
