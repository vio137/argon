plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

android {
    namespace = "me.ik.argon"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.ik.argon"
        minSdk = 29
        targetSdk = 37
        versionCode = 21
        versionName = "1.0.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        compose = true
        buildConfig = true // About dialog shows VERSION_NAME/VERSION_CODE
    }

    // lintVital* stalls 10+ min on the 2GB local build box; skip it there via
    // -Pargon.skipLintVital=true (kept in ~/.gradle/gradle.properties, uncommitted).
    // CI (android-release-lint workflow) always runs the gate.
    lint {
        checkReleaseBuilds =
            !providers.gradleProperty("argon.skipLintVital").map { it.toBoolean() }.getOrElse(false)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testBuildType = "release"

    buildTypes {
        release {
            // R8: keep-rules in proguard-rules.pro cover every JNI entry
            // point (org.servo.servoview, MediaFocus) and the serialization
            // models. Any new external/JNI class needs a rule before this
            // ships; verified by the androidTest matrix, not by compiling.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}

android {
    packagingOptions {
        jniLibs { useLegacyPackaging = true }
    }
}
