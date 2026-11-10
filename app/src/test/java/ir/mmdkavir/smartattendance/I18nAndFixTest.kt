package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.i18n.Fa
import ir.mmdkavir.smartattendance.i18n.GUIDE_FA
import ir.mmdkavir.smartattendance.i18n.Lang
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.util.Fix
import ir.mmdkavir.smartattendance.util.FixFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nAndFixTest {
    @After
    fun reset() {
        Lang.code = "en"
    }

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val callRegex = Regex("""(?<![A-Za-z0-9_.])t\(\s*"((?:[^"\\]|\\.)*)"""")
    private val arabic = Regex("[\\u0600-\\u06FF]")
    private val sameInBothLanguages = setOf(
        "GitHub", "GitHub Actions", "Chat ID", "Personal Access Token",
        "Add Smart Attendance workflow", "Update Smart Attendance workflow"
    )

    @Test
    fun everyTranslatedCallHasAPersianEntry() {
        assertTrue("sources not found", sources().isNotEmpty())
        val missing = mutableListOf<String>()
        sources().filterNot { it.path.contains("/i18n/") }.forEach { file ->
            callRegex.findAll(file.readText()).forEach { m ->
                if (m.groupValues[1] !in Fa.map) missing += "${file.name}: ${m.groupValues[1]}"
            }
        }
        assertTrue("Missing Persian translations:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun noUnwrappedLiteralInTextCalls() {
        val bad = mutableListOf<String>()
        val literalText = Regex("""(?<![A-Za-z0-9_])Text\(\s*"([^"]*)"""")
        sources().filter { it.path.contains("/ui/") }.forEach { file ->
            literalText.findAll(file.readText()).forEach { m ->
                val v = m.groupValues[1]
                if (v.any { it.isLetter() } && v != "فارسی" && v != "English" && v != "Jalali") bad += "${file.name}: $v"
            }
        }
        assertTrue("Text() with an untranslated literal:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun translationsAreRealPersianWithMatchingPlaceholders() {
        val token = Regex("%(?:%|[-#+ 0,(]*\\d*(?:\\.\\d+)?[sdf])")
        val problems = mutableListOf<String>()
        Fa.map.forEach { (en, fa) ->
            if (fa.isBlank()) problems += "blank: $en"
            if (en !in sameInBothLanguages && en.any { it.isLetter() } && !arabic.containsMatchIn(fa)) problems += "not Persian: $en"
            if (token.findAll(en).map { it.value }.toList() != token.findAll(fa).map { it.value }.toList()) problems += "placeholders differ: $en"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue(arabic.containsMatchIn(GUIDE_FA))
    }

    @Test
    fun switchingLanguageChangesTextAndKeepsNumbers() {
        Lang.code = "en"
        assertEquals("Settings", t("Settings"))
        assertEquals("100 m", t("%s m", 100))
        Lang.code = "fa"
        assertEquals("تنظیمات", t("Settings"))
        assertEquals("100 متر", t("%s m", 100))
        assertEquals("%.1f".format(java.util.Locale.US, 2.5).let { "$it کیلومتر" }, t("%.1f km", 2.5))
        assertEquals("Unknown text", t("Unknown text"))
    }

    @Test
    fun languageNormalization() {
        assertEquals("fa", Lang.normalize("fa"))
        assertEquals("en", Lang.normalize("en"))
        assertFalse(Lang.normalize("de").isEmpty())
    }

    // ---- location stability ----
    private val now = 1_000_000L
    private fun fix(acc: Float, ageMs: Long = 1_000, lat: Double = 31.9, lng: Double = 54.35) =
        Fix(lat, lng, acc, now - ageMs)

    @Test
    fun rejectsInvalidStaleOrInaccurateFirstFix() {
        assertFalse(FixFilter.accept(null, fix(0f), now))
        assertFalse(FixFilter.accept(null, fix(400f), now))
        assertFalse(FixFilter.accept(null, fix(10f, ageMs = 60_000), now))
        assertFalse(FixFilter.accept(null, Fix(0.0, 0.0, 10f, now - 1000), now))
        assertTrue(FixFilter.accept(null, fix(20f), now))
    }

    @Test
    fun poorFixDoesNotReplaceAGoodOne() {
        val good = fix(8f, ageMs = 3_000)
        val poor = Fix(31.905, 54.36, 120f, now - 500)
        assertFalse(FixFilter.accept(good, poor, now))
    }

    @Test
    fun olderFixNeverReplacesNewerOne() {
        val current = fix(20f, ageMs = 1_000)
        val older = Fix(31.9, 54.35, 5f, now - 5_000)
        assertFalse(FixFilter.accept(current, older, now))
    }

    @Test
    fun jitterInsideAccuracyKeepsMarkerStill() {
        val current = fix(20f, ageMs = 4_000)
        val jitter = Fix(31.90005, 54.35005, 22f, now - 500)
        assertFalse(FixFilter.accept(current, jitter, now))
    }

    @Test
    fun clearlyBetterOrRealMoveIsAccepted() {
        val current = fix(40f, ageMs = 4_000)
        assertTrue(FixFilter.accept(current, Fix(31.9, 54.35, 10f, now - 500), now))
        val moved = Fix(31.91, 54.35, 35f, now - 500)
        assertTrue(FixFilter.accept(fix(30f, ageMs = 4_000), moved, now))
    }

    @Test
    fun staleCurrentFixIsReplacedByAnyValidOne() {
        val stale = fix(5f, ageMs = 40_000)
        assertTrue(FixFilter.accept(stale, Fix(31.9, 54.35, 90f, now - 500), now))
    }
}
