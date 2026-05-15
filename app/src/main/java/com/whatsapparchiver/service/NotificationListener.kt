package com.whatsapparchiver.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.PendingCapture
import com.whatsapparchiver.util.PhoneNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService — Escuta notificações do WhatsApp em segundo plano.
 *
 * Fluxo:
 * 1. Recebe notificação do WhatsApp
 * 2. Extrai contato, texto e horário
 * 3. Verifica se o número está na lista autorizada
 * 4. Evita duplicatas com cache em memória
 * 5. Grava em PendingCapture para processamento posterior
 *
 * PERMISSÃO NECESSÁRIA no AndroidManifest:
 *   <service android:name=".service.NotificationListenerService"
 *       android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.service.notification.NotificationListenerService"/>
 *     </intent-filter>
 *   </service>
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WANotifListener"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        // Janela de deduplicação: ignora mensagens idênticas em 5 segundos
        private const val DEDUP_WINDOW_MS = 5000L
    }

    // Job pai para cancelar todas as coroutines quando o serviço for destruído
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Cache de deduplicação: chave = "contato|texto", valor = timestamp
    private val recentNotifications = ConcurrentHashMap<String, Long>()

    private lateinit var repository: ConversationRepository

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(applicationContext)
        repository = ConversationRepository(db)
        Log.i(TAG, "NotificationListener iniciado")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.i(TAG, "NotificationListener encerrado")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // Filtra apenas WhatsApp e WhatsApp Business
        if (pkg != WHATSAPP_PACKAGE && pkg != WHATSAPP_BUSINESS_PACKAGE) return

        // Ignora notificações agrupadas (sumário)
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras: Bundle = sbn.notification.extras ?: return

        // Extrai campos da notificação
        val contactName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val timestamp = sbn.postTime

        // Deduplicação: ignora se mesma mensagem chegou há menos de 5s
        val dedupKey = "$contactName|$messageText"
        val lastSeen = recentNotifications[dedupKey] ?: 0L
        if (timestamp - lastSeen < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Duplicata ignorada: $dedupKey")
            return
        }
        recentNotifications[dedupKey] = timestamp

        // Limpa entradas antigas do cache (> 60s)
        val now = System.currentTimeMillis()
        recentNotifications.entries.removeIf { now - it.value > 60_000L }

        Log.i(TAG, "Nova notificação WhatsApp: contato=$contactName, texto=$messageText")

        // Processa em background
        scope.launch {
            processNotification(
                contactName = contactName,
                messageText = messageText,
                timestamp = timestamp,
                sourcePackage = pkg
            )
        }
    }

    /**
     * Verifica se o contato está na lista autorizada e grava captura pendente.
     * A normalização tenta casar o nome do contato com números registrados.
     */
    private suspend fun processNotification(
        contactName: String,
        messageText: String,
        timestamp: Long,
        sourcePackage: String
    ) {
        try {
            // Busca todos os phones habilitados
            val enabledPhones = repository.getEnabledPhonesSync()
            if (enabledPhones.isEmpty()) return

            // Tenta encontrar match por displayLabel ou número normalizado
            val matched = enabledPhones.firstOrNull { phone ->
                phone.displayLabel.equals(contactName, ignoreCase = true) ||
                PhoneNormalizer.normalize(contactName)?.let { normalized ->
                    phone.normalizedNumber == normalized
                } == true
            }

            if (matched == null) {
                Log.d(TAG, "Contato '$contactName' não está na lista monitorada")
                return
            }

            Log.i(TAG, "Match! Gravando captura pendente para ${matched.normalizedNumber}")

            // Grava pendência para o serviço de acessibilidade processar
            val pending = PendingCapture(
                phoneId = matched.id,
                normalizedNumber = matched.normalizedNumber,
                contactName = contactName,
                notificationText = messageText,
                notificationTimestamp = timestamp,
                sourcePackage = sourcePackage,
                processed = false
            )
            repository.insertPendingCapture(pending)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar notificação", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Não utilizado, mas pode ser implementado para rastrear leitura
    }
}
