package org.isoron.uhabits.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, separately selected profile generation; this does not measure startup latency. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        check(InstrumentationRegistry.getArguments().getString("generateBaselineProfile") == "true") {
            "Profile generation requires separate authorization: -e generateBaselineProfile true"
        }
        val scenarios = ProfileableReleaseQaScenarios()
        scenarios.verifySafetyAndIsolationGuards()
        val target = InstrumentationRegistry.getInstrumentation().context.createPackageContext(
            ProfileableReleaseQaScenarios.TARGET_PACKAGE, 0
        )
        val generationFlag = target.resources.getIdentifier(
            "benchmark_profile_generation", "bool", ProfileableReleaseQaScenarios.TARGET_PACKAGE
        )
        check(generationFlag != 0 && target.resources.getBoolean(generationFlag)) {
            "Build the isolated target with -PbenchmarkProfileGeneration=true before collecting profiles"
        }
        // Provisioning is a separate, explicitly authorized step. Never seed/reset in collect:
        // Macrobenchmark may restart the target repeatedly while converging the profile.
        scenarios.verifyFixtureReady()
        baselineProfileRule.collect(
            packageName = ProfileableReleaseQaScenarios.TARGET_PACKAGE,
            includeInStartupProfile = false
        ) {
            startActivityAndWait(
                Intent().setClassName(
                    ProfileableReleaseQaScenarios.TARGET_PACKAGE,
                    ProfileableReleaseQaScenarios.MAIN_ACTIVITY
                ).putExtra("main.destination", "HABITS")
            )
            // Verify persisted selection after the rule's process/compilation preparation.
            scenarios.verifyFixtureReady()
            scenarios.runPopulatedJourneys()
        }
    }
}
