package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewJsonParser
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import io.github.veganmarshmallow.taskboard.core.tags.ProjectColor
import io.github.veganmarshmallow.taskboard.core.tags.RepoField
import io.github.veganmarshmallow.taskboard.core.tags.Rgb
import io.github.veganmarshmallow.taskboard.core.tags.TagPill.Kind
import io.github.veganmarshmallow.taskboard.core.tags.TagPills
import io.github.veganmarshmallow.taskboard.core.tags.TextTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPillsTest {
    private val formBody = "### What should be done\nX\n\n### Repo\n\nVeganMarshmallow/Taskboard-widget\n\n---\nfooter"

    // --- repo field ---

    @Test fun repoFromTheFormSection() {
        assertEquals("Taskboard-widget", RepoField.name(formBody))
    }

    @Test fun repoFromALine() {
        assertEquals("site", RepoField.name("Do the thing.\n\nRepo: VeganMarshmallow/site"))
        assertEquals("site", RepoField.name("**Repo:** `VeganMarshmallow/site`"))
        assertEquals("site", RepoField.name("- repository: VeganMarshmallow/site"))
        assertEquals("site", RepoField.name("REPO:VeganMarshmallow/site"))
    }

    @Test fun repoInUrlsLinksAndCode() {
        listOf(
            "https://github.com/VeganMarshmallow/site",
            "https://github.com/VeganMarshmallow/site/",
            "https://github.com/VeganMarshmallow/site.git",
            "github.com/VeganMarshmallow/site/issues/3",
            "[VeganMarshmallow/site](https://github.com/VeganMarshmallow/site)",
            "`VeganMarshmallow/site`",
            "<https://github.com/VeganMarshmallow/site>",
            "VeganMarshmallow/site.",
            "https://github.com/VeganMarshmallow/site#readme",
        ).forEach { assertEquals(it, "site", RepoField.name("### Repo\n$it")) }
    }

    @Test fun repoNamesWithDotsUnderscoresDigits() {
        assertEquals("my.site_v2", RepoField.name("### Repo\nowner-1/my.site_v2"))
        assertEquals("x", RepoField.name("### Repo\na/x"))
    }

    @Test fun sectionWithTrailingWords() {
        assertEquals("site", RepoField.name("### Repo\nVeganMarshmallow/site (the new one)"))
    }

    @Test fun sectionWinsOverALine() {
        assertEquals("from-section", RepoField.name("Repo: o/from-line\n\n### Repo\no/from-section"))
    }

    @Test fun badSectionFallsBackToALine() {
        assertEquals("from-line", RepoField.name("### Repo\n_No response_\n\nRepo: o/from-line"))
    }

    @Test fun noRepo() {
        listOf(
            null, "", "   ", "Just a description", "### Repo", "### Repo\n_No response_", "### Repo\nTaskboard-widget",
            "### Repo\nnot a repo at all", "### Repo\n../etc", "### Repo\no/..", "### Repo\n-bad/owner", "Repo: ",
            "### Repo\nhttps://gitlab.com/o/x/y/z/w", "### Repo\n```\no/x\n```", "### Repository name\nfoo",
            "### Repo\no/" + "x".repeat(101),
        ).forEach { assertNull("$it", RepoField.name(it)) }
    }

    @Test fun hebrewAndHugeBodies() {
        assertEquals("site", RepoField.name("### מה צריך לעשות\nלבנות אתר\n\n### Repo\nVeganMarshmallow/site"))
        assertNull(RepoField.name("### Repo\nבעלים/אתר"))
        assertNull(RepoField.name("a ".repeat(100_000) + "\nRepo: o/x")) // only the first 64 KB are scanned
        assertEquals("big", RepoField.name("### Repo\no/big\n" + "filler ".repeat(500_000)))
    }

    // --- pills ---

    private val boardColors = mapOf(
        "status: todo" to "d4d4d4", "type: feature" to "a2eeef", "priority: high" to "b60205",
        "ready-for-claude" to "1d76db", "needs-owner" to "b60205", "created-by-claude" to "c5def5",
    )

    @Test fun allPillsInOrderWithGitHubColoursAndNoStatus() {
        val pills = TagPills.of(boardColors, formBody)
        assertEquals(listOf("Taskboard-widget", "Feature", "High priority", "Approved", "Needs you", "By Claude"), pills.map { it.text })
        assertEquals(listOf(Kind.PROJECT, Kind.TYPE, Kind.PRIORITY, Kind.APPROVED, Kind.NEEDS_YOU, Kind.BY_CLAUDE), pills.map { it.kind })
        assertEquals(ProjectColor.of("Taskboard-widget"), pills[0].background)
        assertEquals(Rgb(0xA2EEEF), pills[1].background)
        assertEquals(Rgb(0x1D76DB), pills[3].background)
        assertEquals(TextTone.DARK, pills[1].textTone)
        assertEquals(TextTone.LIGHT, pills[2].textTone)
    }

    @Test fun everyStatusLabelIsHidden() {
        val all = listOf("status: todo", "status: in-progress", "status: blocked", "status: in-review", "status: done", "STATUS: Todo", "status:weird")
        assertTrue(TagPills.of(all.associateWith { "ffffff" }, null).isEmpty())
    }

    @Test fun unknownLabelsAreHidden() {
        assertTrue(TagPills.of(mapOf("bug" to "d73a4a", "enhancement" to "a2eeef", "wontfix" to "ffffff", "" to "000000"), null).isEmpty())
    }

    @Test fun typeLabelsAreTitleCasedAndSorted() {
        val pills = TagPills.of(mapOf("type: research" to "fef2c0", "type: bug" to "ee0701", "type:" to "000000", "type:   " to "000000"), null)
        assertEquals(listOf("Bug", "Research"), pills.map { it.text })
    }

    @Test fun lowPriority() {
        assertEquals(listOf("Low priority"), TagPills.of(mapOf("priority: low" to "c2e0c6"), null).map { it.text })
    }

    @Test fun missingOrBadColourFallsBackToGrey() {
        val pills = TagPills.of(mapOf("type: feature" to null, "ready-for-claude" to "nope"), null)
        assertTrue(pills.all { it.background == TagPills.FALLBACK_COLOR && it.textTone == TextTone.DARK })
    }

    @Test fun labelNamesAreNormalizedAndDeduplicated() {
        val pills = TagPills.of(mapOf("  Ready-For-Claude " to "1d76db", "ready-for-claude" to "000000", "TYPE: Feature" to "a2eeef"), null)
        assertEquals(listOf("Feature", "Approved"), pills.map { it.text })
        assertEquals(Rgb(0x1D76DB), pills[1].background)
    }

    @Test fun nothingAtAll() {
        assertTrue(TagPills.of(emptyMap(), null).isEmpty())
        assertTrue(TagPills.of(emptyMap(), "").isEmpty())
    }

    @Test fun projectOnly() {
        assertEquals(listOf(Kind.PROJECT), TagPills.of(emptyMap(), "Repo: o/x").map { it.kind })
    }

    @Test fun longTextIsClipped() {
        val long = "type: " + "very-long-type-".repeat(10)
        val pill = TagPills.of(mapOf(long to "a2eeef"), "Repo: o/" + "n".repeat(100)).map { it.text }
        assertTrue(pill.all { it.length <= TagPills.MAX_TEXT })
        assertTrue(pill.all { it.endsWith("…") })
    }

    @Test fun manyLabels() {
        val colors = (1..500).associate { "type: t$it" to "%06x".format(it * 9973 % 0xFFFFFF) }
        val pills = TagPills.of(colors, null)
        assertEquals(500, pills.size)
        assertEquals("T1", pills.first().text)
    }

    // --- wired into the preview ---

    @Test fun previewFromParsedIssueJsonHasPills() {
        val json = JsonWriterHelper.issue(
            11,
            body = formBody,
            labels = listOf("status: todo" to "d4d4d4", "type: feature" to "a2eeef", "created-by-claude" to "c5def5"),
        )
        val preview = TaskPreview.fromIssue(PreviewJsonParser.parseIssue(json))
        assertEquals(listOf("Taskboard-widget", "Feature", "By Claude"), preview.tags.map { it.text })
        assertEquals(Rgb(0xC5DEF5), preview.tags[2].background)
    }

    @Test fun labelsWithoutColourDataStillShowAsGreyPills() {
        val preview = TaskPreview.fromIssue(IssueDetails(issue(3, "type: bug", "status: todo"), ""))
        assertEquals(listOf("Bug"), preview.tags.map { it.text })
        assertEquals(TagPills.FALLBACK_COLOR, preview.tags[0].background)
    }

    @Test fun parserReadsLabelColoursTolerantly() {
        val details = PreviewJsonParser.parseIssue(
            """{"number":4,"state":"open","title":"t","labels":[{"name":"type: bug","color":"ee0701"},"needs-owner",{"name":"Type: Bug","color":"000000"},{"color":"fff"},5,null,{"name":"ready-for-claude","color":7}]}""",
        )
        assertEquals(mapOf("type: bug" to "ee0701", "needs-owner" to null, "ready-for-claude" to null), details.labelColors)
    }

    @Test fun cachedBoardPreviewHasNoPillsUntilLoaded() {
        val board = io.github.veganmarshmallow.taskboard.core.board.BoardBuilder().build(listOf(issue(2, "status: todo", "type: bug")), NOW)
        assertTrue(TaskPreview.fromBoard(board, 2)!!.tags.isEmpty())
    }
}

/** Builds single-issue JSON with label colours. */
private object JsonWriterHelper {
    fun issue(n: Int, body: String, labels: List<Pair<String, String>>): String =
        io.github.veganmarshmallow.taskboard.core.json.JsonWriter.write(
            mapOf(
                "number" to n.toLong(), "title" to "T", "state" to "open", "body" to body,
                "labels" to labels.map { (name, color) -> mapOf("name" to name, "color" to color) },
            ),
        )
}
