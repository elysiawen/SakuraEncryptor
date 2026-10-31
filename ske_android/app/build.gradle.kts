import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signing material lives outside version control; see keystore.properties.
// Absent file simply means release builds come out unsigned instead of failing.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")

android {
    namespace = "com.sakura.encryptor"
    compileSdk = 35

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.sakura.encryptor"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        vectorDrawables { useSupportLibrary = true }
    }

    /**
     * Two APKs from one tree: `plain` is the regular build, `openlist` bundles
     * the in-process OpenList server. The AAR is a 28 MB binary built by
     * openlist-lib/build.ps1 and is not committed, so it is wired only into the
     * `openlist` flavor and the build fails there when it is missing.
     */
    flavorDimensions += "openlist"
    productFlavors {
        create("plain") {
            dimension = "openlist"
        }
        create("openlist") {
            dimension = "openlist"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 shrinks and optimises: smaller APK and a faster cold start.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.util.Log is only a stub in local unit tests: every call
            // throws unless this is enabled. Any code path that logs on failure —
            // which is exactly where the interesting cases live — would otherwise
            // fail the test that exercises it.
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    // Fingerprint / face unlock for the launch gate.
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)

    // In-process OpenList, bound from Go by openlist-lib/build.ps1. The AAR is
    // a 28 MB artifact and is not committed, so only the `openlist` flavor
    // carries it; `plain` keeps building without any Go toolchain.
    // The flavor-specific configuration name has no typed accessor in this
    // script, so it is addressed by name.
    add("openlistImplementation", files("libs/openlistlib.aar"))

    testImplementation(libs.junit)
}

// A missing AAR would otherwise yield an "openlist" APK without the server, so
// the assemble/bundle tasks of that flavor refuse to run without it.
val openListAar = layout.projectDirectory.file("libs/openlistlib.aar")
val verifyOpenListLib = tasks.register("verifyOpenListLib") {
    val aar = openListAar.asFile
    doLast {
        if (!aar.exists()) {
            throw GradleException(
                "缺少 ${aar}，无法打包 openlist 版本。先运行 ske_android/openlist-lib/build.ps1。"
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("assembleOpenlist") || name.startsWith("bundleOpenlist")) {
        dependsOn(verifyOpenListLib)
    }
}
