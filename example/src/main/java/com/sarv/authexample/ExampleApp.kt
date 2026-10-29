package com.sarv.authexample

import android.app.Application
import android.content.Context
import com.sarv.auth.SarvAuthClient
import com.sarv.auth.SarvAuthConfiguration

/** Replace these with your app's values from the Sarv developer dashboard. */
object SarvConfig {
    const val CLIENT_ID = "YOUR_CLIENT_ID"

    /** Must be registered exactly as written; its scheme is the manifest placeholder. */
    const val REDIRECT_URI = "com.sarv.authexample:/oauth2redirect"

    val isConfigured: Boolean get() = CLIENT_ID != "YOUR_CLIENT_ID"
}

class ExampleApp : Application() {
    /** One client for the app's lifetime — refreshes are serialised per instance. */
    val sarv: SarvAuthClient by lazy {
        SarvAuthClient(
            this,
            SarvAuthConfiguration(
                clientId = SarvConfig.CLIENT_ID,
                redirectUri = SarvConfig.REDIRECT_URI,
            ),
        )
    }
}

val Context.sarv: SarvAuthClient get() = (applicationContext as ExampleApp).sarv
