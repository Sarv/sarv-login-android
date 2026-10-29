package com.sarv.authexample

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sarv.auth.SarvAuthException
import com.sarv.auth.SarvUser
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private lateinit var profile: TextView
    private lateinit var tokens: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        profile = findViewById(R.id.profile)
        tokens = findViewById(R.id.tokens)
        progress = findViewById(R.id.progress)

        val reload = findViewById<Button>(R.id.reload_profile)
        val token = findViewById<Button>(R.id.get_token)
        val signOut = findViewById<Button>(R.id.sign_out)
        actions = listOf(reload, token, signOut)

        reload.setOnClickListener { loadProfile() }
        token.setOnClickListener { getAccessToken() }
        signOut.setOnClickListener { signOut() }

        // Show what's stored right away, then refresh it from the server.
        render(sarv.currentUser)
        loadProfile()
    }

    /** GET /api/oauth/userinfo with a valid access token. */
    private fun loadProfile() = run {
        render(sarv.fetchUser())
    }

    /** What every API call does: get a token, refreshing it when it's about to expire. */
    private fun getAccessToken() = run {
        val token = sarv.validAccessToken()
        render(sarv.currentUser)
        Toast.makeText(this, getString(R.string.token_ready, token.take(12)), Toast.LENGTH_SHORT).show()
    }

    private fun signOut() {
        setWorking(true)
        lifecycleScope.launch {
            sarv.signOut(this@HomeActivity)
            backToSignIn()
        }
    }

    private fun run(work: suspend () -> Unit) {
        setWorking(true)
        lifecycleScope.launch {
            try {
                work()
            } catch (e: SarvAuthException.SessionExpired) {
                Toast.makeText(this@HomeActivity, R.string.session_ended, Toast.LENGTH_LONG).show()
                backToSignIn()
            } catch (e: SarvAuthException.NotSignedIn) {
                backToSignIn()
            } catch (e: SarvAuthException) {
                Toast.makeText(this@HomeActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                setWorking(false)
            }
        }
    }

    private fun render(user: SarvUser?) {
        profile.text = if (user == null) "—" else buildString {
            appendLine("Name: ${user.name ?: "—"}")
            appendLine("Email: ${user.email ?: "—"}")
            appendLine("Email verified: ${user.emailVerified?.let { if (it) "Yes" else "No" } ?: "—"}")
            append("User id (sub): ${user.sub}")
        }
        val stored = sarv.tokens
        tokens.text = if (stored == null) "—" else buildString {
            val expires = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(stored.expiresAtMillis))
            appendLine("Access token expires: $expires")
            appendLine("Refresh token: ${if (stored.refreshToken == null) "None" else "Stored"}")
            append("Scopes: ${stored.scope ?: "—"}")
        }
    }

    private fun setWorking(working: Boolean) {
        actions.forEach { it.isEnabled = !working }
        progress.visibility = if (working) View.VISIBLE else View.INVISIBLE
    }

    private fun backToSignIn() {
        startActivity(Intent(this, SignInActivity::class.java))
        finish()
    }
}
