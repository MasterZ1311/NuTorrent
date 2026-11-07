import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nutorrent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nutorrent"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val localProps = Properties()
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                localFile.inputStream().use { localProps.load(it) }
            }
            val keystorePath = System.getenv("KEYSTORE_FILE")
                ?: (project.findProperty("KEYSTORE_FILE") as? String)
                ?: localProps.getProperty("KEYSTORE_FILE")
            // Explicit configuration required: do not automatically bind to nutorrent-release.jks
            val keystoreFile = if (!keystorePath.isNullOrEmpty()) file(keystorePath) else null
            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: (project.findProperty("KEYSTORE_PASSWORD") as? String)
                ?: localProps.getProperty("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: (project.findProperty("KEY_ALIAS") as? String)
                ?: localProps.getProperty("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: (project.findProperty("KEY_PASSWORD") as? String)
                ?: localProps.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            val hasExplicitCredentials = releaseSigning.storeFile?.exists() == true &&
                !releaseSigning.storePassword.isNullOrEmpty() &&
                !releaseSigning.keyAlias.isNullOrEmpty() &&
                !releaseSigning.keyPassword.isNullOrEmpty()

            signingConfig = if (hasExplicitCredentials) {
                releaseSigning
            } else if (project.hasProperty("allowDebugSigningForRelease")) {
                signingConfigs.getByName("debug")
            } else {
                null // Unsigned release artifact produced safely when credentials are not configured
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "OldTargetApi",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ObsoleteSdkInt"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.documentfile)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}

afterEvaluate {
    if (project.hasProperty("requireReleaseSigning")) {
        val releaseSigning = android.signingConfigs.getByName("release")
        val hasExplicitCredentials = releaseSigning.storeFile?.exists() == true &&
            !releaseSigning.storePassword.isNullOrEmpty() &&
            !releaseSigning.keyAlias.isNullOrEmpty() &&
            !releaseSigning.keyPassword.isNullOrEmpty()
        if (!hasExplicitCredentials) {
            tasks.matching { it.name.contains("Release", ignoreCase = true) }.configureEach {
                doFirst {
                    throw GradleException("Release build requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD to be configured.")
                }
            }
        }
    }
}
