import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jonisuominen.readout"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jonisuominen.readout"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Public HTTPS OAuth broker URL; never place an Oura client secret here.
        val brokerUrl = (project.findProperty("OURA_BROKER_URL") as String?).orEmpty().trim()
        buildConfigField("String", "OURA_BROKER_URL", "\"${brokerUrl.replace("\\", "").replace("\"", "")}\"")
    }

    buildTypes {
        release {
            // Shrinking stays off until a release build has been tested on a device.
            isMinifyEnabled = false
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

    lint {
        // Translations are not planned yet; everything else must pass.
        disable += setOf("MissingTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
}
