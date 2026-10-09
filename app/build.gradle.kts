plugins {
    id("com.android.application")
}

android {
    namespace = "com.ryderdavid.bubblekeyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ryderdavid.bubblekeyboard"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "0.18"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // signed with the debug key until you set up your own
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // a fresh JVM per test class keeps Robolectric's native-graphics sandboxes from piling up
        unitTests.all {
            it.forkEvery = 1
            it.maxHeapSize = "1024m"
            // the preview/animation renderers are tools, not checks: run them with -Prender
            if (!project.hasProperty("render")) it.exclude("**/Render*Test*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    // WebSocket streaming to the speech service and HTTPS to the cleanup model
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
