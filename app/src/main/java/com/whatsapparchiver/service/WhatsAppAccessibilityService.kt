package com.whatsapparchiver.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.CapturedMessage
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.PendingCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * WhatsAppAccessibilityService — Acessa a tela do WhatsApp para capturar mensagens.
 *
 * ATENÇÃO: Este serviço usa Accessibility API, que lê conteúdo visível na tela.
 * Não acessa o banco criptografado do WhatsApp, apenas o que está visível ao usuário.
 *
 * PERMISSÃO NECESSÁRIA no AndroidManifest:
 *   <service android:name=".service.WhatsAppAccessibilityService"
 *       android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.accessibilityservice.AccessibilityService"/>
 *     </intent-filter>
 *     <meta-data android:name="android.accessibilityservice"
 *         android:resource="@xml/accessibility_service_config"/>
 *   </service>
 *
 * Arquivo res/xml/accessibility_service_config.xml:
 *   <accessibility-service
 *       android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
 *       android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
 *       android:canRetrieveWindowContent="true"
 *       android:packageNames="com.whatsapp,com.whatsapp.w4b"
 *       android:description="@string/accessibility_description"/>
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WAAccessibility"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
        // Aguarda 1.5s após abrir conversa para capturar
        private const val CAPTURE_DELAY_MS = 1500L
        // Máximo de mensagens por captura (evita scroll infinito)
        private const val MAX_MESSAGES_PER_CAPTURE = 100

        // ViewId do RecyclerView de mensagens no WhatsApp (pode mudar com atualizações)
        private const val CONVERSATION_LIST_ID = "com.whatsapp:id/conversation_recycler_view"
        // ViewId de mensagem recebida
        private const val MSG_BODY_ID = "com.whatsapp:id/message_text"
        // ViewId do timestamp
        private const val MSG_TIME_ID = "com.whatsapp:id/message_delivery_time_text"
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var repository: ConversationRepository

    // Controle de estado
    private var isCapturing = false
    private var currentPendingId: Long = -1
    private var captureScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = AppDatabase.getInstance(applicationContext)
        repository = ConversationRepository(db)
        Log.i(TAG, "Serviço de acessibilidade conectado")

        // Inicia verificação periódica de capturas pendentes (a cada 30s)
        schedulePendingCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return

        // Quando uma janela de conversa abre, tenta capturar se há pendência
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isCapturing && !captureScheduled) {
                captureScheduled = true
                mainHandler.postDelayed({
                    captureScheduled = false
                    captureCurrentScreen()
                }, CAPTURE_DELAY_MS)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de acessibilidade interrompido")
    }

    // ─── Verificação periódica ────────────────────────────────────────

    private fun schedulePendingCheck() {
        scope.launch {
            while (true) {
                delay(30_000L) // Verifica a cada 30 segundos
                checkAndProcessPending()
            }
        }
    }

    private suspend fun checkAndProcessPending() {
        if (isCapturing) return // Já está processando

        val pending = repository.getNextPendingCapture() ?: return
        Log.i(TAG, "Processando pendência ID=${pending.id} para ${pending.normalizedNumber}")

        withContext(Dispatchers.Main) {
            openWhatsAppConversation(pending)
        }
    }

    // ─── Abre conversa no WhatsApp ────────────────────────────────────

    /**
     * Usa deep link do WhatsApp para abrir conversa diretamente.
     * Formato: https://wa.me/<número_sem_+>
     */
    private fun openWhatsAppConversation(pending: PendingCapture) {
        isCapturing = true
        currentPendingId = pending.id

        val numberClean = pending.normalizedNumber.removePrefix("+")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("https://wa.me/$numberClean")
            setPackage(pending.sourcePackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            applicationContext.startActivity(intent)
            Log.i(TAG, "Abrindo conversa com ${pending.normalizedNumber}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir WhatsApp", e)
            isCapturing = false
            scope.launch {
                repository.markPendingAsError(pending.id, e.message ?: "Erro desconhecido")
            }
        }
    }

    // ─── Captura da tela ─────────────────────────────────────────────

    private fun captureCurrentScreen() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow nulo, abortando captura")
            isCapturing = false
            return
        }

        scope.launch {
            try {
                val messages = extractMessagesFromTree(root)
                Log.i(TAG, "Capturadas ${messages.size} mensagens")

                if (messages.isNotEmpty() && currentPendingId >= 0) {
                    // Busca a pendência para obter o phoneId
                    val pending = repository.getPendingById(currentPendingId)
                    if (pending != null) {
                        repository.insertMessages(messages.map { msg ->
                            msg.copy(phoneId = pending.phoneId)
                        })
                        repository.markPendingAsProcessed(currentPendingId)
                        Log.i(TAG, "Mensagens salvas para pendência $currentPendingId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro na captura", e)
                repository.markPendingAsError(currentPendingId, e.message ?: "Erro")
            } finally {
                isCapturing = false
                currentPendingId = -1
                // Volta ao home para não deixar o WhatsApp em foco
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    /**
     * Percorre a árvore de acessibilidade e extrai mensagens visíveis.
     * Filtra por viewId para encontrar bolhas de mensagem.
     */
    private fun extractMessagesFromTree(root: AccessibilityNodeInfo): List<CapturedMessage> {
        val messages = mutableListOf<CapturedMessage>()
        val now = Date()

        // Procura RecyclerView de conversa
        val conversationList = root.findAccessibilityNodeInfosByViewId(CONVERSATION_LIST_ID)
        val container = conversationList.firstOrNull() ?: root

        // Percorre todos os filhos
        traverseNode(container, messages, 0)

        return messages.take(MAX_MESSAGES_PER_CAPTURE)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        messages: MutableList<CapturedMessage>,
        depth: Int
    ) {
        if (depth > 20) return // Evita stack overflow em árvores profundas

        // Tenta extrair texto de mensagem
        val bodyNodes = node.findAccessibilityNodeInfosByViewId(MSG_BODY_ID)
        val timeNodes = node.findAccessibilityNodeInfosByViewId(MSG_TIME_ID)

        if (bodyNodes.isNotEmpty()) {
            val text = bodyNodes[0].text?.toString() ?: ""
            val time = timeNodes.firstOrNull()?.text?.toString() ?: ""
            val isIncoming = determineIfIncoming(node)

            if (text.isNotBlank()) {
                messages.add(
                    CapturedMessage(
                        phoneId = 0, // será preenchido depois
                        messageText = text,
                        messageTime = time,
                        capturedAt = Date(),
                        isIncoming = isIncoming,
                        hasImage = hasImageNode(node)
                    )
                )
            }
            return
        }

        // Desce para os filhos
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, messages, depth + 1)
                child.recycle()
            }
        }
    }

    /**
     * Heurística simples para determinar se é mensagem recebida ou enviada.
     * Mensagens enviadas geralmente têm indicador de status (✓✓) próximo.
     */
    private fun determineIfIncoming(node: AccessibilityNodeInfo): Boolean {
        // Procura por nó de status de entrega (presente apenas em mensagens enviadas)
        val statusNodes = node.findAccessibilityNodeInfosByViewId(
            "com.whatsapp:id/status"
        )
        return statusNodes.isEmpty()
    }

    private fun hasImageNode(node: AccessibilityNodeInfo): Boolean {
        val imageNodes = node.findAccessibilityNodeInfosByViewId(
            "com.whatsapp:id/media_image_holder"
        )
        return imageNodes.isNotEmpty()
    }
}
