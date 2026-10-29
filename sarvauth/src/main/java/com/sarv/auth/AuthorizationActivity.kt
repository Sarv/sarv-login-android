package com.sarv.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands one URL to a Custom Tab (never an embedded WebView) and waits for the
 * redirect back. One flow at a time; starting another cancels the first.
 */
internal object Authorizer {
    @Volatile private var pending: CompletableDeferred<Result<String>>? = null

    suspend fun open(context: Context, url: String): String {
        val deferred = CompletableDeferred<Result<String>>()
        pending?.complete(Result.failure(SarvAuthException.Cancelled()))
        pending = deferred
        withContext(Dispatchers.Main) { context.startActivity(AuthorizationActivity.intent(context, url)) }
        return deferred.await().getOrThrow()
    }

    fun deliver(result: Result<String>) {
        pending?.complete(result)
        pending = null
    }
}

/** Opens the Custom Tab, then receives the redirect from [RedirectActivity]. */
class AuthorizationActivity : Activity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean(KEY_STARTED) ?: false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            val url = intent.getStringExtra(EXTRA_URL)
            if (url == null) {
                Authorizer.deliver(Result.failure(SarvAuthException.Cancelled()))
                finish()
                return
            }
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
            } catch (e: ActivityNotFoundException) {
                Authorizer.deliver(Result.failure(SarvAuthException.NoBrowser()))
                finish()
            }
            return
        }
        // Back here either with Sarv's redirect, or because the user closed the tab.
        val data = intent.data
        Authorizer.deliver(
            if (data != null) Result.success(data.toString()) else Result.failure(SarvAuthException.Cancelled())
        )
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTED, started)
    }

    internal companion object {
        private const val EXTRA_URL = "com.sarv.auth.URL"
        private const val KEY_STARTED = "com.sarv.auth.STARTED"

        fun intent(context: Context, url: String): Intent =
            Intent(context, AuthorizationActivity::class.java).putExtra(EXTRA_URL, url).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/** Catches `<redirect scheme>://…` and forwards it to [AuthorizationActivity]. */
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, AuthorizationActivity::class.java)
                .setData(intent.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
