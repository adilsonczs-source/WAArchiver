package com.waarchiver

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WAArchiver"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != WHATSAPP_PACKAGE && pkg != WHATSAPP_BUSINESS) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "Desconhecido"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val timestamp = sbn.postTime

        Log.d(TAG, "[$timestamp] $title: $text")

        // Salvar na lista compartilhada
        NotificationStore.add(timestamp, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // ignorar por enquanto
    }
}
