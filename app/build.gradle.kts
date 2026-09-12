plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.elderbot.v1"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.elderbot.v1"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
