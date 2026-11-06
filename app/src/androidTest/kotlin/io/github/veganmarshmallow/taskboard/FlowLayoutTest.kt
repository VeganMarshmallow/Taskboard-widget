package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.tags.Rgb
import io.github.veganmarshmallow.taskboard.core.tags.TagPill
import io.github.veganmarshmallow.taskboard.preview.FlowLayout
import io.github.veganmarshmallow.taskboard.preview.TagPillViews
import io.github.veganmarshmallow.taskboard.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** FlowLayout's wrapping and TagPillViews' colours on a device. */
@RunWith(AndroidJUnit4::class)
class FlowLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun layout(widthPx: Int, rtl: Boolean = false, vararg childWidths: Int): FlowLayout {
        var flow: FlowLayout? = null
        instrumentation.runOnMainSync {
            flow = FlowLayout(context).apply {
                horizontalSpacing = 10
                verticalSpacing = 5
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                childWidths.forEach { w -> addView(Box(w, 20)) }
                measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                layout(0, 0, measuredWidth, measuredHeight)
            }
        }
        return flow!!
    }

    private fun FlowLayout.child(i: Int) = getChildAt(i)

    /** A child that wants exactly [w] x [h] px (capped by the parent's AT_MOST width). */
    private inner class Box(private val w: Int, private val h: Int) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    @Test fun wrapsWhenARowIsFull() {
        val f = layout(100, false, 40, 40, 40)
        assertEquals(0, f.child(0).left); assertEquals(50, f.child(1).left)
        assertEquals(0, f.child(2).left); assertEquals(25, f.child(2).top) // 20 + 5 spacing
        assertEquals(45, f.measuredHeight)
    }

    @Test fun exactFitStaysOnOneRow() {
        val f = layout(90, false, 40, 40) // 40 + 10 + 40 = 90
        assertEquals(0, f.child(1).top)
        assertEquals(20, f.measuredHeight)
    }

    @Test fun childWiderThanTheRowGetsItsOwnCappedRow() {
        val f = layout(100, false, 30, 500, 30)
        assertEquals(25, f.child(1).top)
        assertTrue(f.child(1).width <= 100)
        assertEquals(50, f.child(2).top)
    }

    @Test fun emptyAndGoneChildrenTakeNoSpace() {
        assertEquals(0, layout(100, false).measuredHeight)
        val f = layout(100, false, 40, 40, 40)
        instrumentation.runOnMainSync {
            f.child(1).visibility = View.GONE
            f.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            f.layout(0, 0, f.measuredWidth, f.measuredHeight)
        }
        assertEquals(50, f.child(2).left)
        assertEquals(20, f.measuredHeight)
    }

    @Test fun rightToLeftStartsAtTheRight() {
        val f = layout(100, true, 40, 40, 40)
        assertEquals(60, f.child(0).left); assertEquals(10, f.child(1).left)
        assertEquals(60, f.child(2).left); assertEquals(25, f.child(2).top)
    }

    @Test fun manyChildren() {
        val f = layout(300, false, *IntArray(200) { 50 })
        assertEquals(200, f.childCount)
        assertTrue(f.measuredHeight > 20 * 30)
    }

    /** #46: pills are neutral, in the theme's card and text colours, whatever the GitHub label colour. */
    @Test fun pillViewsUseTheThemesNeutralColours() {
        var flow: FlowLayout? = null
        val themed = AppTheme.wrap(context, R.style.Theme_Taskboard)
        instrumentation.runOnMainSync {
            flow = FlowLayout(themed)
            TagPillViews.bind(flow!!, listOf(TagPill("Approved", Rgb(0x1D76DB), TagPill.Kind.APPROVED), TagPill("Feature", Rgb(0xA2EEEF), TagPill.Kind.TYPE)))
        }
        val f = flow!!
        assertEquals(View.VISIBLE, f.visibility)
        val card = AppTheme.color(themed, R.attr.tbCard)
        val text = AppTheme.color(themed, R.attr.tbText)
        (0 until 2).map { f.child(it) as TextView }.forEach {
            assertEquals(text, it.currentTextColor)
            assertEquals(card, it.backgroundTintList!!.defaultColor)
        }
        instrumentation.runOnMainSync { TagPillViews.bind(f, emptyList()) }
        assertEquals(View.GONE, f.visibility)
        assertEquals(0, f.childCount)
    }
}
