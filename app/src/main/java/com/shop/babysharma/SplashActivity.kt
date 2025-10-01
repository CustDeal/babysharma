package com.shop.babysharma

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        val splashWeb = findViewById<WebView>(R.id.splashWeb)

        // Very safe, no remote code—just local HTML. JS not needed here but harmless.
        val settings: WebSettings = splashWeb.settings
        settings.javaScriptEnabled = true

        splashWeb.loadUrl("file:///android_asset/splash.html")

        // After ~2s, go to MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1800) // 1.8 sec; tweak as you like
    }
}
