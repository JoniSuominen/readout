package com.jonisuominen.readout.connect

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import com.jonisuominen.readout.MainActivity
import com.jonisuominen.readout.container
import com.jonisuominen.readout.core.OuraAuthenticating
import com.jonisuominen.readout.core.OuraAuthorizationGrant
import com.jonisuominen.readout.core.OuraAuthorizationRequest
import com.jonisuominen.readout.core.OuraError
import com.jonisuominen.readout.core.OuraException
import com.jonisuominen.readout.core.OuraPublicConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signs in at Oura in a browser tab. The state and PKCE verifier for the
 * attempt live only in memory and are discarded when it ends.
 */
class OuraBrowserAuthenticator(private val bridge: PermissionBridge) : OuraAuthenticating {
    private class Attempt(val request: OuraAuthorizationRequest, val result: CompletableDeferred<OuraAuthorizationGrant>)

    private var attempt: Attempt? = null

    /** True while the sign-in page is open and no callback has arrived. */
    val isWaitingForCallback: Boolean get() = attempt?.result?.isActive == true

    override suspend fun authenticate(configuration: OuraPublicConfiguration): OuraAuthorizationGrant =
        withContext(Dispatchers.Main.immediate) {
            if (attempt != null) throw OuraException(OuraError.CANCELLED)
            val activity = bridge.activity ?: throw OuraException(OuraError.AUTHORIZATION_DENIED)
            val request = OuraAuthorizationRequest.generate()
            val url = request.authorizationURL(configuration.validated().clientID)
            val current = Attempt(request, CompletableDeferred())
            attempt = current
            try {
                try {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url))
                } catch (_: ActivityNotFoundException) {
                    throw OuraException(OuraError.AUTHORIZATION_DENIED)
                }
                current.result.await()
            } finally {
                if (attempt === current) attempt = null
            }
        }

    override suspend fun cancel() = withContext(Dispatchers.Main.immediate) { cancelNow() }

    /** The person came back to the app without finishing sign-in. */
    fun cancelNow() {
        attempt?.result?.completeExceptionally(OuraException(OuraError.CANCELLED))
    }

    /** Called on the main thread with the callback link Oura redirected to. */
    fun handleCallback(callback: String) {
        val current = attempt ?: return
        try {
            current.result.complete(current.request.validateCallback(callback))
        } catch (error: OuraException) {
            current.result.completeExceptionally(error)
        }
    }
}

/** Receives `com.jonisuominen.readout://oauth/oura` and returns to the app. */
class OuraRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { container.ouraAuthenticator.handleCallback(it) }
        // Bring Readout forward and close the sign-in tab that sits above it.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
