plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = "0.1.1"

base {
    archivesName = "loculus-$appVersion"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
        freeCompilerArgs.addAll(
            "-Wextra",
            "-Xjsr305=strict",
        )
    }
}

android {
    namespace = "de.schaefer.eosgps"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.schaefer.eosgps"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/release.keystore")
            storePassword = findProperty("RELEASE_STORE_PASSWORD") as String
            keyAlias = "apps"
            keyPassword = findProperty("RELEASE_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        checkAllWarnings = true
        checkReleaseBuilds = true
        checkTestSources = true
        checkGeneratedSources = true
        disable += "LogConditional" // R8 strips Log calls in release via proguard-rules.pro
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
}
