package com.nightcinder.helix.monitoring

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringSourceException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GrafanaAlertsClientTest {
    init {
        TestSite.apply()
    }


    private lateinit var server: MockWebServer
    private lateinit var client: GrafanaAlertsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GrafanaAlertsClient(
            client = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchActiveAlerts_mergesAlertmanagerAndRulesWithDeduplication(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/alertmanager/grafana/api/v2/alerts") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            [
                              {
                                "fingerprint": "fp-1",
                                "startsAt": "2026-11-23T17:50:00Z",
                                "status": {"state": "active"},
                                "labels": {"alertname": "HostMemoryHigh", "severity": "critical", "instance": "docker-host"},
                                "annotations": {"summary": "Memory > 90% on core"}
                              },
                              {
                                "fingerprint": "fp-2",
                                "startsAt": "2026-11-23T17:55:00Z",
                                "status": {"state": "suppressed"},
                                "labels": {"alertname": "SilencedAlert", "severity": "warning"}
                              }
                            ]
                            """.trimIndent(),
                        )
                    path.startsWith("/api/prometheus/grafana/api/v1/rules") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "status": "success",
                              "data": {
                                "groups": [
                                  {
                                    "name": "homelab",
                                    "rules": [
                                      {
                                        "name": "HostMemoryHigh",
                                        "state": "firing",
                                        "labels": {"severity": "critical", "instance": "docker-host"},
                                        "annotations": {"summary": "Duplicate from rules"}
                                      },
                                      {
                                        "name": "esxi-poller-down",
                                        "state": "firing",
                                        "labels": {"severity": "warning", "instance": "vcenter"},
                                        "annotations": {"summary": "ESXi poller target down"},
                                        "alerts": [
                                          {
                                            "state": "firing",
                                            "activeAt": "2026-11-23T18:05:00Z",
                                            "labels": {"alertname": "esxi-poller-down", "instance": "vcenter"},
                                            "annotations": {"summary": "ESXi poller unreachable"}
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val alerts = client.fetchActiveAlerts()
        assertEquals(2, alerts.size)

        val memAlert = alerts.first { it.title == "HostMemoryHigh" }
        assertEquals(AlertSource.GRAFANA, memAlert.source)
        assertEquals(AlertSeverity.CRITICAL, memAlert.severity)
        assertEquals("core", memAlert.targetHostName)
        assertEquals("Memory > 90% on core", memAlert.summary)

        val esxiAlert = alerts.first { it.title == "esxi-poller-down" }
        assertEquals(AlertSource.GRAFANA, esxiAlert.source)
        assertEquals(AlertSeverity.WARNING, esxiAlert.severity)
        assertEquals("server1", esxiAlert.targetHostName)
    }

    @Test
    fun fetchActiveAlerts_doesNotResurrectSilencedAlertsAndAvoidsFakeHostBadges(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/alertmanager/grafana/api/v2/alerts") -> {
                        // Must query active=true WITHOUT silenced=false so client sees suppressed state
                        assertFalse(path.contains("silenced=false"))
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                [
                                  {
                                    "fingerprint": "fp-silenced",
                                    "status": {"state": "suppressed", "silencedBy": ["silence-1"]},
                                    "labels": {"alertname": "MaintenanceWindowAlert", "severity": "warning", "instance": "docker-host"}
                                  },
                                  {
                                    "fingerprint": "fp-info",
                                    "status": {"state": "active"},
                                    "labels": {"alertname": "OperationalAuditNotice", "severity": "informational"},
                                    "annotations": {"summary": "Routine operational check"}
                                  },
                                  {
                                    "fingerprint": "fp-active-inst",
                                    "status": {"state": "active"},
                                    "labels": {"alertname": "BackupLagging", "severity": "low", "instance": "desktop"}
                                  }
                                ]
                                """.trimIndent(),
                            )
                    }
                    path.startsWith("/api/prometheus/grafana/api/v1/rules") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "status": "success",
                              "data": {
                                "groups": [
                                  {
                                    "name": "ops",
                                    "rules": [
                                      {
                                        "name": "MaintenanceWindowAlert",
                                        "state": "firing",
                                        "labels": {"severity": "warning", "instance": "docker-host"}
                                      },
                                      {
                                        "name": "BackupLagging",
                                        "state": "firing",
                                        "labels": {"severity": "low"}
                                      }
                                    ]
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val alerts = client.fetchActiveAlerts()
        // MaintenanceWindowAlert stays suppressed (not resurrected by /v1/rules),
        // and BackupLagging with instance=null in /v1/rules does not duplicate active BackupLagging on desktop!
        assertEquals(2, alerts.size)
        assertTrue(alerts.none { it.title == "MaintenanceWindowAlert" })

        val opAlert = alerts.first { it.title == "OperationalAuditNotice" }
        assertEquals(AlertSeverity.INFO, opAlert.severity)
        // "OperationalAuditNotice" contains "vcenter" substring inside "Operational", must NOT match server1 or fake host!
        assertNull(opAlert.targetHostName)

        val backupAlert = alerts.first { it.title == "BackupLagging" }
        assertEquals(AlertSeverity.INFO, backupAlert.severity)
        assertEquals("desktop", backupAlert.targetHostName)
    }

    @Test
    fun fetchActiveAlerts_http403_throwsMonitoringSourceException(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        try {
            client.fetchActiveAlerts()
            fail("Expected MonitoringSourceException")
        } catch (e: MonitoringSourceException) {
            assertEquals(AlertSource.GRAFANA, e.source)
            assertEquals(403, e.httpStatus)
        }
    }

    @Test
    fun fetchActiveAlerts_htmlLoginRedirect_throwsMonitoringSourceException(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><title>Grafana Login</title></html>"),
        )
        try {
            client.fetchActiveAlerts()
            fail("Expected MonitoringSourceException on HTML response")
        } catch (e: MonitoringSourceException) {
            assertEquals(AlertSource.GRAFANA, e.source)
            assertTrue(e.message.contains("HTML", ignoreCase = true) || e.message.contains("SSO", ignoreCase = true))
        }
    }
}
