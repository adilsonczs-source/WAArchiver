package com.whatsapparchiver.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.GeneratedPdf
import com.whatsapparchiver.drive.GoogleDriveUploader
import com.whatsapparchiver.pdf.PdfGenerator
import java.util.Date
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════
// WORKERS (tarefas em background com WorkManager)
// ══════════════════════════════════════════════════════

/**
 * PdfGenerationWorker — Gera PDFs de mensagens pendentes.
 *
 * Executado periodicamente ou sob demanda.
 * Respeita restrições de rede e bateria configuradas pelo usuário.
 */
class PdfGenerationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "PdfGenerationWorker"
        const val WORK_NAME = "pdf_generation_periodic"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_CHARGING_ONLY = "charging_only"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "PdfGenerationWorker iniciado")

        return try {
            // Verifica restrições opcionais do usuário
            val wifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, false)
            val chargingOnly = inputData.getBoolean(KEY_CHARGING_ONLY, false)

            if (wifiOnly && !isOnWifi()) {
                Log.i(TAG, "Sem Wi-Fi — adiando geração de PDF")
                return Result.retry()
            }
            if (chargingOnly && !isCharging()) {
                Log.i(TAG, "Sem carregador — adiando geração de PDF")
                return Result.retry()
            }

            val db = AppDatabase.getInstance(applicationContext)
            val repository = ConversationRepository(db)
            val generator = PdfGenerator(applicationContext, repository)

            val count = generator.generatePendingPdfs()
            Log.i(TAG, "$count PDF(s) gerado(s)")

            // Enfileira upload no Drive (se logado)
            val uploader = GoogleDriveUploader(applicationContext)
            if (uploader.isSignedIn()) {
                WorkerScheduler.enqueueDriveUpload(applicationContext)
            }

            Result.success(
                workDataOf("pdfs_generated" to count)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro no PdfGenerationWorker", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isCharging(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }
}

/**
 * DriveUploadWorker — Faz upload de PDFs pendentes para o Google Drive.
 */
