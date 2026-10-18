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

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt")
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

dependencies {
    implementation(libs.junit)
    implementation(libs.rules)
    implementation(libs.uiautomator)
    implementation(libs.espresso.core)
}
