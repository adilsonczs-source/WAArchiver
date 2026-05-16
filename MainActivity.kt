package com.waarchiver

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val btnPermission = findViewById<Button>(R.id.btnPermission)

        if (isNotificationListenerEnabled()) {
            statusText.text = "✅ WAArchiver ativo!\nCapturando notificações do WhatsApp."
            btnPermission.text = "Permissão concedida"
            btnPermission.isEnabled = false
        } else {
            statusText.text = "⚠️ Permissão necessária.\nClique no botão abaixo para ativar."
            btnPermission.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }
}
