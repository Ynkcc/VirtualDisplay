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
    
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ynk.virtualdisplay"
        minSdk = 29
        targetSdk = 36
        resConfigs("zh", "en")

        val commitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

        val gitDescribe = providers.exec {
            commandLine("git", "describe", "--tags", "HEAD")
        }.standardOutput.asText.map { it.trim() }.getOrElse("v1.0.0")

        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

        val isDirty = providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.map { it.trim().isNotEmpty() }.getOrElse(false)

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }.format(Date())

        // git describe 输出:
        //   tag 正上方: v1.1.1
        //   tag 之后:   v1.1.1-3-gabc1234
        val resolvedVersionName = gitDescribe.removePrefix("v").let { desc ->
            val dashIdx = desc.indexOf('-')
            if (dashIdx > 0) {
                val base = desc.substring(0, dashIdx)
                val afterDash = desc.substring(dashIdx + 1)
                val devCount = afterDash.substringBefore('-').toIntOrNull()
                if (devCount != null) "$base.dev$devCount" else base
            } else desc
        }

        versionCode = commitCount
        versionName = resolvedVersionName

        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("Boolean", "GIT_DIRTY", "$isDirty")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

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
            isMinifyEnabled = true
            isShrinkResources = true
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}
