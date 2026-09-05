plugins {
    id("com.android.application")
}

android {
    namespace = "com.ikegami99.realityscanner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ikegami99.realityscanner"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GITHUB_REPO", "\"IKEGAMI-99/REALITY-SCANNER\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
}
