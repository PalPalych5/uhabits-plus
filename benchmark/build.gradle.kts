plugins {
    alias(libs.plugins.agp.test)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.isoron.uhabits.benchmark"
    compileSdk = 36

    targetProjectPath = ":uhabits-android"
    // The test process must survive force-stop / compilation of the measured app.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = !providers.gradleProperty("benchmarkProfileGeneration")
                .map { it.toBooleanStrict() }.getOrElse(false)
            // AGP TestCreationConfig gathers ProguardFileType.TEST, even in com.android.test.
            testProguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            matchingFallbacks += listOf("release")
        }
    }


    compileOptions {
        targetCompatibility(JavaVersion.VERSION_17)
        sourceCompatibility(JavaVersion.VERSION_17)
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// com.android.test otherwise also creates a debug APK targeting the ordinary app.
// Only this module is restricted; uhabits-android debug instrumentation is unchanged.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.rules)
    implementation(libs.uiautomator)
    implementation(libs.espresso.core)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.profileinstaller)
}
