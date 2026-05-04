import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.TapLinkX3.app"
    compileSdk = 36
    val rawLoginEmail = localProperties.getProperty("AIZ_LOGIN_EMAIL") ?: System.getenv("AIZ_LOGIN_EMAIL") ?: ""
    val rawLoginPassword = localProperties.getProperty("AIZ_LOGIN_PASSWORD") ?: System.getenv("AIZ_LOGIN_PASSWORD") ?: ""
    val rawGroqApiKey = localProperties.getProperty("GROQ_API_KEY") ?: System.getenv("GROQ_API_KEY") ?: ""
    val loginEmail = rawLoginEmail.replace("\\", "\\\\").replace("\"", "\\\"")
    val loginPassword = rawLoginPassword.replace("\\", "\\\\").replace("\"", "\\\"")
    val groqApiKey = rawGroqApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

    defaultConfig {
        applicationId = "com.TapLinkX3.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "1.5.0"
        buildConfigField("String", "LOGIN_EMAIL", "\"$loginEmail\"")
        buildConfigField("String", "LOGIN_PASSWORD", "\"$loginPassword\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"  // Add this line
            // Append .debug to the package name for debug builds
            resValue("string", "app_name", "AIZ")
        }

        release {
            resValue("string", "app_name", "AIZ")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "TaplinkX3.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)

    // Google
    implementation(libs.material)
    implementation(libs.core)
    implementation(libs.gson)
    implementation(libs.zxing.embedded)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Local AARs
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))
    implementation(fileTree("libs"))

    // OkHttp for API requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
