package com.whatsapparchiver.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.whatsapparchiver.drive.DriveArchiver
import com.whatsapparchiver.drive.GoogleDriveUploader
import java.util.concurrent.TimeUnit

/**
 * DriveSyncWorker — Executa o ciclo Drive ↔ OJ Digital em background.
 *
 * Agendado pelo WorkerScheduler a cada 2 minutos (configurável).
 * Quando roda:
 *   1. Lê WA_Archiver_numeros.json → importa novos números
 *   2. Gera PDFs pendentes
 *   3. Publica WA_Archiver_conversas.json → OJ Digital lê
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DriveSyncWorker"
        const val WORK_NAME  = "drive_sync_bidirectional"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"

        /**
         * Agenda o worker periódico.
         * @param intervalMinutes Intervalo em minutos (mínimo 1)
         */
        fun agendar(context: Context, intervalMinutes: Long = 2) {
            val safe = maxOf(intervalMinutes, 1L)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(
                safe, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_INTERVAL_MINUTES to safe))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag("drive_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Drive sync agendado: a cada $safe min")
        }

        fun cancelar(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Drive sync cancelado")
        }

        fun isAgendado(context: Context): Boolean {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME).get()
            return infos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "DriveSyncWorker iniciado")

        val uploader = GoogleDriveUploader(applicationContext)
        if (!uploader.isSignedIn()) {
            Log.i(TAG, "Usuário não logado no Drive — pulando ciclo")
            return Result.success() // Não é erro, só não está conectado
        }

        return try {
            val archiver = DriveArchiver(applicationContext, uploader)
            val result   = archiver.syncCicloCompleto()

            Log.i(TAG, "Ciclo concluído: $result")

            if (result.sucesso) {
                Result.success(workDataOf(
                    "numeros_importados"   to result.numerosImportados,
                    "pdfs_gerados"         to result.pdfsGerados,
                    "conversas_publicadas" to result.conversasPublicadas
                ))
            } else {
                if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf("erro" to result.erro))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no DriveSyncWorker", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
