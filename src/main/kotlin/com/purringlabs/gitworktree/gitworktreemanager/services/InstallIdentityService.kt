package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * Stores an anonymous, stable identifier for this plugin install.
 *
 * The identifier is a random UUID generated once and persisted across IDE restarts and plugin
 * upgrades. It contains no personally identifiable information; its only purpose is to let
 * telemetry distinguish "the same install acting again" from "a different install", which is what
 * makes daily/weekly/monthly active-user counts and retention possible.
 */
@Service(Service.Level.APP)
@State(
    name = "GitWorktreeInstallIdentity",
    storages = [Storage("gitWorktreeInstallIdentity.xml")]
)
class InstallIdentityService : PersistentStateComponent<InstallIdentityService.State> {

    data class State(var installId: String = "")

    private var persistedState = State()

    override fun getState(): State = persistedState

    override fun loadState(loadedState: State) {
        persistedState = loadedState
    }

    /**
     * Returns the stable anonymous install id, generating and persisting one on first access.
     */
    fun getInstallId(): String {
        if (persistedState.installId.isBlank()) {
            persistedState.installId = UUID.randomUUID().toString()
        }
        return persistedState.installId
    }

    companion object {
        fun getInstance(): InstallIdentityService =
            ApplicationManager.getApplication().getService(InstallIdentityService::class.java)
    }
}
