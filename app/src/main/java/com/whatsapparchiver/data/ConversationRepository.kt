package com.whatsapparchiver.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import java.util.Date

// ══════════════════════════════════════════════════════
// ENTIDADES (Tabelas do banco Room)
// ══════════════════════════════════════════════════════

/**
 * Número de telefone monitorado
 */
@Entity(tableName = "monitored_phones")
data class MonitoredPhone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedNumber: String,  // E.164: +5565999999999
    val displayLabel: String,       // Nome ou número formatado
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Captura pendente: notificação recebida, aguardando processamento pelo Accessibility
 */
@Entity(tableName = "pending_captures")
data class PendingCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneId: Long,
    val normalizedNumber: String,
    val contactName: String,
    val notificationText: String,
    val notificationTimestamp: Long,
    val sourcePackage: String,      // com.whatsapp ou com.whatsapp.w4b
    val processed: Boolean = false,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Mensagem capturada da tela do WhatsApp
 */
@Entity(tableName = "captured_messages")
data class CapturedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneId: Long,
    val messageText: String,
    val messageTime: String,        // Hora exibida no WhatsApp (ex: "14:32")
    val capturedAt: Date,
    val isIncoming: Boolean,        // true = recebida, false = enviada
    val hasImage: Boolean = false,
    val imagePath: String? = null,  // Caminho local da imagem, se capturada
    val ocrText: String? = null,    // Texto extraído via OCR da imagem
    val exported: Boolean = false   // true = já incluído em PDF
)

/**
 * Registro de PDFs gerados
 */
@Entity(tableName = "generated_pdfs")
data class GeneratedPdf(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneId: Long,
    val normalizedNumber: String,
    val displayLabel: String,
    val filePath: String,
    val messageCount: Int,
    val generatedAt: Date,
    val uploadedToDrive: Boolean = false
)

// ══════════════════════════════════════════════════════
// TYPE CONVERTERS
// ══════════════════════════════════════════════════════

class Converters {
    @TypeConverter fun fromDate(date: Date?): Long? = date?.time
    @TypeConverter fun toDate(millis: Long?): Date? = millis?.let { Date(it) }
}

// ══════════════════════════════════════════════════════
// DAOs (Data Access Objects)
// ══════════════════════════════════════════════════════

@Dao
interface MonitoredPhoneDao {
    @Query("SELECT * FROM monitored_phones ORDER BY createdAt DESC")
    fun getAllLive(): LiveData<List<MonitoredPhone>>

    @Query("SELECT * FROM monitored_phones WHERE isEnabled = 1")
    suspend fun getEnabled(): List<MonitoredPhone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(phone: MonitoredPhone): Long

    @Delete
    suspend fun delete(phone: MonitoredPhone)

    @Query("UPDATE monitored_phones SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface PendingCaptureDao {
    @Insert
    suspend fun insert(capture: PendingCapture): Long

    @Query("SELECT * FROM pending_captures WHERE processed = 0 ORDER BY notificationTimestamp ASC LIMIT 1")
    suspend fun getNext(): PendingCapture?

    @Query("SELECT * FROM pending_captures WHERE id = :id")
    suspend fun getById(id: Long): PendingCapture?

    @Query("UPDATE pending_captures SET processed = 1 WHERE id = :id")
    suspend fun markProcessed(id: Long)

    @Query("UPDATE pending_captures SET processed = 1, errorMessage = :error WHERE id = :id")
    suspend fun markError(id: Long, error: String)

    @Query("DELETE FROM pending_captures WHERE processed = 1 AND createdAt < :before")
    suspend fun cleanOld(before: Long)
}

@Dao
interface CapturedMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<CapturedMessage>)

    @Query("SELECT * FROM captured_messages WHERE phoneId = :phoneId AND exported = 0 ORDER BY capturedAt ASC")
    suspend fun getUnexported(phoneId: Long): List<CapturedMessage>

    @Query("UPDATE captured_messages SET exported = 1 WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM captured_messages WHERE phoneId = :phoneId")
    suspend fun countForPhone(phoneId: Long): Int
}

@Dao
interface GeneratedPdfDao {
    @Query("SELECT * FROM generated_pdfs ORDER BY generatedAt DESC")
    fun getAllLive(): LiveData<List<GeneratedPdf>>

    @Insert
    suspend fun insert(pdf: GeneratedPdf): Long
}

// ══════════════════════════════════════════════════════
// DATABASE
// ══════════════════════════════════════════════════════

@Database(
    entities = [
        MonitoredPhone::class,
        PendingCapture::class,
        CapturedMessage::class,
        GeneratedPdf::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun monitoredPhoneDao(): MonitoredPhoneDao
    abstract fun pendingCaptureDao(): PendingCaptureDao
    abstract fun capturedMessageDao(): CapturedMessageDao
    abstract fun generatedPdfDao(): GeneratedPdfDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whatsapp_archiver.db"
                )
                .fallbackToDestructiveMigration() // Em produção, use migrations reais
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// REPOSITORY
// ══════════════════════════════════════════════════════

/**
 * ConversationRepository — Camada de abstração entre UI/Services e banco de dados.
 * Todos os métodos de escrita são suspend (executar em background).
 */
class ConversationRepository(private val db: AppDatabase) {

    // ─── Phones ────────────────────────────────────────

    fun getAllPhones() = db.monitoredPhoneDao().getAllLive()

    suspend fun getEnabledPhonesSync() = db.monitoredPhoneDao().getEnabled()

    suspend fun insertPhone(phone: MonitoredPhone) =
        db.monitoredPhoneDao().insert(phone)

    suspend fun deletePhone(phone: MonitoredPhone) =
        db.monitoredPhoneDao().delete(phone)

    suspend fun setPhoneEnabled(id: Long, enabled: Boolean) =
        db.monitoredPhoneDao().setEnabled(id, enabled)

    // ─── Pending Captures ──────────────────────────────

    suspend fun insertPendingCapture(capture: PendingCapture) =
        db.pendingCaptureDao().insert(capture)

    suspend fun getNextPendingCapture() =
        db.pendingCaptureDao().getNext()

    suspend fun getPendingById(id: Long) =
        db.pendingCaptureDao().getById(id)

    suspend fun markPendingAsProcessed(id: Long) =
        db.pendingCaptureDao().markProcessed(id)

    suspend fun markPendingAsError(id: Long, error: String) =
        db.pendingCaptureDao().markError(id, error)

    // ─── Messages ──────────────────────────────────────

    suspend fun insertMessages(messages: List<CapturedMessage>) =
        db.capturedMessageDao().insertAll(messages)

    suspend fun getUnexportedMessages(phoneId: Long) =
        db.capturedMessageDao().getUnexported(phoneId)

    suspend fun markMessagesAsExported(ids: List<Long>) =
        db.capturedMessageDao().markExported(ids)

    // ─── PDFs ──────────────────────────────────────────

    fun getAllGeneratedPdfs() = db.generatedPdfDao().getAllLive()

    suspend fun insertGeneratedPdf(pdf: GeneratedPdf) =
        db.generatedPdfDao().insert(pdf)
}
