plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.moriya.phonenode"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.moriya.phonenode"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
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
    // The bundled tarballs are already gzip-compressed; store them
    // uncompressed in the APK, matching the manual pipeline's --no-compress.
    androidResources {
        noCompress += listOf("gz", "tgz")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    // Tar extraction on device (ProotManager). The manual pipeline used a
    // staged copy of this jar in app/libs; Gradle resolves it from Maven.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
