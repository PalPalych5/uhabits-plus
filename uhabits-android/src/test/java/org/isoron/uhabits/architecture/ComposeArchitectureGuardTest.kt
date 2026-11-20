/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.architecture

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Lightweight architecture guard tests for UHabit Next Compose migration.
 *
 * These tests scan source files directly (zero extra dependencies, fast execution)
 * to enforce design-system, layer-boundary, and Compose quality invariants.
 *
 * Run with: ./gradlew :uhabits-android:testDebugUnitTest --tests "*.ComposeArchitectureGuardTest"
 */
class ComposeArchitectureGuardTest {

    // --- Source root resolution ---

    private val androidSrcRoot: File by lazy {
        val projectRoot = findProjectRoot()
        File(projectRoot, "uhabits-android/src/main/java")
    }

    private val composeSrcRoot: File by lazy {
        File(androidSrcRoot, "org/isoron/uhabits/compose")
    }

    private val activitiesSrcRoot: File by lazy {
        File(androidSrcRoot, "org/isoron/uhabits/activities")
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(10) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        val fallback = File("D:/repos/uhabits-next-dev")
        if (File(fallback, "settings.gradle.kts").exists()) return fallback
        error("Cannot locate project root (settings.gradle.kts)")
    }

    private fun kotlinFiles(root: File): List<File> =
        if (!root.exists()) emptyList()
        else root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // =====================================================================
    // Guard 1: Shared design-system components must not contain raw ARGB colors
    // =====================================================================

