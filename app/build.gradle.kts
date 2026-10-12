plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ryderdavid.bubblekeyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ryderdavid.bubblekeyboard"
        minSdk = 26
        targetSdk = 37
        versionCode = 36
        versionName = "0.36"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // signed with the debug key until you set up your own
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
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
    // the settings screen: Jetpack Compose with Material 3 (dynamic color)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

composeCompiler {
    // the mapping task doesn't survive Gradle's configuration cache here, and we don't ship mappings
    includeComposeMappingFile.set(false)
}
