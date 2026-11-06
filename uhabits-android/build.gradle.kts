/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint.plugin)
    alias(libs.plugins.mokkery)
}

tasks.compileLint {
    dependsOn("updateTranslators")
}

/*
Added on top of kotlinOptions to work around this issue:
https://youtrack.jetbrains.com/issue/KTIJ-24311/task-current-target-is-17-and-kaptGenerateStubsProductionDebugKotlin-task-current-target-is-1.8-jvm-target-compatibility-should#focus=Comments-27-6798448.0-0
Updating gradle might fix this, so try again in the future to remove this and run:
./gradlew --rerun-tasks :uhabits-android:kaptGenerateStubsReleaseKotlin
If this doesn't produce any warning, try to remove it.
 */
kotlin {
    jvmToolchain(17)
}

val prepareAndroidCoreAssets by tasks.registering(Sync::class) {
    from("../uhabits-core/assets/main") {
        // Android uses platform fonts and its own root FontAwesome asset.
        exclude(
            "fonts/FontAwesome.ttf",
            "fonts/NotoSans-Bold.ttf",
            "fonts/NotoSans-Regular.ttf",
        )
    }
    into(layout.buildDirectory.dir("generated/coreAssets"))
}

// Narrow, explicitly opt-in smoke APK. Normal androidTest keeps the complete suite.
val calendarSmoke = providers.gradleProperty("calendarSmokeSources").orNull == "true"
val prepareCalendarSmokeSources by tasks.registering(Sync::class) {
    from("src/androidTest/java") {
        include("**/TaskDraftQaRunner.kt", "**/TaskDraftLifecycleTest.kt", "**/TaskListVisualTest.kt", "**/TaskCalendarVisualTest.kt")
    }
    into(layout.buildDirectory.dir("generated/calendarSmokeSources"))
}
if (calendarSmoke) {
    require(providers.gradleProperty("taskDraftQaTest").orNull == "true") { "Calendar smoke requires TaskDraftQaRunner" }
    tasks.matching { it.name == "compileDebugAndroidTestKotlin" || it.name == "compileDebugAndroidTestJavaWithJavac" || it.name == "kspDebugAndroidTestKotlin" }.configureEach {
        dependsOn(prepareCalendarSmokeSources)
    }
}

android {
    namespace = "org.isoron.uhabits"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", prepareAndroidCoreAssets)
        }
        if (calendarSmoke) getByName("androidTest").java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/calendarSmokeSources")))
    }

    defaultConfig {
        versionCode = 20301
        versionName = "2.3.1"
        minSdk = 28
        targetSdk = 36
        applicationId = "org.isoron.uhabits.plus"
        // Database-only migration checks must not execute the application's legacy test reset.
        testInstrumentationRunner = if (providers.gradleProperty("taskDraftQaTest").orNull == "true")
            "org.isoron.uhabits.activities.tasks.TaskDraftQaRunner"
        else if (providers.gradleProperty("dueMigrationTest").orNull == "true")
            "org.isoron.uhabits.database.TaskDueMigrationRunner"
        else "androidx.test.runner.AndroidJUnitRunner"
    }

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }

    val designatedDebugKeystorePath = if (providers.gradleProperty("uhabit.debug.keystore").isPresent) {
        providers.gradleProperty("uhabit.debug.keystore").get().takeIf { it.isNotBlank() }
    } else if (System.getenv("UHABIT_DEBUG_KEYSTORE") != null) {
        System.getenv("UHABIT_DEBUG_KEYSTORE").takeIf { it.isNotBlank() }
    } else {
        localProperties.getProperty("uhabit.debug.keystore")?.takeIf { it.isNotBlank() }
    }

    signingConfigs {
        getByName("debug") {
            if (!designatedDebugKeystorePath.isNullOrBlank()) {
                val designatedFile = file(designatedDebugKeystorePath)
                if (!designatedFile.isFile) {
                    throw GradleException(
                        "Designated UHabit debug keystore does not exist at: ${designatedFile.absolutePath}. " +
                        "Verify 'uhabit.debug.keystore' in local.properties or UHABIT_DEBUG_KEYSTORE."
                    )
                }
                storeFile = designatedFile
                storePassword = localProperties.getProperty("uhabit.debug.storePassword")
                    ?: providers.gradleProperty("uhabit.debug.storePassword").orNull
                    ?: System.getenv("UHABIT_DEBUG_STORE_PASSWORD")
                    ?: "android"
                keyAlias = localProperties.getProperty("uhabit.debug.keyAlias")
                    ?: providers.gradleProperty("uhabit.debug.keyAlias").orNull
                    ?: System.getenv("UHABIT_DEBUG_KEY_ALIAS")
                    ?: "androiddebugkey"
                keyPassword = localProperties.getProperty("uhabit.debug.keyPassword")
                    ?: providers.gradleProperty("uhabit.debug.keyPassword").orNull
                    ?: System.getenv("UHABIT_DEBUG_KEY_PASSWORD")
                    ?: "android"
            } else {
                val allowDefault = providers.gradleProperty("uhabit.allowDefaultDebugKeystore")
                    .map { it.toBooleanStrict() }.getOrElse(false)
                if (!allowDefault) {
                    throw GradleException(
                        "Designated UHabit debug keystore is not configured. " +
                        "Set 'uhabit.debug.keystore' in local.properties or UHABIT_DEBUG_KEYSTORE environment variable. " +
                        "Silent fallback to the machine's default debug keystore is disabled to protect installation compatibility."
                    )
                }
            }
        }

        if (System.getenv("LOOP_KEY_ALIAS") != null) {
            create("release") {
                keyAlias = System.getenv("LOOP_KEY_ALIAS")
                keyPassword = System.getenv("LOOP_KEY_PASSWORD")
                storeFile = file(System.getenv("LOOP_KEY_STORE"))
                storePassword = System.getenv("LOOP_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.txt")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
            // Generation needs unobfuscated signatures; performance QA keeps release R8.
            val profileGeneration = providers.gradleProperty("benchmarkProfileGeneration")
                .map { it.toBooleanStrict() }.getOrElse(false)
            isMinifyEnabled = !profileGeneration
            resValue("bool", "benchmark_profile_generation", profileGeneration.toString())
        }

        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        targetCompatibility(JavaVersion.VERSION_17)
        sourceCompatibility(JavaVersion.VERSION_17)
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures.viewBinding = true
    buildFeatures.compose = true
    lint.abortOnError = false
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(prepareAndroidCoreAssets)
}

mokkery {
    defaultMockMode.set(dev.mokkery.MockMode.autofill)
    stubs.allowClassInheritance.set(true)
    stubs.allowConcreteClassInstantiation.set(true)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    "benchmarkImplementation"(libs.profileinstaller)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.appIntro)
    implementation(libs.jsr305)
    implementation(libs.kotlin.inject.runtime)
    implementation(libs.guava)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.appcompat)
    implementation(libs.legacy.preference.v14)
    implementation(libs.legacy.support.v4)
    implementation(libs.material)
    implementation(libs.documentfile)
    implementation(libs.security.crypto)
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    implementation(libs.opencsv)
    implementation(libs.konfetti.xml)
    implementation(project(":uhabits-core"))
    ksp(libs.kotlin.inject.compiler)

    androidTestImplementation(libs.annotation)
    androidTestImplementation(libs.kotlin.inject.runtime)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.ktor.jackson)
    androidTestImplementation(libs.rules)
    androidTestImplementation(libs.uiautomator)

    testImplementation(libs.kotlin.inject.runtime)
    testImplementation(libs.junit.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation("org.json:json:20240303")
}
