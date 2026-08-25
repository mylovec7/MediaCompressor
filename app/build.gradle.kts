plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vr3th.mediacompressor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vr3th.mediacompressor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Self-signed release key so CI-built APKs install directly on a device
    // without extra secrets setup. Fine for personal/offline use; if this app
    // is ever published, generate a private keystore and keep it OUT of the
    // repo instead (see README).
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "mediacompressor"
            keyAlias = "mediacompressor"
            keyPassword = "mediacompressor"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
