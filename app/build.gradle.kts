plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vip.workbuddyrelay.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "vip.workbuddyrelay.app"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "vip.workbuddyrelay.app.SmokeInstrumentation"
        versionCode = 11
        versionName = "0.3.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../ci/release.keystore")
            storePassword = "workbuddyrelay"
            keyAlias = "workbuddyrelay"
            keyPassword = "workbuddyrelay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
