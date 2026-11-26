import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

android {
    defaultConfig {
        applicationId = "com.anigooli.app"
        versionCode = 9
        versionName = "1.2.0"
    }

    val releaseStorePath = project.findProperty("storeFilePath")?.toString()?.takeIf { it.isNotBlank() }
    val hasReleaseKeystore = releaseStorePath != null && file(releaseStorePath).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            register("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = project.findProperty("storePass")?.toString().orEmpty()
                keyAlias = project.findProperty("key")?.toString().orEmpty()
                keyPassword = project.findProperty("keyPass")?.toString().orEmpty()
            }
        }
    }

    buildTypes {
        release {
            optimization { enable = true }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeystore) "release" else "debug"
            )
        }
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

    lint {
        checkDependencies = true
    }

    namespace = "com.imashnake.animite"
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi"
    )
}

dependencies {
    implementation(projects.api.anilist)
    implementation(projects.api.mal)
    implementation(projects.api.preferences)
    implementation(projects.core.resource)
    implementation(projects.core.ui)
    implementation(projects.media)
    implementation(projects.navigation)
    implementation(projects.profile)
    implementation(projects.settings)
    implementation(projects.social)
    implementation(projects.anime)
    implementation(projects.manga)
    implementation(projects.explore)
    implementation(projects.streaming)
    implementation(projects.player)
    implementation(projects.downloads)
    implementation(projects.library)
    implementation(projects.notifications)

    // AndroidX
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycleRuntimeKtx)
    implementation(libs.androidx.navigationCompose)
    implementation(libs.androidx.work.runtimeKtx)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigationCompose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Compose
    implementation(libs.bundles.compose)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.text.googleFonts)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.toolingPreview)

    // Coil
    implementation(libs.bundles.coil)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.collectionsImmutable)

    implementation(libs.materialKolor)

    coreLibraryDesugaring(libs.android.desugaring)

    testImplementation(libs.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espressoCore)
    androidTestImplementation(libs.compose.test.ui.testJunit4)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    basePath = rootDir.absolutePath
}
