package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ListWorktreesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.NoRepositoryCtaEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.OpenWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.OperationEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError
import com.purringlabs.gitworktree.gitworktreemanager.models.TelemetryContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface TelemetryService {
    fun recordOperation(operation: OperationEvent)
    fun recordError(error: ErrorEvent)

    /**
     * Records a passive presence signal for the current IDE session. Unlike operations, this fires
     * even when the user takes no explicit action, so daily-active-user counts are not biased toward
     * users who happen to mutate worktrees. Fires at most once per IDE launch.
     */
    fun recordSessionHeartbeat()

    fun getContext(): TelemetryContext
    fun isEnabled(): Boolean
}

@Service(Service.Level.APP)
class TelemetryServiceImpl : TelemetryService, Disposable {
    private val logger = Logger.getInstance(TelemetryServiceImpl::class.java)
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val context: TelemetryContext
    private val newRelicEnabled: Boolean
    // Random per-IDE-launch identifier; distinct from the persisted, anonymous install id.
    private val sessionId: String = UUID.randomUUID().toString()
    // Ensures the passive session heartbeat is sent at most once per IDE launch.
    private val heartbeatSent = AtomicBoolean(false)

    init {
        context = TelemetryContext(
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            pluginVersion = getPluginVersion(),
            osName = SystemInfo.OS_NAME,
            osVersion = SystemInfo.OS_VERSION,
            jvmVersion = SystemInfo.JAVA_VERSION,
            countryCode = Locale.getDefault().country.ifBlank { "unknown" },
            installId = getInstallId(),
            sessionId = sessionId,
            ideProduct = getIdeProduct()
        )
        newRelicEnabled = initializeNewRelic()
    }

    override fun recordOperation(operation: OperationEvent) {
        runAsync {
            try {
                if (newRelicEnabled) {
                    sendToNewRelic(operation)
                } else {
                    logToIntelliJ(operation)
                }
            } catch (e: Exception) {
                logger.error("Failed to record operation telemetry", e)
            }
        }
    }

    override fun recordError(error: ErrorEvent) {
        runAsync {
            try {
                if (newRelicEnabled) {
                    val payload = buildErrorPayload(error)
                    sendPayload(payload)
                } else {
                    logToIntelliJ(error)
                }
            } catch (e: Exception) {
                logger.error("Failed to record error telemetry", e)
            }
        }
    }

    override fun recordSessionHeartbeat() {
        if (!heartbeatSent.compareAndSet(false, true)) return
        runAsync {
            try {
                val payload = buildHeartbeatPayload()
                if (newRelicEnabled) {
                    sendPayload(payload)
                } else {
                    logger.info("TELEMETRY_HEARTBEAT: $payload")
                }
            } catch (e: Exception) {
                logger.error("Failed to record session heartbeat telemetry", e)
            }
        }
    }

    override fun getContext(): TelemetryContext = context

    override fun isEnabled(): Boolean = newRelicEnabled

    override fun dispose() {
        if (newRelicEnabled) {
            logger.info("TelemetryService disposed")
        }
    }

    private fun initializeNewRelic(): Boolean {
        return try {
            val apiKey = ApiKeyHolder.getKey()
            val accountId = AccountIdHolder.getAccountId()
            if (apiKey.isBlank() || accountId.isBlank()) {
                logger.warn("New Relic account id or insert key is missing; telemetry disabled")
                return false
            }

            logger.info("New Relic telemetry initialized")
            true
        } catch (e: Exception) {
            logger.warn("Failed to initialize New Relic telemetry; falling back to local logging", e)
            false
        }
    }

    private fun sendToNewRelic(operation: OperationEvent) {
        val payload = buildOperationPayload(operation)
        sendPayload(payload)
    }

    private fun buildOperationPayload(operation: OperationEvent): String {
        val payload = buildJsonArray {
            add(buildJsonObject {
                put("eventType", operation.operationType)
                put("event_type_name", operation.operationType)
                put("operation_type", operation.operationType)
                put("operation_id", operation.operationId)
                put("timestamp", System.currentTimeMillis())
                put("duration_ms", operation.durationMs)
                put("success", operation.success)
                addContextFields(this, operation.context, includeIdentifiers = true)
                addOperationFields(this, operation)
            })
        }

        return json.encodeToString(JsonArray.serializer(), payload)
    }

    private fun buildErrorPayload(error: ErrorEvent): String {
        val payload = buildJsonArray {
            add(buildJsonObject {
                put("eventType", "ERROR_EVENT")
                put("event_type_name", "ERROR_EVENT")
                put("error_id", error.errorId)
                put("timestamp", error.timestamp)
                addContextFields(this, error.context)
                addErrorFields(this, error.error)
            })
        }

        return json.encodeToString(JsonArray.serializer(), payload)
    }

