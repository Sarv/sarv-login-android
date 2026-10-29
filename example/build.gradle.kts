import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sarv.authexample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sarv.authexample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The scheme of SarvConfig.REDIRECT_URI — lets the SDK receive the redirect.
        manifestPlaceholders["sarvAuthRedirectScheme"] = "com.sarv.authexample"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // In your app: implementation("com.sarv:sarvauth:0.1.0")
    implementation(project(":sarvauth"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
