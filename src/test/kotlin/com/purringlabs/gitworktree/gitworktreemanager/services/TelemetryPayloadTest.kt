package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.testFramework.LightPlatformTestCase
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ListWorktreesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError
import com.purringlabs.gitworktree.gitworktreemanager.models.TelemetryContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

class TelemetryPayloadTest : LightPlatformTestCase() {

    fun testOperationTelemetryPayloadIncludesInstallAndSessionIdentifiers() {
        val operation = ListWorktreesEvent(
            operationId = "operation-789",
            startTime = 1_700_000_000_000,
            durationMs = 42,
            success = true,
            context = testTelemetryContext(),
            worktreeCount = 3,
            error = null
        )
        val telemetryService = TelemetryServiceImpl()

        val buildPayload = TelemetryServiceImpl::class.java.getDeclaredMethod(
            "buildOperationPayload",
            com.purringlabs.gitworktree.gitworktreemanager.models.OperationEvent::class.java
        )
        buildPayload.isAccessible = true

        val payload = buildPayload.invoke(telemetryService, operation) as String
        val event = Json.parseToJsonElement(payload).jsonArray.single().jsonObject

        assertEquals("install-123", event.getValue("install_id").jsonPrimitive.content)
        assertEquals("session-456", event.getValue("session_id").jsonPrimitive.content)
    }

    fun testHeartbeatTelemetryPayloadIncludesInstallAndSessionIdentifiers() {
        val telemetryService = TelemetryServiceImpl()

        val contextField = TelemetryServiceImpl::class.java.getDeclaredField("context")
        contextField.isAccessible = true
        contextField.set(telemetryService, testTelemetryContext())

        val buildPayload = TelemetryServiceImpl::class.java.getDeclaredMethod("buildHeartbeatPayload")
        buildPayload.isAccessible = true

        val payload = buildPayload.invoke(telemetryService) as String
        val event = Json.parseToJsonElement(payload).jsonArray.single().jsonObject

        assertEquals("SESSION_HEARTBEAT", event.getValue("eventType").jsonPrimitive.content)
        assertEquals("install-123", event.getValue("install_id").jsonPrimitive.content)
        assertEquals("session-456", event.getValue("session_id").jsonPrimitive.content)
    }

    fun testErrorTelemetryPayloadIncludesInstallAndSessionIdentifiers() {
        val error = ErrorEvent(
            errorId = "error-123",
            timestamp = 1_700_000_000_000,
            context = testTelemetryContext(),
            error = StructuredError(
                errorType = "UNKNOWN_ERROR",
                errorMessage = "boom",
                gitCommand = null,
                gitExitCode = null,
                gitErrorOutput = null,
                stackTrace = null
            )
        )
        val telemetryService = TelemetryServiceImpl()

        val buildPayload = TelemetryServiceImpl::class.java.getDeclaredMethod(
            "buildErrorPayload",
            ErrorEvent::class.java
        )
        buildPayload.isAccessible = true

        val payload = buildPayload.invoke(telemetryService, error) as String
        val event = Json.parseToJsonElement(payload).jsonArray.single().jsonObject

        assertEquals("install-123", event.getValue("install_id").jsonPrimitive.content)
        assertEquals("session-456", event.getValue("session_id").jsonPrimitive.content)
    }

    private fun testTelemetryContext(): TelemetryContext = TelemetryContext(
        ideVersion = "2025.2",
        pluginVersion = "1.1.19",
        osName = "macOS",
        osVersion = "15.0",
        jvmVersion = "21",
        countryCode = "US",
        installId = "install-123",
        sessionId = "session-456",
        ideProduct = "IntelliJ IDEA"
    )
}
