package com.whatsapparchiver.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OcrProcessor — Extrai texto de imagens usando ML Kit (offline, sem internet).
 *
 * Dependência no build.gradle:
 *   implementation 'com.google.mlkit:text-recognition:16.0.1'
 *
 * Uso:
 *   val texto = OcrProcessor.extractText(bitmap)
 */
object OcrProcessor {

    private const val TAG = "OcrProcessor"

    // Inicializa reconhecedor de texto (lazy — só cria quando necessário)
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Extrai texto de um Bitmap usando ML Kit.
     * Operação assíncrona convertida para suspending function.
     *
     * @param bitmap Imagem a ser processada
     * @return Texto extraído ou string vazia se nada encontrado
     */
    suspend fun extractText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.textBlocks
                        .joinToString("\n") { block ->
                            block.lines.joinToString(" ") { it.text }
                        }
                        .trim()
                    Log.d(TAG, "OCR extraiu ${text.length} caracteres")
                    continuation.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha no OCR", e)
                    // Não falha a coroutine, retorna vazio
                    continuation.resume("")
                }

            continuation.invokeOnCancellation {
                // ML Kit não tem cancel direto; apenas não usa o resultado
            }
        }
    }

    /**
     * Extrai texto com informação de posição de cada bloco.
     * Útil para reconstruir layout da conversa.
     */
    suspend fun extractTextWithLayout(bitmap: Bitmap): List<TextBlock> {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.mapNotNull { block ->
                        val bounds = block.boundingBox ?: return@mapNotNull null
                        TextBlock(
                            text = block.text,
                            x = bounds.left,
                            y = bounds.top,
                            width = bounds.width(),
                            height = bounds.height(),
                            confidence = block.lines.flatMap { it.elements }
                                .mapNotNull { it.confidence }
                                .average()
                                .takeIf { !it.isNaN() }
                                ?.toFloat() ?: 0f
                        )
                    }
                    continuation.resume(blocks)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha no OCR com layout", e)
                    continuation.resume(emptyList())
                }
        }
    }

    /**
     * Libera recursos do reconhecedor (chamar ao encerrar o app).
     */
    fun close() {
        recognizer.close()
    }

    data class TextBlock(
        val text: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val confidence: Float
    )
}
