package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The widget-configure protocol of the setup screen (the "keep widget on placement" fix, b4afec0). */
@RunWith(AndroidJUnit4::class)
class SetupActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val widgetId = 4242

    @Before fun noToken() = TokenStore(context).clear()

    @After fun cleanUp() = TokenStore(context).clear()

    private fun configureIntent() = Intent(context, SetupActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    @Test fun configuringWithoutTokenStillReturnsOk() {
        ActivityScenario.launchActivityForResult<SetupActivity>(configureIntent()).use { scenario ->
            scenario.onActivity { it.finish() }  // the user backs out without setting a token
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(widgetId, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        }
    }

    @Test fun saveWhileConfiguringReturnsOkAndFinishes() {
        ActivityScenario.launchActivityForResult<SetupActivity>(configureIntent()).use { scenario ->
            scenario.onActivity {
                it.findViewById<EditText>(R.id.token).setText("github_pat_TESTONLY")
                it.findViewById<Button>(R.id.save).performClick()
            }
            val result = scenario.result  // waits for the screen to finish on its own
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(widgetId, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            assertEquals("github_pat_TESTONLY", TokenStore(context).get())
        }
    }

    /** #46 round 2: the folded "Setup steps" stay open across a rotation (the activity is recreated). */
    @Test fun openSetupStepsSurviveRecreation() {
        ActivityScenario.launch<SetupActivity>(Intent(context, SetupActivity::class.java)).use { scenario ->
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<View>(R.id.token_help).visibility)
                it.findViewById<Button>(R.id.token_help_toggle).performClick()
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.token_help).visibility)
                assertEquals(it.getString(R.string.token_help_hide), it.findViewById<Button>(R.id.token_help_toggle).text.toString())
            }
        }
    }

    @Test fun launcherDoesNotSetAResult() {
        ActivityScenario.launchActivityForResult<SetupActivity>(Intent(context, SetupActivity::class.java)).use { scenario ->
            scenario.onActivity { it.finish() }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }
}
