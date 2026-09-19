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
        versionCode = 2
        versionName = "0.1.1"
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
