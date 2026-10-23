package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.MalformedVersionException
import io.github.veganmarshmallow.taskboard.core.update.Release
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAssets
import io.github.veganmarshmallow.taskboard.core.update.ReleaseJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParsingTest {
    private fun asset(id: Long, name: String, size: Long = 10, uploaded: Boolean = true) =
        ReleaseAsset(id, name, size, assetUrl(id), uploaded)

    @Test fun parsesARealShapedRelease() {
        val release = ReleaseJsonParser.parse(
            releaseJson(assetJson(APK_ID, "taskboard-widget.apk", 704244), assetJson(VERSION_ID, "version.json", 120)),
        )
        assertEquals("latest", release.tag)
        assertEquals(RELEASE_PAGE, release.htmlUrl)
        assertTrue(release.notes!!.contains("versionCode 9"))
        assertEquals(listOf(asset(APK_ID, "taskboard-widget.apk", 704244), asset(VERSION_ID, "version.json", 120)), release.assets)
    }

    @Test fun skipsAssetsWithUnusableFields() {
        val bad = listOf(
            mapOf("id" to 1L, "name" to "a.apk", "size" to 1L), // no url
            mapOf("id" to 2L, "name" to "a.apk", "size" to 1L, "url" to "https://evil.example/releases/assets/2"),
            mapOf("id" to 3L, "name" to "a.apk", "size" to 1L, "url" to "http://api.github.com/x"),
            mapOf("id" to 0L, "name" to "a.apk", "size" to 1L, "url" to assetUrl(0)),
            mapOf("id" to "4", "name" to "a.apk", "size" to 1L, "url" to assetUrl(4)),
            mapOf("id" to 5L, "name" to "", "size" to 1L, "url" to assetUrl(5)),
            mapOf("id" to 6L, "name" to null, "size" to 1L, "url" to assetUrl(6)),
            mapOf("id" to 7L, "name" to "a.apk", "size" to -1L, "url" to assetUrl(7)),
            mapOf("id" to 8L, "name" to "a.apk", "size" to null, "url" to assetUrl(8)),
        )
        val text = io.github.veganmarshmallow.taskboard.core.json.JsonWriter.write(
            mapOf("tag_name" to "latest", "assets" to bad + listOf("junk", 5L, null, listOf(1L))),
        )
        assertEquals(emptyList<ReleaseAsset>(), ReleaseJsonParser.parse(text).assets)
    }

    @Test fun missingStateMeansUploaded() {
        val text = """{"assets":[{"id":1,"name":"x","size":2,"url":"${assetUrl(1)}"}]}"""
        assertTrue(ReleaseJsonParser.parse(text).assets.single().uploaded)
        assertEquals("", ReleaseJsonParser.parse(text).tag)
        assertNull(ReleaseJsonParser.parse(text).notes)
        assertNull(ReleaseJsonParser.parse(text).htmlUrl)
        assertNull(ReleaseJsonParser.parse("""{"html_url":42,"assets":[]}""").htmlUrl)
    }

    @Test(expected = MalformedVersionException::class) fun rejectsNoAssetsList() { ReleaseJsonParser.parse("""{"tag_name":"latest"}""") }

    @Test(expected = MalformedVersionException::class) fun rejectsAssetsNotAList() { ReleaseJsonParser.parse("""{"assets":{}}""") }

    @Test(expected = MalformedVersionException::class) fun rejectsArray() { ReleaseJsonParser.parse("[]") }

    @Test(expected = MalformedVersionException::class) fun rejectsHtml() { ReleaseJsonParser.parse("<!DOCTYPE html>") }

    @Test(expected = MalformedVersionException::class) fun rejectsEmpty() { ReleaseJsonParser.parse("") }

    @Test fun selectsApkAndVersionJson() {
        val sel = ReleaseAssets.select(Release("latest", null, listOf(asset(5, "1-setup.png"), asset(1, "taskboard-widget.apk", 700), asset(2, "version.json"))))
        assertEquals(1L, sel.apk!!.id)
        assertEquals(2L, sel.versionJson!!.id)
    }

    @Test fun exactNamePreferredOverCaseInsensitive() {
        val sel = ReleaseAssets.select(Release("latest", null, listOf(asset(1, "Taskboard-Widget.APK"), asset(2, "taskboard-widget.apk"))))
        assertEquals(2L, sel.apk!!.id)
        val only = ReleaseAssets.select(Release("latest", null, listOf(asset(1, "Taskboard-Widget.APK"))))
        assertEquals(1L, only.apk!!.id)
    }

    @Test fun ignoresLookAlikesAndOtherApks() {
        val sel = ReleaseAssets.select(
            Release("latest", null, listOf(asset(1, "taskboard-widget.apk.sha256"), asset(2, "app-debug.apk"), asset(3, "taskboard-widget-old.apk"), asset(4, "version.json.bak"))),
        )
        assertNull(sel.apk)
        assertNull(sel.versionJson)
    }

    @Test fun ignoresAssetsStillUploadingAndBadSizes() {
        assertNull(ReleaseAssets.select(Release("", null, listOf(asset(1, "taskboard-widget.apk", uploaded = false)))).apk)
        assertNull(ReleaseAssets.select(Release("", null, listOf(asset(1, "taskboard-widget.apk", size = 0)))).apk)
        assertNull(ReleaseAssets.select(Release("", null, listOf(asset(1, "taskboard-widget.apk", size = ReleaseAssets.MAX_APK_BYTES + 1)))).apk)
        assertEquals(1L, ReleaseAssets.select(Release("", null, listOf(asset(1, "taskboard-widget.apk", size = ReleaseAssets.MAX_APK_BYTES)))).apk!!.id)
        assertNull(ReleaseAssets.select(Release("", null, listOf(asset(2, "version.json", size = 0)))).versionJson)
        assertNull(ReleaseAssets.select(Release("", null, listOf(asset(2, "version.json", size = 64 * 1024 + 1)))).versionJson)
        // an uploading duplicate doesn't shadow a ready one
        val sel = ReleaseAssets.select(Release("", null, listOf(asset(1, "taskboard-widget.apk", uploaded = false), asset(2, "taskboard-widget.apk"))))
        assertEquals(2L, sel.apk!!.id)
    }

    @Test fun emptyRelease() {
        val sel = ReleaseAssets.select(Release("latest", null, emptyList()))
        assertNull(sel.apk); assertNull(sel.versionJson)
    }
}