class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DriveUploadWorker"
        const val WORK_NAME = "drive_upload_oneshot"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "DriveUploadWorker iniciado")

        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val repository = ConversationRepository(db)
            val uploader = GoogleDriveUploader(applicationContext)

            if (!uploader.isSignedIn()) {
                Log.i(TAG, "Usuário não logado no Drive — pulando upload")
                return Result.success()
            }

            // Busca PDFs ainda não enviados para o Drive
            val pendingPdfs = repository.getPdfsNotUploadedToDrive()
            Log.i(TAG, "${pendingPdfs.size} PDF(s) para enviar ao Drive")

            var successCount = 0
            for (pdf in pendingPdfs) {
                val file = java.io.File(pdf.filePath)
                if (!file.exists()) continue

                val fileId = uploader.uploadPdf(
                    localFile = file,
                    subfolder = pdf.displayLabel  // Separa por contato no Drive
                )

                if (fileId != null) {
                    repository.markPdfAsUploaded(pdf.id)
                    successCount++
                    Log.i(TAG, "Upload OK: ${pdf.filePath} → Drive ID $fileId")
                }
            }

            Log.i(TAG, "$successCount/${pendingPdfs.size} PDFs enviados ao Drive")
            Result.success(workDataOf("uploaded" to successCount))

        } catch (e: Exception) {
            Log.e(TAG, "Erro no DriveUploadWorker", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

/**
 * CleanupWorker — Limpeza semanal de logs e pendências antigas.
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CleanupWorker"
        const val WORK_NAME = "cleanup_weekly"
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val repository = ConversationRepository(db)

            // Remove pendências processadas com mais de 7 dias
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            repository.cleanOldPending(cutoff)
            Log.i(TAG, "Limpeza de registros antigos concluída")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erro no CleanupWorker", e)
            Result.failure()
        }
    }
}

// ══════════════════════════════════════════════════════
// SCHEDULER — Gerencia todos os agendamentos
// ══════════════════════════════════════════════════════

/**
 * WorkerScheduler — Ponto central para agendar e cancelar Workers.
 *
 * USO típico (chamar no onCreate da MainActivity):
 *   WorkerScheduler.initialize(context, intervalMinutes = 30)
 *
 * Para processar imediatamente (botão "Processar agora"):
 *   WorkerScheduler.enqueueImmediate(context)
 */
object WorkerScheduler {

    private const val TAG = "WorkerScheduler"

    /**
     * Inicializa o agendamento periódico de geração de PDF.
     *
     * @param context Context
     * @param intervalMinutes Intervalo em minutos (mínimo 15 por restrição do WorkManager)
     * @param wifiOnly Processar apenas com Wi-Fi
     * @param chargingOnly Processar apenas quando carregando
     */
    fun initialize(
        context: Context,
        intervalMinutes: Long = 30,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false
    ) {
        val safeInterval = maxOf(intervalMinutes, 15L) // WorkManager mínimo = 15 min

        // Constraints de hardware/rede
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED
            )
            .setRequiresCharging(chargingOnly)
            .build()

        val inputData = workDataOf(
            PdfGenerationWorker.KEY_WIFI_ONLY to wifiOnly,
            PdfGenerationWorker.KEY_CHARGING_ONLY to chargingOnly
        )

        val request = PeriodicWorkRequestBuilder<PdfGenerationWorker>(
            safeInterval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("pdf_generation")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PdfGenerationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // Atualiza intervalo se já existe
            request
        )

        Log.i(TAG, "Agendamento periódico: a cada $safeInterval min (wifi=$wifiOnly, charging=$chargingOnly)")

        // Agendamento de limpeza semanal
        scheduleWeeklyCleanup(context)
    }

    /**
     * Enfileira geração de PDF imediata (botão "Processar agora").
     */
    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<PdfGenerationWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("pdf_immediate")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "pdf_immediate_oneshot",
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.i(TAG, "Geração imediata de PDF enfileirada")
    }

    /**
     * Enfileira upload para o Drive (chamado após geração de PDF).
     */
    fun enqueueDriveUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag("drive_upload")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DriveUploadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP, // Não duplica se já pendente
            request
        )
        Log.i(TAG, "Upload para Drive enfileirado")
    }

    /**
     * Cancela todos os agendamentos (usado ao desativar o app).
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PdfGenerationWorker.WORK_NAME)
            cancelUniqueWork(DriveUploadWorker.WORK_NAME)
            cancelUniqueWork(CleanupWorker.WORK_NAME)
            cancelAllWorkByTag("pdf_generation")
            cancelAllWorkByTag("drive_upload")
        }
        Log.i(TAG, "Todos os Workers cancelados")
    }

    /**
     * Retorna true se o worker periódico está ativo.
     */
    fun isScheduled(context: Context): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PdfGenerationWorker.WORK_NAME)
            .get()
        return infos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
    }

    private fun scheduleWeeklyCleanup(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag("cleanup")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

// ══════════════════════════════════════════════════════
// BOOT RECEIVER — Reinicia agendamentos após reboot
// ══════════════════════════════════════════════════════

/**
 * BootReceiver — Reinicia o WorkManager após reinicialização do dispositivo.
 * Localização: app/src/main/java/com/whatsapparchiver/receiver/BootReceiver.kt
 *
 * (Coloque em arquivo separado se preferir — aqui está junto para compactar)
 */
// class BootReceiver : BroadcastReceiver() {
//     override fun onReceive(context: Context, intent: Intent) {
//         if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
//             intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
//             Log.i("BootReceiver", "Reiniciando Workers após boot")
//             val prefs = context.getSharedPreferences("archiver_prefs", Context.MODE_PRIVATE)
//             WorkerScheduler.initialize(
//                 context,
//                 intervalMinutes = prefs.getLong("interval_minutes", 30),
//                 wifiOnly = prefs.getBoolean("wifi_only", false),
//                 chargingOnly = prefs.getBoolean("charging_only", false)
//             )
//         }
//     }
// }
