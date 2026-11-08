package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import java.util.Base64

/**
 * The board repo's `.board/settings.json` (#83) on a [FakeGitHubServer]: [text] null means no file.
 * GETs answer the Contents API's shape; a PUT stores its content unless [putStatus] says otherwise.
 * Everything else answers 404 (the settings screen's update check finds nothing).
 */
class FakeBoardSettings(@Volatile var text: String? = null) : AutoCloseable {
    @Volatile var sha = "sha-1"
    @Volatile var getStatus = 200
    @Volatile var putStatus = 200
    private var writes = 0
    val server = FakeGitHubServer { method, path -> answer(method, path) }

    /** The raw JSON body of every PUT to the file (accepted or refused), in order. */
    val puts: List<String> get() = server.received.filter { it.method == "PUT" && it.path == PATH }.map { it.body }

    fun install() = apply {
        TestOverrides.httpClient = server::client
        TokenStore(InstrumentationRegistry.getInstrumentation().targetContext).save("github_pat_TESTONLY")
    }

    private fun answer(method: String, path: String): FakeGitHubServer.Answer? = when {
        path != PATH -> null
        method == "GET" && getStatus != 200 -> FakeGitHubServer.Answer(getStatus, """{"message":"Server Error"}""")
        method == "GET" -> text?.let { FakeGitHubServer.Answer(200, fileJson(it, sha)) }
        method == "PUT" && putStatus != 200 -> FakeGitHubServer.Answer(putStatus, """{"message":"Resource not accessible by personal access token"}""")
        method == "PUT" -> {
            text = decodeContent(server.received.last { it.method == "PUT" }.body)
            sha = "sha-${++writes + 1}"
            FakeGitHubServer.Answer(200, """{"content":{"sha":"$sha"}}""")
        }
        else -> null
    }

    override fun close() {
        server.close()
        TestOverrides.httpClient = null
    }

    companion object {
        const val PATH = "/repos/VeganMarshmallow/claude-workspace/contents/.board/settings.json"

        fun fileJson(text: String, sha: String): String {
            val b64 = Base64.getMimeEncoder().encodeToString(text.toByteArray()).replace("\r\n", "\\n")
            return """{"type":"file","encoding":"base64","sha":"$sha","path":".board/settings.json","content":"$b64"}"""
        }

        /** The file text a PUT body carries. */
        fun decodeContent(body: String): String {
            val b64 = Regex("\"content\":\"([^\"]*)\"").find(body)!!.groupValues[1]
            return String(Base64.getDecoder().decode(b64))
        }

        /** Opens settings and waits until the Work pace section's form matches [until]. */
        fun openSettings(until: (WorkPaceForm) -> Boolean): SetupActivity {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val intent = Intent(instrumentation.targetContext, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent) as SetupActivity
            awaitForm(activity, until)
            return activity
        }

        /** Waits (up to 10 s) until [activity]'s Work pace form matches [until]; fails with the last form otherwise. */
        fun awaitForm(activity: SetupActivity, until: (WorkPaceForm) -> Boolean): WorkPaceForm {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val deadline = System.currentTimeMillis() + 10_000
            var form: WorkPaceForm? = null
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { form = activity.workPaceForTest.shown }
                if (until(form!!)) return form!!
                Thread.sleep(50)
            }
            throw AssertionError("Work pace form never matched; last: $form")
        }

        fun finish(activity: Activity) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
