package com.sarv.authexample

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sarv.auth.SarvAuthException
import com.sarv.auth.ui.SarvLoginButton
import kotlinx.coroutines.launch

class SignInActivity : AppCompatActivity() {

    private lateinit var loginButton: SarvLoginButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tokens survive restarts — a returning user goes straight home, no network call.
        if (sarv.isSignedIn) return openHome()

        setContentView(R.layout.activity_sign_in)
        loginButton = findViewById(R.id.sarv_login)
        progress = findViewById(R.id.progress)
        findViewById<TextView>(R.id.config_warning).visibility =
            if (SarvConfig.isConfigured) View.GONE else View.VISIBLE

        loginButton.setOnClickListener { signIn() }
    }

    private fun signIn() {
        setWorking(true)
        lifecycleScope.launch {
            try {
                sarv.signIn(this@SignInActivity)
                openHome()
            } catch (e: SarvAuthException.Cancelled) {
                // The user closed the tab.
            } catch (e: SarvAuthException) {
                AlertDialog.Builder(this@SignInActivity)
                    .setTitle(R.string.sign_in_failed)
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                setWorking(false)
            }
        }
    }

    private fun setWorking(working: Boolean) {
        loginButton.isEnabled = !working
        progress.visibility = if (working) View.VISIBLE else View.GONE
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
