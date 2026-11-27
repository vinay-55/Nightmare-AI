package com.example.nightmareai.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.nightmareai.MainActivity
import com.example.nightmareai.auth.AuthSessionManager
import com.example.nightmareai.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

/**
 * Entry authentication bridge.
 * Supports immediate open-access login (`googleId = "open_access_user"`) as well as
 * plug-and-play Google Sign-In without modifying Room schemas or workspace screens.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: AuthSessionManager

    @Suppress("DEPRECATION")
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            sessionManager.onGoogleSignInAccount(account)
            navigateToWorkspace()
        } catch (e: ApiException) {
            Toast.makeText(
                this,
                "Google Sign-In unavailable in dev build (code ${e.statusCode}). Use Login for Open Access.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AuthSessionManager(this)
        if (sessionManager.isLoggedIn()) {
            navigateToWorkspace()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLoginOpenAccess.setOnClickListener {
            sessionManager.loginOpenAccess()
            navigateToWorkspace()
        }

        binding.btnGoogleSignIn.setOnClickListener {
            val client = sessionManager.createGoogleSignInClient(this)
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun navigateToWorkspace() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
