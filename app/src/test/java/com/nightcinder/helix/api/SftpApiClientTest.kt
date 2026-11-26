package com.nightcinder.helix.api

import com.nightcinder.helix.core.model.TermixApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SftpApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SftpApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        client = SftpApiClient(
            client = okHttpClient,
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connect_and_listFiles_and_readFile_and_writeFile() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":1,"name":"core","ip":"192.0.2.10","port":22,"username":"core"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"success","connectionLogs":[]}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"name":"docker-compose.yml","path":"/srv/docker-compose.yml","isDirectory":false,"sizeBytes":512}]""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"path":"/srv/docker-compose.yml","content":"services:\n  web:\n    image: nginx"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"targetPath":"/srv/docker-compose.yml","bytesTransferred":38}""")
        )

        val session = client.connect(hostId = 1)
        assertTrue(session.sessionId.isNotBlank())
        assertEquals(1L, session.hostId)

        val entries = client.listFiles("sftp_99", "/srv")
        assertEquals(1, entries.size)
        assertEquals("docker-compose.yml", entries[0].name)

        val content = client.readFile("sftp_99", "/srv/docker-compose.yml")
        assertTrue(content.content.contains("nginx"))

        val writeRes = client.writeFile(
            sessionId = "sftp_99",
            path = "/srv/docker-compose.yml",
            content = "services:\n  web:\n    image: nginx:alpine",
        )
        assertTrue(writeRes.success)

        val req0 = server.takeRequest()
        assertEquals("/host/db/host/1", req0.path)

        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/plugin-api/file-manager/connect", req1.path)
        assertEquals("true", req1.getHeader("X-Electron-App"))
        val connectBody = req1.body.readUtf8()
        // file-manager v1.2 needs the client-chosen session id plus ip/port/username.
        assertTrue(connectBody.contains(""""sessionId":"${session.sessionId}""""))
        assertTrue(connectBody.contains(""""ip":"192.0.2.10""""))
        assertTrue(connectBody.contains(""""username":"core""""))

        val req2 = server.takeRequest()
        assertEquals("GET", req2.method)
        assertTrue(req2.path!!.startsWith("/plugin-api/file-manager/listFiles"))
        assertTrue(req2.path!!.contains("sessionId=sftp_99"))
        assertNull(req2.getHeader("X-Electron-App"))

        val req3 = server.takeRequest()
        assertEquals("GET", req3.method)
        assertTrue(req3.path!!.startsWith("/plugin-api/file-manager/readFile"))
        assertTrue(req3.path!!.contains("sessionId=sftp_99"))

        val req4 = server.takeRequest()
        assertEquals("POST", req4.method)
        assertEquals("/plugin-api/file-manager/writeFile", req4.path)
        assertEquals("true", req4.getHeader("X-Electron-App"))
        assertTrue(req4.body.readUtf8().contains("nginx:alpine"))
    }

    @Test
    fun downloadUploadAndTransferToHost_useVerifiedEndpoints() = runTest {
        val rawPayload = "binary-payload-123".encodeToByteArray()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("binary-payload-123")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"targetPath":"/tmp/out.bin","bytesTransferred":18}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"sourcePath":"/tmp/out.bin","targetPath":"/backup/out.bin","targetHostId":2}""")
        )

        val downloaded = client.downloadFile("sftp_99", "/tmp/in.bin")
        assertArrayEquals(rawPayload, downloaded)

        val uploaded = client.uploadFile("sftp_99", "/tmp/out.bin", rawPayload)
        assertTrue(uploaded.success)
        assertEquals(18L, uploaded.bytesTransferred)

        val transferred = client.transferToHost(
            sourceSessionId = "sftp_99",
            sourcePath = "/tmp/out.bin",
            targetHostId = 2L,
            targetPath = "/backup/out.bin",
        )
        assertTrue(transferred.success)
        assertEquals(2L, transferred.targetHostId)

        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/plugin-api/file-manager/downloadFileStream", req1.path)
        assertEquals("true", req1.getHeader("X-Electron-App"))

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/plugin-api/file-manager/uploadFileStream", req2.path)
        assertEquals("true", req2.getHeader("X-Electron-App"))

        val req3 = server.takeRequest()
        assertEquals("POST", req3.method)
        assertEquals("/plugin-api/file-manager/transferToHost", req3.path)
        assertEquals("true", req3.getHeader("X-Electron-App"))
        assertTrue(req3.body.readUtf8().contains(""""targetHostId":2"""))
    }

    @Test
    fun listFiles_readsTermixTypeField() = runTest {
        // Shape sent by the live Termix file-manager plugin (v1.x): `type` string, no isDirectory flag.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"files":[
                      {"name":"var","type":"directory","modified":"Nov 23 05:10","modifiedTimestamp":1795428600,"permissions":"rwxr-xr-x","owner":"0","group":"0","path":"/var","executable":false},
                      {"name":"bin","type":"link","linkTarget":"usr/bin","modified":"Apr 1 2026","permissions":"rwxrwxrwx","owner":"0","group":"0","path":"/bin","executable":false},
                      {"name":"swap.img","type":"file","size":4294967296,"modified":"Nov 1 2026","permissions":"rw-------","owner":"0","group":"0","path":"/swap.img","executable":false}
                    ],"path":"/"}""",
                ),
        )

        val entries = client.listFiles("sftp_99", "/")

        assertEquals(listOf("var", "bin", "swap.img"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isSymlink)
        assertEquals("usr/bin", entries[1].linkTarget)
        assertTrue(!entries[2].isDirectory && !entries[2].isSymlink)
        assertEquals(4294967296L, entries[2].sizeBytes)
    }

    @Test
    fun dataExpired_throwsStructuredException() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"DATA_EXPIRED"}""")
        )

        try {
            client.listFiles("sftp_99", "/")
            fail("Expected TermixApiException.DataExpired")
        } catch (e: TermixApiException.DataExpired) {
            assertEquals(401, e.httpStatus)
            assertEquals("DATA_EXPIRED", e.code)
        }
    }
}
