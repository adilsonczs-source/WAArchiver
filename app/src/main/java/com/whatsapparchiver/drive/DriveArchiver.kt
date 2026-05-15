package com.whatsapparchiver.drive

import android.content.Context
import android.util.Log
import com.google.api.services.drive.Drive
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.MonitoredPhone
import com.whatsapparchiver.pdf.PdfGenerator
import com.whatsapparchiver.util.PhoneNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Date

/**
 * DriveArchiver — Integração bidirecional com Google Drive
 *
 * ARQUIVO A: WA_Archiver_numeros.json  (OJ Digital → Archiver)
 *   O OJ Digital publica este arquivo toda vez que envia WA.
 *   O Archiver lê, importa números novos e inicia monitoramento.
 *
 * ARQUIVO B: WA_Archiver_conversas.json (Archiver → OJ Digital)
 *   O Archiver publica as conversas capturadas aqui.
 *   O OJ Digital lê e vincula automaticamente às certidões.
 *
 * Ciclo automático: WorkManager chama syncCicloCompleto() a cada X min.
 */
class DriveArchiver(
    private val context: Context,
    private val driveUploader: GoogleDriveUploader
) {
    companion object {
        private const val TAG = "DriveArchiver"
        const val FILE_NUMEROS   = "WA_Archiver_numeros.json"
        const val FILE_CONVERSAS = "WA_Archiver_conversas.json"
        // Chave do SharedPreferences para timestamp da última leitura
        private const val PREF_LAST_NUMEROS_TS   = "wa_last_numeros_ts"
        private const val PREF_LAST_CONVERSAS_TS = "wa_last_conversas_ts"
    }

    private val prefs = context.getSharedPreferences("wa_archiver_drive", Context.MODE_PRIVATE)
    private val db    = AppDatabase.getInstance(context)
    private val repo  = ConversationRepository(db)

    // ─── Ciclo completo: lê números + publica conversas ──────────

    /**
     * Ponto de entrada chamado pelo WorkManager.
     * 1. Lê WA_Archiver_numeros.json → importa novos números
     * 2. Gera PDFs pendentes
     * 3. Publica WA_Archiver_conversas.json → OJ Digital lê
     *
     * @return Resumo do ciclo para log
     */
    suspend fun syncCicloCompleto(): SyncResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Iniciando ciclo Drive ↔ OJ Digital")
        val result = SyncResult()

        try {
            // PASSO 1: lê números novos do OJ Digital
            result.numerosImportados = lerNumerosDoOJDigital()

            // PASSO 2: gera PDFs das mensagens pendentes
            val generator = PdfGenerator(context, repo)
            result.pdfsGerados = generator.generatePendingPdfs()

            // PASSO 3: publica conversas para o OJ Digital
            result.conversasPublicadas = publicarConversasNoOJDigital()

            result.sucesso = true
            Log.i(TAG, "Ciclo concluído: $result")
        } catch (e: Exception) {
            result.erro = e.message ?: "Erro desconhecido"
            Log.e(TAG, "Erro no ciclo Drive", e)
        }

        result
    }

    // ─── PASSO 1: Lê números do OJ Digital ───────────────────────

    /**
     * Lê WA_Archiver_numeros.json do Drive.
     * Importa apenas números novos (não duplica).
     * @return Quantidade de números novos importados
     */
    private suspend fun lerNumerosDoOJDigital(): Int {
        val drive   = buildDrive() ?: return 0
        val fileId  = buscarArquivo(drive, FILE_NUMEROS) ?: return 0
        val content = baixarConteudo(drive, fileId) ?: return 0

        // Verifica se é mais recente que a última leitura
        val ultimaTs = prefs.getString(PREF_LAST_NUMEROS_TS, "") ?: ""
        val payload  = JSONObject(content)
        val publicadoEm = payload.optString("publicadoEm", "")
        if(ultimaTs.isNotEmpty() && publicadoEm.isNotEmpty() && publicadoEm <= ultimaTs) {
            Log.d(TAG, "Numeros.json sem novidade (ts=$publicadoEm)")
            return 0
        }

        val numeros = payload.optJSONArray("numeros") ?: return 0
        val existentes = repo.getEnabledPhonesSync().map { it.normalizedNumber }.toSet()
        var novos = 0

        for (i in 0 until numeros.length()) {
            val obj  = numeros.getJSONObject(i)
            val norm = PhoneNormalizer.normalize(obj.optString("normalizedNumber", ""))
                ?: continue
            if (norm in existentes) continue

            repo.insertPhone(MonitoredPhone(
                normalizedNumber = norm,
                displayLabel     = obj.optString("displayLabel", norm),
                isEnabled        = true
            ))
            novos++
            Log.i(TAG, "Número importado do Drive: $norm")
        }

        // Salva timestamp para não reimportar
        if (publicadoEm.isNotEmpty()) {
            prefs.edit().putString(PREF_LAST_NUMEROS_TS, publicadoEm).apply()
        }

        return novos
    }

    // ─── PASSO 3: Publica conversas para o OJ Digital ─────────────

    /**
     * Monta WA_Archiver_conversas.json com todas as mensagens
     * capturadas e publica no Drive para o OJ Digital ler.
     * @return Quantidade de conversas publicadas
     */
    private suspend fun publicarConversasNoOJDigital(): Int {
        val phones = repo.getEnabledPhonesSync()
        if (phones.isEmpty()) return 0

        val pdfsArray   = JSONArray()
        var totalConvs  = 0

        for (phone in phones) {
            val mensagens = repo.getUnexportedMessages(phone.id)
            if (mensagens.isEmpty()) continue

            // Monta objeto de conversa no formato que o OJ Digital espera
            val msgsArray = JSONArray()
            for (msg in mensagens) {
                msgsArray.put(JSONObject().apply {
                    put("messageText", msg.messageText)
                    put("messageTime", msg.messageTime)
                    put("isIncoming",  msg.isIncoming)
                    put("hasImage",    msg.hasImage)
                    put("capturedAt",  msg.capturedAt.toISOString())
                })
            }

            pdfsArray.put(JSONObject().apply {
                put("numero",              phone.normalizedNumber)
                put("normalizedNumber",    phone.normalizedNumber)
                put("displayLabel",        phone.displayLabel)
                put("processo",            "") // será enriquecido pelo OJ Digital
                put("geradoEm",            Date().toISOString())
                put("messageCount",        mensagens.size)
                put("mensagens",           msgsArray)
                put("origem",              "WhatsApp Archiver Android")
            })
            totalConvs++
        }

        if (totalConvs == 0) return 0

        val cfg = getCfgBasica()
        val payload = JSONObject().apply {
            put("versao",         "1.0-wa-archiver-android")
            put("publicadoEm",    Date().toISOString())
            put("device",         android.os.Build.MODEL)
            put("oficial",        cfg)
            put("totalConversas", totalConvs)
            put("pdfs",           pdfsArray)
            put("conversas",      pdfsArray) // compatibilidade com ambos os formatos
        }

        val drive  = buildDrive() ?: return 0
        val json   = payload.toString(2)
        val blob   = json.toByteArray()
        val existing = buscarArquivo(drive, FILE_CONVERSAS)

        if (existing != null) {
            // Atualiza arquivo existente
            val content = com.google.api.client.http.ByteArrayContent("application/json", blob)
            drive.files().update(existing, null, content).execute()
        } else {
            // Cria novo
            val meta = com.google.api.services.drive.model.File().apply {
                name     = FILE_CONVERSAS
                mimeType = "application/json"
            }
            val content = com.google.api.client.http.ByteArrayContent("application/json", blob)
            drive.files().create(meta, content).execute()
        }

        Log.i(TAG, "$totalConvs conversa(s) publicadas em $FILE_CONVERSAS")
        return totalConvs
    }

    // ─── Helpers Drive ────────────────────────────────────────────

    private fun buildDrive(): Drive? {
        return try {
            val account = driveUploader.getSignedInAccount() ?: return null
            val credential = com.google.android.gms.auth.GoogleAuthUtil
            // Reutiliza o buildDriveService do GoogleDriveUploader via reflexão
            // Na prática, use a mesma função já existente no GoogleDriveUploader
            null // Substituir por: driveUploader.buildDriveServicePublic()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao construir Drive service", e)
            null
        }
    }

    private fun buscarArquivo(drive: Drive, nome: String): String? {
        val result = drive.files().list()
            .setQ("name='$nome' and trashed=false")
            .setFields("files(id,modifiedTime)")
            .setSpaces("drive")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    private fun baixarConteudo(drive: Drive, fileId: String): String? {
        return try {
            val stream = drive.files().get(fileId).executeMediaAsInputStream()
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao baixar arquivo $fileId", e)
            null
        }
    }

    private fun getCfgBasica(): String {
        val prefs = context.getSharedPreferences("archiver_prefs", Context.MODE_PRIVATE)
        return prefs.getString("oficial_nome", "Oficial de Justiça") ?: "Oficial de Justiça"
    }

    private fun Date.toISOString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(this)
    }

    // ─── Modelo de resultado ──────────────────────────────────────

    data class SyncResult(
        var sucesso:             Boolean = false,
        var numerosImportados:   Int     = 0,
        var pdfsGerados:         Int     = 0,
        var conversasPublicadas: Int     = 0,
        var erro:                String? = null
    ) {
        override fun toString() = buildString {
            append("sucesso=$sucesso")
            append(", números=$numerosImportados")
            append(", PDFs=$pdfsGerados")
            append(", conversas=$conversasPublicadas")
            if (erro != null) append(", erro=$erro")
        }
    }
}
