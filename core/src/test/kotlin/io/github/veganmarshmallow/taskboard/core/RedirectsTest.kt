package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Redirects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectsTest {
    private val first = HttpRequest(
        "GET", assetUrl(APK_ID),
        mapOf("Authorization" to "Bearer $TOKEN", "Accept" to "application/octet-stream", "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to "taskboard-widget"),
    )

    @Test fun redirectStatuses() {
        listOf(301, 302, 303, 307, 308).forEach { assertTrue(Redirects.isRedirect(it)) }
        listOf(200, 204, 300, 304, 305, 306, 309, 404).forEach { assertFalse("$it", Redirects.isRedirect(it)) }
    }

    @Test fun storageHostNeverGetsTheToken() {
        val next = Redirects.next(first, STORAGE)!!
        assertEquals(STORAGE, next.url)
        assertEquals("GET", next.method)
        assertEquals(setOf("Accept", "User-Agent"), next.headers.keys)
        assertFalse(next.headers.values.any { TOKEN in it })
    }

    @Test fun headerNameCaseDoesNotLeakTheToken() {
        val lower = first.copy(headers = mapOf("authorization" to "Bearer $TOKEN", "AUTHORIZATION" to "token $TOKEN", "accept" to "x"))
        val next = Redirects.next(lower, STORAGE)!!
        assertEquals(setOf("accept"), next.headers.keys)
    }

    @Test fun apiHostKeepsTheToken() {
        val next = Redirects.next(first, "https://api.github.com/repositories/9/releases/assets/1")!!
        assertEquals(first.headers, next.headers)
        assertEquals(first.headers, Redirects.next(first, "https://API.GITHUB.COM/x")!!.headers)
        assertEquals(first.headers, Redirects.next(first, "https://api.github.com:443/x")!!.headers)
    }

    @Test fun lookAlikeHostsDoNotGetTheToken() {
        listOf(
            "https://api.github.com.evil.example/x",
            "https://evil.example/api.github.com/x",
            "https://api.github.com:8443/x",
            "https://xapi.github.com/x",
            "https://github.com/x",
            "https://uploads.github.com/x",
        ).forEach { url ->
            val next = Redirects.next(first, url)!!
            assertFalse(url, next.headers.keys.any { it.equals("Authorization", true) })
        }
    }

    @Test fun refusesUnsafeTargets() {
        listOf(
            null, "", "   ",
            "http://objects.githubusercontent.com/x", // downgrade
            "ftp://x.example/a",
            "javascript:alert(1)",
            "file:///data/data/x",
            "https://user:pass@objects.githubusercontent.com/x",
            "https://api.github.com@evil.example/x",
            "https:///nohost",
            "https://exa mple.com/",
            "https://[bad/",
        ).forEach { assertNull("$it", Redirects.next(first, it)) }
    }

    @Test fun resolvesRelativeLocations() {
        assertEquals("https://api.github.com/repositories/9/releases/assets/1", Redirects.next(first, "/repositories/9/releases/assets/1")!!.url)
        assertEquals("https://objects.githubusercontent.com/a", Redirects.next(first, "//objects.githubusercontent.com/a")!!.url)
        // Protocol-relative to another host drops the token, too.
        assertFalse(Redirects.next(first, "//objects.githubusercontent.com/a")!!.headers.containsKey("Authorization"))
    }

    @Test fun keepsSignedQueryStringsIntact() {
        val signed = "https://objects.githubusercontent.com/p/1?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=ab%2Fcd&response-content-disposition=attachment%3B%20filename%3Dtaskboard-widget.apk"
        assertEquals(signed, Redirects.next(first, signed)!!.url)
    }

    @Test fun onceDroppedTheTokenNeverComesBack() {
        val storage = Redirects.next(first, STORAGE)!!
        val back = Redirects.next(storage, "https://api.github.com/x")!!
        assertFalse(back.headers.containsKey("Authorization"))
    }

    @Test fun isApiUrl() {
        assertTrue(Redirects.isApiUrl("https://api.github.com/x"))
        assertTrue(Redirects.isApiUrl("https://Api.GitHub.com/x"))
        assertFalse(Redirects.isApiUrl("http://api.github.com/x"))
        assertFalse(Redirects.isApiUrl("https://api.github.com.evil/x"))
        assertFalse(Redirects.isApiUrl("https://a@api.github.com/x"))
        assertFalse(Redirects.isApiUrl("not a url"))
        assertFalse(Redirects.isApiUrl(""))
    }
}
