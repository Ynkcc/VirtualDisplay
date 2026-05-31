import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ynk.virtualdisplay"
    
    // Restore the specific SDK version that was being used
    compileSdk {
        @Suppress("UnstableApiUsage")
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ynk.virtualdisplay"
        minSdk = 29
        targetSdk = 35 // targetSdk 36 is not yet stable, 35 is recommended for now

        // 自动计算版本号与获取构建信息
        val commitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

        val isDirty = providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.map { it.trim().isNotEmpty() }.getOrElse(false)

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }.format(Date())

        versionCode = commitCount
        versionName = "1.0.$commitCount"

        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("Boolean", "GIT_DIRTY", "$isDirty")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Explicitly set the Java toolchain for Android tasks
    @Suppress("UnstableApiUsage")
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    val signingProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile: File? = (System.getenv("RELEASE_STORE_FILE").takeIf { !it.isNullOrEmpty() }
        ?: signingProperties.getProperty("release.storeFile"))?.let { rootProject.file(it) }
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD").takeIf { !it.isNullOrEmpty() }
        ?: signingProperties.getProperty("release.storePassword")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD").takeIf { !it.isNullOrEmpty() }
        ?: signingProperties.getProperty("release.keyPassword")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS").takeIf { !it.isNullOrEmpty() }
        ?: signingProperties.getProperty("release.keyAlias")
        ?: "key0"

    signingConfigs {
        if (releaseStoreFile != null && releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    
    sourceSets {
        getByName("main") {
            java.directories.add("src/main/java")
            java.directories.add("../scrcpy/server/src/main/java")
            aidl.directories.add("src/main/aidl")
            aidl.directories.add("../scrcpy/server/src/main/aidl")
        }
    }
}

// Set toolchain for Kotlin tasks as well
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
}