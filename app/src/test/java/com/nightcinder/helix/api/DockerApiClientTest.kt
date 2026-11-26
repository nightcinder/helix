package com.nightcinder.helix.api

import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.TermixApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DockerApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DockerApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        client = DockerApiClient(
            client = okHttpClient,
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connectAndKeepaliveSsh_sendPostWithMutatingHeaders() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"status":"success","connectionLogs":[]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"alive":true}""")
        )

        val session = client.connectSsh(hostId = 1)
        val alive = client.keepaliveSsh(session.sessionId)

        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/plugin-api/docker/ssh/connect", req1.path)
        assertEquals("true", req1.getHeader("X-Electron-App"))
        val body1 = req1.body.readUtf8()
        assertTrue(body1.contains(""""hostId":1"""))
        // Termix docker v1.3 requires the client to choose the session id.
        assertTrue(body1.contains(""""sessionId":"${session.sessionId}""""))
        assertTrue(session.sessionId.isNotBlank())

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/plugin-api/docker/ssh/keepalive", req2.path)
        assertEquals("true", req2.getHeader("X-Electron-App"))
        assertTrue(req2.body.readUtf8().contains(""""sessionId":"${session.sessionId}""""))
        assertEquals(1L, session.hostId)
        assertTrue(alive)
    }

    @Test
    fun listContainers_and_containerAction_and_getLogs() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"id":"c1","name":"/authentik-server","image":"authentik:latest","state":"running","status":"Up 2 days","stack":"authentik"}]""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"message":"Container restarted"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"containerId":"c1","logs":"2026-11-21 INFO Ready\n2026-11-21 INFO Listening"}""")
        )

        val containers = client.listContainers("dock_sess_1")
        assertEquals(1, containers.size)
        assertEquals("authentik-server", containers[0].displayName)
        assertTrue(containers[0].isRunning)
        assertEquals("authentik", containers[0].stackName)

        val actionRes = client.containerAction("dock_sess_1", "c1", DockerContainerAction.RESTART)
        assertTrue(actionRes.success)
        assertEquals("restart", actionRes.action)
        assertEquals("c1", actionRes.targetId)

        val logs = client.getContainerLogs("dock_sess_1", "c1", tail = 100)
        assertTrue(logs.combinedText.contains("INFO Ready"))

        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("/plugin-api/docker/containers/dock_sess_1", req1.path)
        assertNull(req1.getHeader("X-Electron-App"))

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/plugin-api/docker/containers/dock_sess_1/c1/restart", req2.path)
        assertEquals("true", req2.getHeader("X-Electron-App"))

        val req3 = server.takeRequest()
        assertEquals("GET", req3.method)
        assertEquals("/plugin-api/docker/containers/dock_sess_1/c1/logs?tail=100", req3.path)
    }

    @Test
    fun stacks_listComposeUpdateAndAction() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"stacks":[{"name":"media","status":"running","serviceCount":3,"runningCount":3}]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"stackName":"media","content":"services:\n  jellyfin:\n    image: jellyfin/jellyfin"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"stackName":"media","content":"services:\n  jellyfin:\n    image: jellyfin/jellyfin:latest"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"action":"deploy"}""")
        )

        val stacks = client.listStacks("dock_sess_1")
        assertEquals(1, stacks.size)
        assertEquals("media", stacks[0].name)

        val compose = client.getStackCompose("dock_sess_1", "media")
        assertTrue(compose.content.contains("jellyfin"))

        val updated = client.updateStackCompose(
            sessionId = "dock_sess_1",
            stackName = "media",
            content = "services:\n  jellyfin:\n    image: jellyfin/jellyfin:latest",
        )
        assertTrue(updated.content.contains(":latest"))

        val stackAct = client.stackAction("dock_sess_1", "media", DockerStackAction.UP)
        assertTrue(stackAct.success)
        assertEquals("deploy", stackAct.action)

        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("/plugin-api/docker/stacks/dock_sess_1", req1.path)

        val req2 = server.takeRequest()
        assertEquals("GET", req2.method)
        assertEquals("/plugin-api/docker/stacks/dock_sess_1/media", req2.path)

        val req3 = server.takeRequest()
        assertEquals("PUT", req3.method)
        assertEquals("/plugin-api/docker/stacks/dock_sess_1/media/compose", req3.path)
        assertEquals("true", req3.getHeader("X-Electron-App"))

        val req4 = server.takeRequest()
        assertEquals("POST", req4.method)
        assertEquals("/plugin-api/docker/stacks/dock_sess_1/media/action", req4.path)
        assertEquals("true", req4.getHeader("X-Electron-App"))
        assertTrue(req4.body.readUtf8().contains(""""action":"deploy""""))
    }

    @Test
    fun dataExpired_mapsToTermixApiExceptionDataExpired() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"DATA_EXPIRED","message":"Session DEK expired"}""")
        )

        try {
            client.listContainers("dock_sess_1")
            fail("Expected TermixApiException.DataExpired")
        } catch (e: TermixApiException.DataExpired) {
            assertEquals(401, e.httpStatus)
            assertEquals("DATA_EXPIRED", e.code)
        }
    }
}
