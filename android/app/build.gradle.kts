import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// A release build is signed with the host's keystore when CI provides one
// (`EMRG_KEYSTORE_FILE`, `EMRG_KEYSTORE_PASSWORD`, `EMRG_KEY_ALIAS`,
// `EMRG_KEY_PASSWORD`). Without it the release variant is signed with the debug
// key so the artifact installs on a test phone; the build log says so and the
// artifact is not a Play upload until a real keystore is supplied.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    System.getenv(name) ?: signingProps.getProperty(name)

val releaseStore = secret("EMRG_KEYSTORE_FILE")
val hasReleaseKey = !releaseStore.isNullOrBlank() && file(releaseStore).exists()

android {
    namespace = "com.emrg.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.emrg.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 363
        versionName = "0.3.63"
        buildConfigField("String", "GATEWAY_API_VERSION", "\"1.0\"")
        buildConfigField("String", "APP_BUILD", "\"android\"")
    }

    signingConfigs {
        // One shared debug key for every build, so CI APKs update the
        // installed app instead of being refused for a signature
        // mismatch. The keystore is the standard public Android debug
        // key (alias androiddebugkey, password "android", the same one
        // every SDK generates); it is not a secret and not the release
        // key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = secret("EMRG_KEYSTORE_PASSWORD")
                keyAlias = secret("EMRG_KEY_ALIAS")
                keyPassword = secret("EMRG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
