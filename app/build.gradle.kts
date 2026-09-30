import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val robolectricRuntime: Configuration by configurations.creating
val robolectricRuntimeDir = layout.buildDirectory.dir("robolectric-runtime")
val copyRobolectricRuntime = tasks.register<Copy>("copyRobolectricRuntime") {
    from(robolectricRuntime)
    into(robolectricRuntimeDir)
}

android {
    namespace = "au.ingo.betterattend"
    compileSdk = 37

    defaultConfig {
        applicationId = "au.ingo.betterattend"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"https://attend.hackclub.com\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"aaa422633e9a7df85892eb2ba84f02d9\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"attend://oauth/callback\"")
    }

    // Release signing: env vars (CI) or a gitignored keystore.properties; otherwise fall back to the
    // debug key so a locally built release APK always installs.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
    val releaseStoreFile = signingValue("ATTEND_KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("ATTEND_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ATTEND_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ATTEND_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "1536m"
                // Robolectric can't download its runtime through the sandbox proxy, so Gradle fetches it.
                it.dependsOn(copyRobolectricRuntime)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricRuntimeDir.get().asFile.absolutePath)
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.material.color.utilities)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
}
