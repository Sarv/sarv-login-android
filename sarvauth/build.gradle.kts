plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.sarv"
version = "0.1.0"

android {
    namespace = "com.sarv.auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Apps set their redirect scheme with
        //   manifestPlaceholders["sarvAuthRedirectScheme"] = "com.example.app"
        // This default only lets the library itself build.
        manifestPlaceholders["sarvAuthRedirectScheme"] = "com.sarv.auth.unset"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Android's org.json is a stub in local unit tests; use the real one.
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.sarv"
            artifactId = "sarvauth"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
