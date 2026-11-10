plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.drivevault.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.drivevault.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 180
        versionName = "1.8.0"
    }
    // Release signing: keystore at android/signing/drivevault.jks (or DV_KEYSTORE) + password in DV_KS_PASS. Never commit either.
    val ks = file(System.getenv("DV_KEYSTORE") ?: "../signing/drivevault.jks")
    signingConfigs {
        val pass = System.getenv("DV_KS_PASS")
        if (ks.exists() && pass != null) create("release") {
            storeFile = ks
            storePassword = pass
            keyAlias = System.getenv("DV_KEY_ALIAS") ?: "drivevault"
            keyPassword = System.getenv("DV_KEY_PASS") ?: pass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.maxHeapSize = "3g"; it.systemProperty("robolectric.logging", "stdout") } } }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Bundle the desktop app (../../desktop) into the APK, so a vault created on the phone also opens on computers.
val copyDesktopApp by tasks.registering(Copy::class) {
    from(rootProject.file("../desktop")) { exclude("**/__pycache__/**") }
    into(layout.buildDirectory.dir("generated/desktopAssets/desktop"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/desktopAssets"))
tasks.named("preBuild") { dependsOn(copyDesktopApp) }
