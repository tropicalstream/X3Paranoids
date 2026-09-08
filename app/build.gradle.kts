plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.x3paranoids"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.x3paranoids"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Zero dependencies: OpenGL ES 3.0 via the framework, SFX synthesised at runtime,
// voice lines pre-rendered into assets/voice, one bundled music track.
dependencies {
}
