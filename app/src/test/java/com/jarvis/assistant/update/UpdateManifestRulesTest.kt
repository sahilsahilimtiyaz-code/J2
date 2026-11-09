package com.jarvis.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestRulesTest {

    private val published = """
        {
          "versionCode": 245,
          "versionName": "1.0.145",
          "apkUrl": "https://example.com/octa-jarvis.apk",
          "downloadPageUrl": "https://example.com/",
          "releaseNotes": "Faster offline replies; sharper taps.",
          "releasedAt": "2026-11-09",
          "sha256": "abc123",
          "minSupportedVersionCode": 120
        }
    """.trimIndent()

    @Test
    fun parses_the_published_manifest() {
        val manifest = UpdateManifestRules.parse(published)
        assertNotNull(manifest)
        assertEquals(245L, manifest!!.versionCode)
        assertEquals("1.0.145", manifest.versionName)
        assertEquals("https://example.com/octa-jarvis.apk", manifest.apkUrl)
        assertEquals("abc123", manifest.sha256)
    }

    @Test
    fun ignores_unknown_fields_and_rejects_junk() {
        val withExtras = published.replace("\"releasedAt\"", "\"channel\": \"beta\", \"releasedAt\"")
        assertNotNull(UpdateManifestRules.parse(withExtras))

        assertNull(UpdateManifestRules.parse("not json"))
        assertNull(UpdateManifestRules.parse("{}"))
        assertNull(UpdateManifestRules.parse("{\"versionCode\": 0, \"versionName\": \"1.0.0\"}"))
    }

    @Test
    fun only_newer_version_codes_count_as_an_update() {
        val manifest = UpdateManifestRules.parse(published)!!
        assertTrue(UpdateManifestRules.isUpdateAvailable(manifest, 100L))
        assertFalse(UpdateManifestRules.isUpdateAvailable(manifest, 245L))
        assertFalse(UpdateManifestRules.isUpdateAvailable(manifest, 300L))
    }

    @Test
    fun a_manifest_without_any_download_target_is_not_an_update() {
        val manifest = UpdateManifestRules.parse(
            """{"versionCode": 999, "versionName": "2.0.0"}""",
        )!!
        assertFalse(UpdateManifestRules.isUpdateAvailable(manifest, 100L))
    }

    @Test
    fun download_url_prefers_the_apk_then_the_page_then_the_fallback() {
        val full = UpdateManifestRules.parse(published)!!
        assertEquals("https://example.com/octa-jarvis.apk", UpdateManifestRules.resolveDownloadUrl(full, "fallback"))

        val pageOnly = UpdateManifestRules.parse(
            """{"versionCode": 250, "versionName": "1.0.150", "downloadPageUrl": "https://example.com/download"}""",
        )!!
        assertEquals("https://example.com/download", UpdateManifestRules.resolveDownloadUrl(pageOnly, "fallback"))

        val empty = UpdateManifest(versionCode = 260, versionName = "1.0.160")
        assertEquals("fallback", UpdateManifestRules.resolveDownloadUrl(empty, "fallback"))
    }

    @Test
    fun mandatory_update_is_flagged_when_the_build_is_below_the_supported_floor() {
        val manifest = UpdateManifestRules.parse(published)!!
        assertTrue(AvailableUpdate(manifest, currentVersionCode = 90L).isMandatory)
        assertFalse(AvailableUpdate(manifest, currentVersionCode = 130L).isMandatory)
    }
}