    private fun buildHeartbeatPayload(): String {
        val payload = buildJsonArray {
            add(buildJsonObject {
                put("eventType", "SESSION_HEARTBEAT")
                put("event_type_name", "SESSION_HEARTBEAT")
                put("timestamp", System.currentTimeMillis())
                addContextFields(this, context, includeIdentifiers = true)
            })
        }

        return json.encodeToString(JsonArray.serializer(), payload)
    }

    private fun addContextFields(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        context: TelemetryContext,
        includeIdentifiers: Boolean = false
    ) {
        builder.put("ide_version", context.ideVersion)
        builder.put("plugin_version", context.pluginVersion)
        builder.put("os_name", context.osName)
        builder.put("os_version", context.osVersion)
        builder.put("jvm_version", context.jvmVersion)
        builder.put("country_code", context.countryCode)
        if (includeIdentifiers) {
            builder.put("install_id", context.installId)
            builder.put("session_id", context.sessionId)
        }
        builder.put("ide_product", context.ideProduct)
    }

    private fun addOperationFields(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        operation: OperationEvent
    ) {
        when (operation) {
            is CreateWorktreeEvent -> {
                builder.put("worktree_name", operation.worktreeName)
                builder.put("branch_name", operation.branchName)
                addErrorFields(builder, operation.error)
            }
            is DeleteWorktreeEvent -> {
                builder.put("worktree_name", operation.worktreeName)
                builder.put("branch_deleted", operation.branchDeleted)
                addErrorFields(builder, operation.error)
            }
            is ListWorktreesEvent -> {
                builder.put("worktree_count", operation.worktreeCount)
                addErrorFields(builder, operation.error)
            }
            is OpenWorktreeEvent -> {
                builder.put("worktree_path", operation.worktreePath)
                builder.put("already_open", operation.alreadyOpen)
            }
            is NoRepositoryCtaEvent -> {
                builder.put("attempted_operation", operation.attemptedOperation)
                builder.put("cta", operation.cta)
            }
        }
    }

    private fun addErrorFields(builder: kotlinx.serialization.json.JsonObjectBuilder, error: StructuredError?) {
        if (error == null) return
        builder.put("error_type", error.errorType)
        builder.put("error_message", error.errorMessage)
        error.gitCommand?.let { builder.put("git_command", it) }
        error.gitExitCode?.let { builder.put("git_exit_code", it) }
        error.gitErrorOutput?.let { builder.put("git_error_output", it) }
        error.stackTrace?.let { builder.put("stack_trace", it) }
    }

    private fun sendPayload(payload: String) {
        val apiKey = ApiKeyHolder.getKey()
        val accountId = AccountIdHolder.getAccountId()
        if (apiKey.isBlank() || accountId.isBlank()) {
            logger.warn("New Relic account id or insert key is missing; telemetry disabled")
            return
        }

        val request = HttpRequest.newBuilder(URI.create(buildEventsEndpoint(accountId)))
            .header("Content-Type", "application/json")
            .header("X-Insert-Key", apiKey)
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("New Relic ingest failed: HTTP ${response.statusCode()} - ${response.body()}")
            }
        } catch (e: Exception) {
            logger.warn("New Relic ingest request failed", e)
        }
    }

    private fun buildEventsEndpoint(accountId: String): String {
        return "https://insights-collector.newrelic.com/v1/accounts/$accountId/events"
    }

    private fun logToIntelliJ(operation: OperationEvent) {
        val payload = buildOperationPayload(operation)
        logger.info("TELEMETRY_EVENT: $payload")
    }

    private fun logToIntelliJ(error: ErrorEvent) {
        val payload = buildErrorPayload(error)
        logger.info("TELEMETRY_ERROR: $payload")
    }

    private fun runAsync(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application == null) {
            block()
        } else {
            application.executeOnPooledThread(block)
        }
    }

    private fun getInstallId(): String {
        return try {
            InstallIdentityService.getInstance().getInstallId()
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun getIdeProduct(): String {
        return try {
            ApplicationNamesInfo.getInstance().fullProductName
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun getPluginVersion(): String {
        return TelemetryServiceImpl::class.java.`package`?.implementationVersion ?: "unknown"
    }

    private object ApiKeyHolder {
        private const val encodedKey = "ZDgxNGZhNzQ1MzIxZWIyODlkOGU3MjJiNzEzMTA4NDdGRkZGTlJBTA=="
        fun getKey(): String = runCatching {
            String(Base64.getDecoder().decode(encodedKey))
        }.getOrDefault("")
    }

    private object AccountIdHolder {
        private const val accountId = "7666948"
        fun getAccountId(): String = accountId
    }

    companion object {
        fun getInstance(): TelemetryService {
            return ApplicationManager.getApplication().getService(TelemetryServiceImpl::class.java)
        }
    }
}
