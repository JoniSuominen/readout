package com.jonisuominen.readout.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface DashboardConnector {
    val source: DataSource

    /** Asks for the permission or sign-in this source needs. Needs the app on screen. */
    suspend fun authorize()

    suspend fun refresh(): List<DashboardMetric>

    suspend fun disconnect(): DisconnectResult
}

/** A connector whose permission can be revoked while the app is closed. */
interface SnapshotGuard {
    /** Clears a cached private snapshot once access is gone. True when it did. */
    fun clearSnapshotIfAccessUnavailable(): Boolean
}

enum class OuraConnectionMethod(val key: String, val displayName: String) {
    HEALTH_CONNECT("healthConnect", "Health Connect"),
    DIRECT("direct", "Oura account"),
}

@Serializable
data class DisconnectResult(
    val localCredentialsCleared: Boolean,
    val remoteAuthorization: RemoteAuthorization,
) {
    @Serializable
    enum class RemoteAuthorization {
        @SerialName("notApplicable") NOT_APPLICABLE,
        @SerialName("revoked") REVOKED,
        @SerialName("unconfirmed") UNCONFIRMED,
        @SerialName("noStoredCredentials") NO_STORED_CREDENTIALS,
    }

    val requiresAttention: Boolean
        get() = !localCredentialsCleared || remoteAuthorization == RemoteAuthorization.UNCONFIRMED

    val warningMessage: String? get() = if (requiresAttention) statusMessage else null

    val statusMessage: String
        get() {
            if (!localCredentialsCleared) {
                return when (remoteAuthorization) {
                    RemoteAuthorization.REVOKED ->
                        "Oura account access was revoked, but saved credentials could not be removed from this device. The connection is no longer used. Try disconnecting again."
                    RemoteAuthorization.UNCONFIRMED ->
                        "Oura is no longer used, but removal of saved credentials and revocation of Oura account access could not be confirmed. Try disconnecting again and review access in your Oura account."
                    RemoteAuthorization.NO_STORED_CREDENTIALS ->
                        "Oura is no longer used, but removal of saved credentials from this device could not be confirmed. Try disconnecting again."
                    RemoteAuthorization.NOT_APPLICABLE ->
                        "This source is no longer used, but removal of local credentials could not be confirmed. Permissions remain in Android Settings."
                }
            }
            return when (remoteAuthorization) {
                RemoteAuthorization.NOT_APPLICABLE ->
                    "Disconnected. Permissions granted in Android Settings remain unchanged."
                RemoteAuthorization.REVOKED ->
                    "Disconnected from Oura. Saved credentials were removed and Oura account access was revoked."
                RemoteAuthorization.UNCONFIRMED ->
                    "Disconnected and removed saved Oura credentials. Revoking access in your Oura account could not be confirmed. Review account access in Oura."
                RemoteAuthorization.NO_STORED_CREDENTIALS ->
                    "Disconnected. No saved Oura credentials were found. You can review previous app access in your Oura account."
            }
        }

    companion object {
        /** System permission grants are managed by Android even after local disconnection. */
        val localOnly = DisconnectResult(true, RemoteAuthorization.NOT_APPLICABLE)
        val revoked = DisconnectResult(true, RemoteAuthorization.REVOKED)
        val noStoredCredentials = DisconnectResult(true, RemoteAuthorization.NO_STORED_CREDENTIALS)
    }
}

class ConnectorException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        UNAVAILABLE,
        AUTHORIZATION_DENIED,

        /**
         * The data exists but cannot be read right now, such as health records
         * while the app is in the background. Not a failure worth showing.
         */
        TEMPORARILY_UNREADABLE,
    }

    companion object {
        fun unavailable(message: String) = ConnectorException(Kind.UNAVAILABLE, message)
        fun authorizationDenied(message: String) = ConnectorException(Kind.AUTHORIZATION_DENIED, message)
        fun temporarilyUnreadable(message: String) = ConnectorException(Kind.TEMPORARILY_UNREADABLE, message)
    }
}

/** The message shown for a failed connect or refresh. */
fun Throwable.userMessage(): String = when (this) {
    is ConnectorException, is OuraException -> message ?: "Something went wrong. Try again."
    is java.io.IOException -> "No connection. Check your network and try again."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Try again."
}
