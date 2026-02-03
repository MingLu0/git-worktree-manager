package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.util.SystemInfo
import com.newrelic.api.agent.NewRelic
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyFilesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ListWorktreesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.OperationEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError
import com.purringlabs.gitworktree.gitworktreemanager.models.TelemetryContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.Base64

interface TelemetryService {
    fun recordOperation(operation: OperationEvent)
    fun recordError(error: ErrorEvent)
    fun getContext(): TelemetryContext
    fun isEnabled(): Boolean
}

@Service(Service.Level.APP)
class TelemetryServiceImpl : TelemetryService, Disposable {
    private val logger = Logger.getInstance(TelemetryServiceImpl::class.java)
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val context: TelemetryContext
    private val newRelicEnabled: Boolean

    init {
        context = TelemetryContext(
            ideVersion = ApplicationInfo.getInstance().fullVersion,
            pluginVersion = getPluginVersion(),
            osName = SystemInfo.OS_NAME,
            osVersion = SystemInfo.OS_VERSION,
            jvmVersion = SystemInfo.JAVA_VERSION
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
                    val attributes = buildErrorAttributes(error)
                    NewRelic.getAgent().getInsights().recordCustomEvent("ERROR_EVENT", attributes)
                } else {
                    logToIntelliJ(error)
                }
            } catch (e: Exception) {
                logger.error("Failed to record error telemetry", e)
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
            if (apiKey.isBlank()) {
                logger.warn("New Relic API key is missing; telemetry disabled")
                return false
            }

            NewRelic.addCustomParameter("ide_version", context.ideVersion)
            NewRelic.addCustomParameter("plugin_version", context.pluginVersion)
            NewRelic.addCustomParameter("os_name", context.osName)
            NewRelic.addCustomParameter("os_version", context.osVersion)
            NewRelic.addCustomParameter("jvm_version", context.jvmVersion)

            logger.info("New Relic telemetry initialized")
            true
        } catch (e: Exception) {
            logger.warn("Failed to initialize New Relic telemetry; falling back to local logging", e)
            false
        }
    }

    private fun sendToNewRelic(operation: OperationEvent) {
        val attributes = buildOperationAttributes(operation)
        NewRelic.getAgent().getInsights().recordCustomEvent(operation.operationType, attributes)
    }

    private fun buildOperationAttributes(operation: OperationEvent): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>(
            "operation_id" to operation.operationId,
            "duration_ms" to operation.durationMs,
            "success" to operation.success,
            "ide_version" to operation.context.ideVersion,
            "plugin_version" to operation.context.pluginVersion,
            "os_name" to operation.context.osName,
            "os_version" to operation.context.osVersion,
            "jvm_version" to operation.context.jvmVersion
        )

        attributes.putAll(extractOperationFields(operation))

        return attributes
    }

    private fun extractOperationFields(operation: OperationEvent): Map<String, Any> {
        return when (operation) {
            is CreateWorktreeEvent -> baseAttributes(
                mapOf(
                    "worktree_name" to operation.worktreeName,
                    "branch_name" to operation.branchName
                ),
                operation.error
            )
            is DeleteWorktreeEvent -> baseAttributes(
                mapOf(
                    "worktree_name" to operation.worktreeName,
                    "branch_deleted" to operation.branchDeleted
                ),
                operation.error
            )
            is ListWorktreesEvent -> baseAttributes(
                mapOf("worktree_count" to operation.worktreeCount),
                operation.error
            )
            is CopyFilesEvent -> baseAttributes(
                mapOf(
                    "item_count" to operation.itemCount,
                    "success_count" to operation.successCount,
                    "failure_count" to operation.failureCount
                ),
                operation.error
            )
        }
    }

    private fun baseAttributes(
        fields: Map<String, Any>,
        error: StructuredError?
    ): Map<String, Any> {
        val attributes = fields.toMutableMap()
        attributes.putAll(errorAttributes(error))
        return attributes
    }

    private fun errorAttributes(error: StructuredError?): Map<String, Any> {
        if (error == null) return emptyMap()
        val attributes = mutableMapOf<String, Any>(
            "error_type" to error.errorType,
            "error_message" to error.errorMessage
        )
        error.gitCommand?.let { attributes["git_command"] = it }
        error.gitExitCode?.let { attributes["git_exit_code"] = it }
        error.gitErrorOutput?.let { attributes["git_error_output"] = it }
        error.stackTrace?.let { attributes["stack_trace"] = it }
        return attributes
    }

    private fun buildErrorAttributes(error: ErrorEvent): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>(
            "error_id" to error.errorId,
            "timestamp" to error.timestamp,
            "ide_version" to error.context.ideVersion,
            "plugin_version" to error.context.pluginVersion,
            "os_name" to error.context.osName,
            "os_version" to error.context.osVersion,
            "jvm_version" to error.context.jvmVersion
        )
        attributes.putAll(errorAttributes(error.error))
        return attributes
    }

    private fun logToIntelliJ(operation: OperationEvent) {
        val payload = json.encodeToString(OperationEvent.serializer(), operation)
        logger.info("TELEMETRY_EVENT: $payload")
    }

    private fun logToIntelliJ(error: ErrorEvent) {
        val payload = json.encodeToString(ErrorEvent.serializer(), error)
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

    private fun getPluginVersion(): String {
        return try {
            PluginManagerCore.getPlugin(
                PluginId.getId("com.purringlabs.gitworktree.gitworktreemanager")
            )?.version ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private object ApiKeyHolder {
        private const val encodedKey = "ZDgxNGZhNzQ1MzIxZWIyODlkOGU3MjJiNzEzMTA4NDdGRkZGTlJBTA=="
        fun getKey(): String = runCatching {
            String(Base64.getDecoder().decode(encodedKey))
        }.getOrDefault("")
    }

    companion object {
        fun getInstance(): TelemetryService {
            return ApplicationManager.getApplication().getService(TelemetryServiceImpl::class.java)
        }
    }
}
