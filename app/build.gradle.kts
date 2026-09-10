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
        versionCode = 1
        versionName = "0.1"
    }
}
