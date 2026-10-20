package app.aaps.core.nssdk.networking

import app.aaps.core.nssdk.NSAndroidClientImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Nightscout access token must never reach the log.
 *
 * The token refresh puts it into the URL path, and the request logger writes every URL. Asserted through a real
 * localhost server with logging switched on, so the test sees exactly the lines the app would write.
 */
class NsSdkLogRedactionTest {

    private lateinit var server: MockWebServer
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the access token in the refresh URL is replaced`() {
        assertThat(NsLogRedaction.url("https://ns.example/api/v2/authorization/request/abc-123"))
            .isEqualTo("https://ns.example/api/v2/authorization/request/***")
        assertThat(NsLogRedaction.url("https://ns.example/api/v2/authorization/request/abc%2F123?x=1"))
            .isEqualTo("https://ns.example/api/v2/authorization/request/***?x=1")
    }

    @Test
    fun `other URLs stay unchanged`() {
        val url = "https://ns.example/api/v3/entries?sort\$desc=date&limit=10"
        assertThat(NsLogRedaction.url(url)).isEqualTo(url)
    }

    @Test
    fun `a refresh writes no access token into the log`() = runTest {
        val token = "secret-token-4711"
        val dataCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.contains("/authorization/request/"))
                    MockResponse.Builder().code(200).body("""{"token":"NEW_JWT","iat":1,"exp":2}""").build()
                else if (dataCalls.incrementAndGet() == 1) MockResponse.Builder().code(401).body("unauthorized").build()
                else MockResponse.Builder().code(200).body("""{"result":[]}""").build()
        }
        val client = NSAndroidClientImpl(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = token,
            logging = true,
            logger = { lines.add(it) },
            dispatcher = Dispatchers.Unconfined
        )

        client.getSgvs()

        // The refresh really ran and was logged, so the check below is not empty.
        assertThat(lines.filter { it.contains("/authorization/request/***") }).isNotEmpty()
        assertThat(lines.filter { it.contains(token) }).isEmpty()
    }
}
