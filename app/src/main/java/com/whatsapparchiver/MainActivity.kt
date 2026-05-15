package com.whatsapparchiver

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.MonitoredPhone
import com.whatsapparchiver.databinding.ActivityMainBinding
import com.whatsapparchiver.pdf.PdfGenerator
import com.whatsapparchiver.service.WhatsAppAccessibilityService
import com.whatsapparchiver.util.PhoneNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MainActivity — Tela principal do WhatsApp Archiver
 * Controla permissões, lista de números monitorados e histórico de PDFs
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConversationRepository
    private lateinit var phoneAdapter: MonitoredPhoneAdapter
    private lateinit var pdfAdapter: PdfHistoryAdapter

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializa banco de dados e repositório
        val db = AppDatabase.getInstance(this)
        repository = ConversationRepository(db)

        setupRecyclerViews()
        setupButtons()
        checkAndRequestPermissions()
        observeData()
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    // ─── Setup UI ────────────────────────────────────────────────────

    private fun setupRecyclerViews() {
        // Lista de números monitorados
        phoneAdapter = MonitoredPhoneAdapter(
            onToggle = { phone, enabled ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.setPhoneEnabled(phone.id, enabled)
                }
            },
            onDelete = { phone -> confirmDeletePhone(phone) }
        )
        binding.rvPhones.layoutManager = LinearLayoutManager(this)
        binding.rvPhones.adapter = phoneAdapter

        // Histórico de PDFs
        pdfAdapter = PdfHistoryAdapter(
            onOpen = { pdfPath -> openPdf(pdfPath) },
            onShare = { pdfPath -> sharePdf(pdfPath) }
        )
        binding.rvPdfHistory.layoutManager = LinearLayoutManager(this)
        binding.rvPdfHistory.adapter = pdfAdapter
    }

    private fun setupButtons() {
        // Botão adicionar número
        binding.btnAddPhone.setOnClickListener {
            showAddPhoneDialog()
        }

        // Botão processar agora (forçar captura manual)
        binding.btnProcessNow.setOnClickListener {
            triggerManualProcess()
        }

        // Botão configurações de serviço de notificação
        binding.btnNotifSettings.setOnClickListener {
            openNotificationListenerSettings()
        }

        // Botão configurações de acessibilidade
        binding.btnAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        // Aviso de privacidade
        binding.btnPrivacyInfo.setOnClickListener {
            showPrivacyNotice()
        }
    }

    // ─── Observar dados em tempo real ────────────────────────────────

    private fun observeData() {
        // Números monitorados (LiveData do Room)
        repository.getAllPhones().observe(this) { phones ->
            phoneAdapter.submitList(phones)
            binding.tvEmptyPhones.visibility =
                if (phones.isEmpty()) View.VISIBLE else View.GONE
        }

        // Histórico de PDFs gerados
        repository.getAllGeneratedPdfs().observe(this) { pdfs ->
            pdfAdapter.submitList(pdfs)
            binding.tvEmptyPdfs.visibility =
                if (pdfs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ─── Status dos serviços ─────────────────────────────────────────

    private fun updateServiceStatus() {
        val notifEnabled = isNotificationListenerEnabled()
        val accessEnabled = isAccessibilityServiceEnabled()

        binding.statusNotification.setImageResource(
            if (notifEnabled) R.drawable.ic_check_green else R.drawable.ic_warning_red
        )
        binding.statusAccessibility.setImageResource(
            if (accessEnabled) R.drawable.ic_check_green else R.drawable.ic_warning_red
        )
        binding.tvStatusSummary.text = when {
            notifEnabled && accessEnabled -> "✅ Monitoramento ativo"
            !notifEnabled -> "⚠️ Ative o Listener de Notificações"
            else -> "⚠️ Ative o Serviço de Acessibilidade"
        }
        binding.btnProcessNow.isEnabled = notifEnabled && accessEnabled
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${WhatsAppAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any {
            it.equals(service, ignoreCase = true)
        }
    }

    // ─── Diálogos ────────────────────────────────────────────────────

    private fun showAddPhoneDialog() {
        val dialog = AddPhoneDialogFragment { rawNumber, label ->
            val normalized = PhoneNormalizer.normalize(rawNumber)
            if (normalized == null) {
                showToast("Número inválido: $rawNumber")
                return@AddPhoneDialogFragment
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val phone = MonitoredPhone(
                    normalizedNumber = normalized,
                    displayLabel = label.ifBlank { normalized },
                    isEnabled = true
                )
                repository.insertPhone(phone)
                withContext(Dispatchers.Main) {
                    showToast("Número adicionado: $normalized")
                }
            }
        }
        dialog.show(supportFragmentManager, "AddPhone")
    }

    private fun confirmDeletePhone(phone: MonitoredPhone) {
        AlertDialog.Builder(this)
            .setTitle("Remover número")
            .setMessage("Deseja remover ${phone.displayLabel} da lista?")
            .setPositiveButton("Remover") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.deletePhone(phone)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPrivacyNotice() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Aviso de Privacidade")
            .setMessage(
                "Este aplicativo deve ser usado EXCLUSIVAMENTE pelo proprietário " +
                "do dispositivo para arquivar suas próprias conversas.\n\n" +
                "• Não monitora conversas fora da lista autorizada\n" +
                "• Não envia dados para servidores externos sem permissão\n" +
                "• A chave de API (se usada) fica armazenada localmente\n\n" +
                "O uso indevido para monitorar terceiros sem consentimento " +
                "é ilegal e viola a LGPD."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    // ─── Ações de PDF ────────────────────────────────────────────────

    private fun openPdf(path: String) {
        val file = File(path)
        if (!file.exists()) { showToast("Arquivo não encontrado"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.provider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Abrir PDF"))
    }

    private fun sharePdf(path: String) {
        val file = File(path)
        if (!file.exists()) { showToast("Arquivo não encontrado"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.provider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar PDF"))
    }

    // ─── Processamento manual ────────────────────────────────────────

    private fun triggerManualProcess() {
        binding.btnProcessNow.isEnabled = false
        binding.btnProcessNow.text = "Processando..."
        lifecycleScope.launch {
            try {
                val generator = PdfGenerator(this@MainActivity, repository)
                val count = withContext(Dispatchers.IO) {
                    generator.generatePendingPdfs()
                }
                showToast("$count PDF(s) gerado(s) com sucesso")
            } catch (e: Exception) {
                showToast("Erro: ${e.message}")
            } finally {
                binding.btnProcessNow.isEnabled = true
                binding.btnProcessNow.text = "Processar agora"
            }
        }
    }

    // ─── Permissões ──────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_STORAGE)
        }
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
