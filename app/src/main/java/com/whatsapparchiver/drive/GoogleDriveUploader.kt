package com.whatsapparchiver.drive

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.client.http.FileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GoogleDriveUploader — Faz upload de PDFs para o Google Drive do usuário.
 *
 * SETUP necessário:
 * 1. Criar projeto no Google Cloud Console: https://console.cloud.google.com
 * 2. Ativar "Google Drive API"
 * 3. Criar credencial OAuth 2.0 do tipo "Android" com o SHA-1 do keystore
 * 4. Adicionar o client_id no google-services.json
 *
 * DEPENDÊNCIAS (já no build.gradle):
 *   implementation 'com.google.android.gms:play-services-auth:21.0.0'
 *   implementation 'com.google.api-client:google-api-client-android:2.2.0'
 *   implementation 'com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0'
 *
 * PERMISSÃO no Manifest:
 *   <uses-permission android:name="android.permission.INTERNET"/>
 *   (já incluída implicitamente pelo play-services-auth)
 *
 * USO:
 *   val uploader = GoogleDriveUploader(context)
 *   uploader.signIn(activity) // abre tela de login Google
 *   val fileId = uploader.uploadPdf(File("/path/to/arquivo.pdf"), "WhatsAppPDF")
 */
class GoogleDriveUploader(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveUploader"
        private const val APP_FOLDER_NAME = "WhatsApp Archiver"
        private const val MIME_PDF = "application/pdf"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"

        // ID da pasta no Drive (cache para não buscar toda vez)
        private var cachedFolderId: String? = null
    }

    // ─── Login Google ──────────────────────────────────────────────────

    /**
     * Retorna as opções de login Google com escopo do Drive.
     * Usar em Activity: startActivityForResult(getSignInIntent(this), RC_SIGN_IN)
     */
    fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE)) // Acesso apenas a arquivos criados pelo app
            .build()
    }

    /**
     * Verifica se o usuário já está logado com o Drive.
     */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(
            account, Scope(DriveScopes.DRIVE_FILE)
        )
    }

    /**
     * Retorna a conta Google logada, ou null.
     */
    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    // ─── Operações no Drive ────────────────────────────────────────────

    /**
     * Faz upload de um PDF para a pasta "WhatsApp Archiver" no Drive.
     *
     * @param localFile Arquivo PDF local
     * @param subfolder Subpasta opcional (ex: nome do contato)
     * @return ID do arquivo no Drive, ou null em caso de erro
     */
    suspend fun uploadPdf(localFile: java.io.File, subfolder: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = buildDriveService() ?: return@withContext null

                // Garante que a pasta pai existe no Drive
                val parentFolderId = getOrCreateFolder(driveService, APP_FOLDER_NAME, null)
                    ?: return@withContext null

                // Se tiver subpasta, cria dentro do APP_FOLDER_NAME
                val targetFolderId = if (subfolder != null) {
                    getOrCreateFolder(driveService, subfolder, parentFolderId)
                        ?: parentFolderId
                } else {
                    parentFolderId
                }

                // Metadata do arquivo
                val fileMetadata = DriveFile().apply {
                    name = localFile.name
                    parents = listOf(targetFolderId)
                    mimeType = MIME_PDF
                }

                // Conteúdo
                val mediaContent = FileContent(MIME_PDF, localFile)

                Log.i(TAG, "Iniciando upload: ${localFile.name} → Drive/$APP_FOLDER_NAME")

                val uploaded = driveService.files()
                    .create(fileMetadata, mediaContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                Log.i(TAG, "Upload concluído! ID=${uploaded.id}, link=${uploaded.webViewLink}")
                uploaded.id

            } catch (e: Exception) {
                Log.e(TAG, "Erro no upload para o Drive", e)
                null
            }
        }
    }

    /**
     * Lista os PDFs na pasta do app no Drive.
     * Retorna lista de pares (nome, id).
     */
    suspend fun listPdfs(): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = buildDriveService() ?: return@withContext emptyList()
                val folderId = getOrCreateFolder(driveService, APP_FOLDER_NAME, null)
                    ?: return@withContext emptyList()

                val result = driveService.files().list()
                    .setQ("'$folderId' in parents and mimeType='$MIME_PDF' and trashed=false")
                    .setFields("files(id, name, createdTime)")
                    .setOrderBy("createdTime desc")
                    .execute()

                result.files.map { it.name to it.id }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao listar PDFs no Drive", e)
                emptyList()
            }
        }
    }

    /**
     * Deleta um arquivo do Drive pelo ID.
     */
    suspend fun deleteFile(fileId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = buildDriveService() ?: return@withContext false
                driveService.files().delete(fileId).execute()
                Log.i(TAG, "Arquivo $fileId deletado do Drive")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao deletar do Drive", e)
                false
            }
        }
    }

    // ─── Helpers privados ──────────────────────────────────────────────

    /**
     * Constrói o serviço autenticado do Google Drive.
     */
    private fun buildDriveService(): Drive? {
        val account = getSignedInAccount() ?: run {
            Log.e(TAG, "Usuário não está logado no Google")
            return null
        }

        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = Account(account.email, "com.google")
        }

        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("WhatsApp Archiver")
            .build()
    }

    /**
     * Busca ou cria uma pasta no Drive.
     * Usa cache em memória para evitar chamadas repetidas.
     *
     * @param service Drive service autenticado
     * @param folderName Nome da pasta
     * @param parentId ID da pasta pai (null = raiz do Drive)
     * @return ID da pasta
     */
    private fun getOrCreateFolder(
        service: Drive,
        folderName: String,
        parentId: String?
    ): String? {
        // Usa cache para a pasta raiz do app
        if (parentId == null && folderName == APP_FOLDER_NAME) {
            cachedFolderId?.let { return it }
        }

        return try {
            // Busca pasta existente
            val parentQuery = if (parentId != null) "'$parentId' in parents and " else ""
            val query = "${parentQuery}mimeType='$MIME_FOLDER' and name='$folderName' and trashed=false"

            val result = service.files().list()
                .setQ(query)
                .setFields("files(id)")
                .setSpaces("drive")
                .execute()

            val existingId = result.files.firstOrNull()?.id

            if (existingId != null) {
                if (parentId == null) cachedFolderId = existingId
                Log.d(TAG, "Pasta '$folderName' encontrada: $existingId")
                existingId
            } else {
                // Cria nova pasta
                val metadata = DriveFile().apply {
                    name = folderName
                    mimeType = MIME_FOLDER
                    if (parentId != null) parents = listOf(parentId)
                }
                val created = service.files().create(metadata)
                    .setFields("id")
                    .execute()

                if (parentId == null) cachedFolderId = created.id
                Log.i(TAG, "Pasta '$folderName' criada: ${created.id}")
                created.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar/criar pasta '$folderName'", e)
            null
        }
    }
}
