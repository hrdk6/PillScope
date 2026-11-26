plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val debugApiBaseUrl: String =
    (project.findProperty("PILLSCOPE_DEBUG_API_URL") as? String)
        ?: System.getenv("PILLSCOPE_DEBUG_API_URL")
        ?: "http://10.0.2.2:8000/"

val releaseApiBaseUrl: String =
    (project.findProperty("PILLSCOPE_RELEASE_API_URL") as? String)
        ?: System.getenv("PILLSCOPE_RELEASE_API_URL")
        ?: "https://api.pillscope.app/"

val privacyPolicyUrl: String =
    (project.findProperty("PILLSCOPE_PRIVACY_URL") as? String)
        ?: System.getenv("PILLSCOPE_PRIVACY_URL")
        ?: "${releaseApiBaseUrl.trimEnd('/')}/privacy"

val accountDeletionUrl: String =
    (project.findProperty("PILLSCOPE_DELETION_URL") as? String)
        ?: System.getenv("PILLSCOPE_DELETION_URL")
        ?: "${releaseApiBaseUrl.trimEnd('/')}/account-deletion"

val releaseKeystorePath: String? =
    (project.findProperty("PILLSCOPE_KEYSTORE_PATH") as? String)
        ?: System.getenv("PILLSCOPE_KEYSTORE_PATH")
val releaseKeystorePassword: String? =
    (project.findProperty("PILLSCOPE_KEYSTORE_PASSWORD") as? String)
        ?: System.getenv("PILLSCOPE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? =
    (project.findProperty("PILLSCOPE_KEY_ALIAS") as? String)
        ?: System.getenv("PILLSCOPE_KEY_ALIAS")
val releaseKeyPassword: String? =
    (project.findProperty("PILLSCOPE_KEY_PASSWORD") as? String)
        ?: System.getenv("PILLSCOPE_KEY_PASSWORD")

android {
    namespace = "com.pillscope.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pillscope.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        buildConfigField("String", "ACCOUNT_DELETION_URL", "\"$accountDeletionUrl\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = releaseKeystorePath?.let { file(it) }
            if (ksFile != null && ksFile.exists() && !releaseKeystorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank()) {
                storeFile = ksFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            require(releaseApiBaseUrl.startsWith("https://")) {
                "Release API_BASE_URL must use HTTPS (got: $releaseApiBaseUrl)"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            val relSigning = signingConfigs.getByName("release")
            if (relSigning.storeFile != null) {
                signingConfig = relSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite", "litertlm")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.litert)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
