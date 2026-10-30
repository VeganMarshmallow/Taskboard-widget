package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.merge.MergeDialogView
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots for task #30 (merge from the widget). Each also checks what the picture claims. */
@RunWith(AndroidJUnit4::class)
class MergeScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dialogs get() = MergeDialogs(AppGraph.messages(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    /** The real dialog layout, bound like MergeFlow does, centred over a dimmed home screen. */
    private fun captureDialog(name: String, dialog: MergeDialog, night: Boolean = false, heightDp: Int = 420): View {
        lateinit var root: View
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(configured(night), R.style.Theme_Taskboard_MergeDialog)
            val density = themed.resources.displayMetrics.density
            val frame = FrameLayout(themed).apply { setBackgroundColor(DIMMED) }
            root = LayoutInflater.from(themed).inflate(R.layout.dialog_merge, frame, false)
            MergeDialogView(root) {}.bind(dialog)
            frame.addView(root, FrameLayout.LayoutParams((340 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val w = (380 * density).toInt()
            val h = (heightDp * density).toInt()
            frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, w, h)
            Screenshots.save(context, name, WidgetHost.toBitmap(frame))
        }
        return root
    }

    private fun configured(night: Boolean): Context {
        if (!night) return context
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        return context.createConfigurationContext(config)
    }

    private fun lines(root: View): List<String> {
        val box = root.findViewById<LinearLayout>(R.id.merge_lines)
        return (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() }
    }

    @Test fun previewWithMergeButton() {
        val body = "### What should be done\nLet the owner merge Claude's PRs from the widget, without opening GitHub.\n\n### Repo\nVeganMarshmallow/Taskboard-widget"
        val names = setOf(Labels.IN_REVIEW, "type: feature")
        val details = IssueDetails(Issue(30, FakeMerges.TITLE, names, true), body, names.associateWith { FakePreviews.LABEL_COLORS[it] ?: "7c3aed" })
        val screen = PreviewScreen.initial(30, null).issueLoaded(details)
            .commentsLoaded(listOf(io.github.veganmarshmallow.taskboard.core.preview.IssueComment(1L, "✅ PR ready: https://github.com/VeganMarshmallow/Taskboard-widget/pull/12", null)))
        lateinit var sheet: View
        instrumentation.runOnMainSync {
            val rendered = PreviewHost(context).render(screen, heightDp = 440)
            sheet = rendered.sheet
            Screenshots.save(context, "30-1-preview-merge-button", WidgetHost.toBitmap(rendered.root))
        }
        assertTrue(sheet.visible(R.id.preview_merge))
        assertTrue(!sheet.visible(R.id.preview_approve))
        assertEquals("In review", sheet.text(R.id.preview_status))
        assertEquals(context.getString(R.string.merge), sheet.text(R.id.preview_merge))
    }

    @Test fun confirmationDialog() {
        val root = captureDialog("30-2-merge-confirmation", dialogs.confirm(FakeMerges.pr()))
        assertEquals("Merge PR #12?", root.text(R.id.merge_title))
        assertEquals(FakeMerges.TITLE, lines(root)[0])
        assertEquals("VeganMarshmallow/Taskboard-widget #12", lines(root)[1])
        assertEquals(context.getString(R.string.merge), root.text(R.id.merge_primary))
        assertEquals(context.getString(R.string.merge_cancel), root.text(R.id.merge_secondary))
        assertTrue(!root.visible(R.id.merge_progress))
    }

    @Test fun stackedRefusal() {
        val problem = MergeProblem.Stacked(FakeMerges.pr(base = "claude/29-network-recovery"), blocker = 11)
        val root = captureDialog("30-3-stacked-refusal", dialogs.refusal(problem, 30))
        assertEquals("Merge another PR first", root.text(R.id.merge_title))
        assertEquals("This PR is stacked on claude/29-network-recovery; merge #11 first.", lines(root)[0])
        assertEquals(context.getString(R.string.merge_open_github), root.text(R.id.merge_primary))
    }

    @Test fun widgetRowsWithMergeButtons() {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(FakeMerges.boardState(), 340, 300, Section.NEEDS_YOU)
            widget = rendered.widget
            Screenshots.save(context, "30-4-rows-merge-button", WidgetHost.toBitmap(rendered.root))
        }
        val list = widget.findViewById<LinearLayout>(R.id.board_list)
        val merges = (0 until list.childCount).map { list.getChildAt(it) }.count { row ->
            row.findViewById<View>(R.id.item_merge)?.visibility == View.VISIBLE
        }
        assertEquals("both in-review rows offer Merge, the question doesn't", 2, merges)
    }

    @Test fun tokenAccessRefusal() {
        val root = captureDialog("38-1-token-access", dialogs.refusal(MergeProblem.NoRepoAccess(FakeMerges.WIDGET), 30), heightDp = 460)
        assertTrue(lines(root).any { it.contains("Pull requests: Read and write") && it.contains("Actions: Read-only") })
        assertTrue(lines(root).none { it.contains("Checks") })
        assertEquals(context.getString(R.string.merge_edit_token), root.text(R.id.merge_primary))
    }

    @Test fun actionsAccessRefusal() {
        val root = captureDialog("38-2-actions-access", dialogs.refusal(MergeProblem.NoActionsAccess(FakeMerges.WIDGET), 30), heightDp = 500)
        assertEquals("Token can't read CI", root.text(R.id.merge_title))
        assertTrue(lines(root).any { it.contains("add Actions: Read-only") })
        assertEquals(context.getString(R.string.merge_edit_token), root.text(R.id.merge_primary))
    }

    @Test fun checksRunningDark() {
        val dialog = dialogs.refusal(MergeProblem.ChecksRunning(FakeMerges.pr(), listOf("CI")), 30)
        val root = captureDialog("38-3-checks-running-dark", dialog, night = true, heightDp = 320)
        assertEquals("Checks still running", root.text(R.id.merge_title))
        assertTrue(lines(root).any { it.contains("Wait for workflow CI to finish") })
    }

    @Test fun checkingProgress() {
        val root = captureDialog("30-7-checking", dialogs.checking(30), heightDp = 300)
        assertTrue(root.visible(R.id.merge_progress))
        assertTrue(!root.visible(R.id.merge_primary))
    }

    @Test fun setupScreenExplainsMergePermissions() {
        TokenStore(context).clear()
        val activity: Activity = instrumentation.startActivitySync(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            val text = root.findViewById<TextView>(R.id.merge_instructions)
            val s = text.text.toString()
            assertTrue(s.startsWith("To merge from the widget"))
            assertTrue(s.contains("Pull requests: Read and write"))
            assertTrue(s.contains("Contents: Read and write"))
            assertTrue(s.contains("Actions: Read-only"))
            assertTrue(!s.contains("Checks") && !s.contains("Commit statuses"))
            assertTrue(s.contains("write access to those repos' code"))
            activity.findViewById<android.widget.ScrollView>(R.id.setup_scroll).scrollTo(0, text.top - 40)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { bitmap = WidgetHost.toBitmap(activity.window.decorView) }
        Screenshots.save(context, "38-4-setup-merge-permissions", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
    }

    private companion object {
        val DIMMED: Int = Color.rgb(0x2E, 0x36, 0x40)
    }
}
