plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                "ij_kotlin_name_count_to_use_star_import" to "99",
                "ij_kotlin_name_count_to_use_star_import_for_members" to "99",
                "ktlint_standard_no-wildcard-imports" to "disabled"
            )
        )
    }
}

android {
    namespace = "com.yashikota.omaigenzo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yashikota.omaigenzo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -DLIBRAW_NOTHREADS")
            }
        }

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Convenient CLI task aliases
tasks.register("format") {
    group = "formatting"
    description = "Formats all Kotlin source code using Spotless and ktlint."
    dependsOn("spotlessApply")
}

tasks.register("lintCheck") {
    group = "verification"
    description = "Runs Spotless check to verify code formatting and style compliance."
    dependsOn("spotlessCheck")
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs all unit tests for the project."
    dependsOn("testDebugUnitTest")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
