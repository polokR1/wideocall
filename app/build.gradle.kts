plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "pl.wideocall.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.wideocall.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.13.1"
        buildConfigField("boolean", "FCM_ENABLED", "true")
        buildConfigField(
            "String",
            "CALL_WAKE_URL",
            "\"https://wideocall-notify.wideocall-polokr1.workers.dev/notify-call\"",
        )
        buildConfigField(
            "String",
            "TURN_CREDENTIALS_URL",
            "\"https://wideocall-notify.wideocall-polokr1.workers.dev/turn-credentials\"",
        )
        buildConfigField(
            "String",
            "SIGNALING_HTTP_URL",
            "\"https://wideocall-notify.wideocall-polokr1.workers.dev\"",
        )
        buildConfigField(
            "String",
            "SIGNALING_WEBSOCKET_URL",
            "\"wss://wideocall-notify.wideocall-polokr1.workers.dev/signal\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    // Nowsze BoM-y zawierają Auth z metadanymi Kotlin 2.3; 34.12.0 jest ostatnią
    // sprawdzoną wersją zgodną z obecną linią Kotlin 2.1 tego projektu.
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.12.0")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(firebaseBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.core:core-telecom:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-messaging")
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