    /**
     * Reusable design-system components in `compose/components/` must strictly use
     * semantic tokens from [UHabitComposeColors], [UHabitDesignTokens], or [MaterialTheme.colorScheme].
     * Zero hardcoded Color(0x...) literals are allowed.
     */
    @Test
    fun `shared design-system components must not contain hardcoded ARGB color literals`() {
        val componentsDir = File(composeSrcRoot, "components")
        assertTrue("components directory must exist", componentsDir.exists())

        val hardcodedColorPattern = Regex("""Color\(\s*0[xX][0-9a-fA-F]+""")
        val violations = mutableListOf<String>()

        kotlinFiles(componentsDir).forEach { file ->
            file.readLines().forEachIndexed { lineIndex, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed

                hardcodedColorPattern.findAll(line).forEach { match ->
                    violations.add(
                        "${file.name}:${lineIndex + 1} — ${match.value}  " +
                            "→ Shared components must use semantic tokens (LocalUHabitComposeColors or LocalUHabitDesignTokens)"
                    )
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} hardcoded Color(0x...) literal(s) in reusable design-system components.\n" +
                    "Shared primitives in compose/components/ must resolve colors through design tokens exclusively.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 2: Shared design-system components must not import activity code
    // =====================================================================

    /**
     * Files in `compose/components/` are reusable across all features.
     * They must never depend on feature-specific or activity code.
     */
    @Test
    fun `shared design-system components must not import activity-specific code`() {
        val componentsDir = File(composeSrcRoot, "components")
        assertTrue("components directory must exist", componentsDir.exists())

        val violations = mutableListOf<String>()

        kotlinFiles(componentsDir).forEach { file ->
            file.readLines().forEachIndexed { lineIndex, line ->
                if (line.trimStart().startsWith("import") &&
                    line.contains("org.isoron.uhabits.activities.")
                ) {
                    violations.add("${file.name}:${lineIndex + 1} — $line")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Design-system components (compose/components/) must not import activity-specific code.\n" +
                    "Keep components modular and reusable across all screens.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 3: App Compose UI files must not exceed legacy color exemptions
    // =====================================================================

    /**
     * Prevents ANY new hardcoded Color(0x...) literals from being introduced in
     * newly migrated or edited Compose UI files.
     *
     * Legitimate domain exceptions:
     * - Color pickers (palette swatches, rainbow spectrum)
     * - Pre-existing legacy screens recorded in baseline (capped count)
     */
    @Test
    fun `app Compose UI files must not introduce new hardcoded color literals`() {
        val hardcodedColorPattern = Regex("""Color\(\s*0[xX][0-9a-fA-F]+""")

        // Color pickers manipulate raw color spectrums/palettes as domain data
        val domainColorPickers = setOf(
            "UHabitColorPickerCompose.kt",
            "ComposeColorPickerDesignC.kt",
            "ColorPickerDialog.kt",
        )

        // Baseline count of pre-existing hardcoded colors in legacy hybrid screens.
        // As screens are refactored in future sprints, this count should ONLY decrease.
        val legacyExemptionBaseline = mapOf(
            "AboutComposeScreen.kt" to 2,
            "HabitDialogsCompose.kt" to 2,
            "HabitFormCompose.kt" to 14,
            "HabitListScreenCompose.kt" to 1,
            "FocusTimerCompose.kt" to 8,
            "TaskDetailCompose.kt" to 1,
            "TaskDuePickerCompose.kt" to 1,
            "TaskRowCompose.kt" to 1,
        )

        val violations = mutableListOf<String>()

        kotlinFiles(activitiesSrcRoot)
            .filter { it.name.endsWith("Compose.kt") || it.name.endsWith("Screen.kt") }
            .filter { it.name !in domainColorPickers }
            .forEach { file ->
                var fileCount = 0
                val fileViolations = mutableListOf<String>()

                file.readLines().forEachIndexed { lineIndex, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed

                    hardcodedColorPattern.findAll(line).forEach { match ->
                        fileCount++
                        fileViolations.add("${file.name}:${lineIndex + 1} — ${match.value}")
                    }
                }

                val allowed = legacyExemptionBaseline[file.name] ?: 0
                if (fileCount > allowed) {
                    val excess = fileCount - allowed
                    violations.add(
                        "${file.name}: contains $fileCount hardcoded colors (exceeds allowed baseline of $allowed by $excess).\n" +
                            fileViolations.joinToString("\n") { "      $it" }
                    )
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "New hardcoded Color(0x...) literal(s) detected in Compose UI screens!\n" +
                    "Use UHabitComposeColors, UHabitDesignTokens, or MaterialTheme.colorScheme tokens instead.\n\n" +
                    violations.joinToString("\n\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 4: Compose Preview functions should use UHabitComposeTheme
    // =====================================================================

    /**
     * Files providing @Preview composables should wrap content in UHabitComposeTheme
     * (not raw MaterialTheme) to ensure consistent token resolution.
     */
    @Test
    fun `Compose Preview functions should use UHabitComposeTheme`() {
        val violations = mutableListOf<String>()

        kotlinFiles(activitiesSrcRoot)
            .filter { it.name.endsWith("Compose.kt") || it.name.endsWith("Screen.kt") }
            .forEach { file ->
                val content = file.readText()
                if (!content.contains("@Preview")) return@forEach

                val previewSections = content.split("@Preview")
                previewSections.drop(1).forEachIndexed { index, section ->
                    val bodyEnd = section.indexOf("\n@").takeIf { it > 0 } ?: section.length
                    val body = section.substring(0, bodyEnd)
                    if (body.contains("MaterialTheme(") && !body.contains("UHabitComposeTheme(")) {
                        violations.add(
                            "${file.name} — Preview #${index + 1} uses raw MaterialTheme " +
                                "→ Use UHabitComposeTheme for consistent token resolution"
                        )
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Compose @Preview functions should use UHabitComposeTheme, not raw MaterialTheme.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 5: Core module must not import Android framework
    // =====================================================================

    /**
     * `uhabits-core` is a Kotlin Multiplatform module. It must not contain
     * direct Android framework imports (android.*, androidx.*).
     */
    @Test
    fun `core module must not import Android framework`() {
        val projectRoot = findProjectRoot()
        val coreSrc = File(projectRoot, "uhabits-core/src/commonMain/kotlin")
        if (!coreSrc.exists()) return

        val violations = mutableListOf<String>()

        kotlinFiles(coreSrc).forEach { file ->
            file.readLines().forEachIndexed { lineIndex, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations.add(
                        "${file.relativeTo(coreSrc).path}:${lineIndex + 1} — $trimmed"
                    )
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "uhabits-core (commonMain) must not import Android framework classes.\n" +
                    "Use platform abstractions or move Android-specific code to uhabits-android.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 6: Compose-only files must not inflate XML layouts
    // =====================================================================

    /**
     * Pure Compose screens should not inflate XML layouts directly.
     * Hybrid hosts that bridge View and Compose are explicitly whitelisted.
     */
    @Test
    fun `Compose-only files should not inflate XML layouts`() {
        val inflatePattern = Regex("""LayoutInflater\.from|inflate\(\s*R\.layout""")

        val hybridHostFiles = setOf(
            "ListHabitsRootView.kt",
            "HabitCardView.kt",
            "ShowHabitView.kt",
            "TimerCardView.kt",
            "TaskFocusBottomSheet.kt",
        )

        val violations = mutableListOf<String>()

        kotlinFiles(activitiesSrcRoot)
            .filter { it.name.endsWith("Compose.kt") || it.name.endsWith("ComposeScreen.kt") }
            .filter { it.name !in hybridHostFiles }
            .forEach { file ->
                file.readLines().forEachIndexed { lineIndex, line ->
                    inflatePattern.findAll(line).forEach { match ->
                        violations.add(
                            "${file.name}:${lineIndex + 1} — ${match.value}  " +
                                "→ Compose-only files should not inflate XML layouts"
                        )
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Compose-only files should not inflate XML layouts.\n" +
                    "Use Compose components or ComposeView interop in a Fragment host.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 7: Presenter state classes must be data classes
    // =====================================================================

    /**
     * Core Presenter state classes (e.g., ScoreCardState, HistoryCardState) should be
     * immutable data classes. This prevents accidental mutation of UI state.
     */
    @Test
    fun `Presenter state classes in core should be data classes`() {
        val projectRoot = findProjectRoot()
        val coreUiDir = File(projectRoot, "uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/ui")
        if (!coreUiDir.exists()) return

        val stateClassPattern = Regex("""^(?!data\s).*class\s+(\w+State)\s*\(""")
        val violations = mutableListOf<String>()

        kotlinFiles(coreUiDir).forEach { file ->
            file.readLines().forEachIndexed { lineIndex, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                stateClassPattern.find(trimmed)?.let { match ->
                    val className = match.groupValues[1]
                    if (!trimmed.contains("abstract") && !trimmed.contains("sealed") &&
                        !trimmed.contains("inner") && !trimmed.contains("interface")
                    ) {
                        violations.add("${file.name}:${lineIndex + 1} — $className should be a data class")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Presenter state classes in core/ui should be immutable data classes.\n\n" +
                    violations.joinToString("\n") { "  • $it" }
            )
        }
    }

    // =====================================================================
    // Guard 8: Structural sanity
    // =====================================================================

    @Test
    fun `design-system compose package exists and contains theme files`() {
        assertTrue(
            "compose/ package should exist at $composeSrcRoot",
            composeSrcRoot.exists()
        )
        val themeFile = File(composeSrcRoot, "UHabitComposeColors.kt")
        assertTrue(
            "UHabitComposeColors.kt should exist in compose/ package",
            themeFile.exists()
        )
        val tokensFile = File(composeSrcRoot, "UHabitDesignTokens.kt")
        assertTrue(
            "UHabitDesignTokens.kt should exist in compose/ package",
            tokensFile.exists()
        )
    }
}
