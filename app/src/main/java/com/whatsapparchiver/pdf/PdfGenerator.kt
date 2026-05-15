package com.whatsapparchiver.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import com.whatsapparchiver.data.CapturedMessage
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.data.GeneratedPdf
import com.whatsapparchiver.data.MonitoredPhone
import com.whatsapparchiver.util.PhoneNormalizer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PdfGenerator — Gera PDF individual por número/contato e data.
 *
 * Nome do arquivo: WhatsApp_+5565999999999_2026-05-14.pdf
 *
 * Conteúdo do PDF:
 *   - Cabeçalho com número/contato e data de geração
 *   - Mensagens em ordem cronológica
 *   - Indicação visual de enviada/recebida
 *   - Horário de cada mensagem
 *   - Imagens incorporadas (quando disponível)
 *
 * Salvo em: /Documents/WhatsAppPDF/
 */
class PdfGenerator(
    private val context: Context,
    private val repository: ConversationRepository
) {

    companion object {
        private const val TAG = "PdfGenerator"
        private const val PAGE_WIDTH = 595   // A4 em pontos (72dpi)
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40f
        private const val LINE_HEIGHT = 22f
        private const val BUBBLE_PADDING = 8f

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val DISPLAY_FORMAT = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }

    // ─── Cores ────────────────────────────────────────────────────────

    private val colorHeader = Color.parseColor("#075E54")   // Verde WhatsApp
    private val colorIncoming = Color.parseColor("#FFFFFF") // Balão recebida
    private val colorOutgoing = Color.parseColor("#DCF8C6") // Balão enviada
    private val colorBubbleBorder = Color.parseColor("#CCCCCC")
    private val colorTimestamp = Color.parseColor("#888888")
    private val colorBackground = Color.parseColor("#ECE5DD") // Fundo bege WhatsApp
    private val colorText = Color.parseColor("#111111")
    private val colorHeaderText = Color.WHITE

    // ─── Pinéis ───────────────────────────────────────────────────────

    private val paintBackground = Paint().apply { color = colorBackground }
    private val paintHeader = Paint().apply { color = colorHeader }
    private val paintIncoming = Paint().apply {
        color = colorIncoming
        style = Paint.Style.FILL
    }
    private val paintOutgoing = Paint().apply {
        color = colorOutgoing
        style = Paint.Style.FILL
    }
    private val paintBorder = Paint().apply {
        color = colorBubbleBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintText = Paint().apply {
        color = colorText
        textSize = 11f
        isAntiAlias = true
    }
    private val paintBold = Paint().apply {
        color = colorText
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val paintTimestamp = Paint().apply {
        color = colorTimestamp
        textSize = 9f
        isAntiAlias = true
    }
    private val paintHeaderText = Paint().apply {
        color = colorHeaderText
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // ─── Ponto de entrada público ─────────────────────────────────────

    /**
     * Gera PDFs para todos os números com mensagens não exportadas.
     * Retorna quantos PDFs foram gerados.
     */
    suspend fun generatePendingPdfs(): Int {
        val phones = repository.getEnabledPhonesSync()
        var count = 0
        for (phone in phones) {
            val messages = repository.getUnexportedMessages(phone.id)
            if (messages.isNotEmpty()) {
                val path = generatePdf(phone, messages)
                if (path != null) {
                    repository.insertGeneratedPdf(
                        GeneratedPdf(
                            phoneId = phone.id,
                            normalizedNumber = phone.normalizedNumber,
                            displayLabel = phone.displayLabel,
                            filePath = path,
                            messageCount = messages.size,
                            generatedAt = Date()
                        )
                    )
                    repository.markMessagesAsExported(messages.map { it.id })
                    count++
                    Log.i(TAG, "PDF gerado: $path")
                }
            }
        }
        return count
    }

    /**
     * Gera PDF para um número específico numa data específica.
     */
    fun generatePdf(phone: MonitoredPhone, messages: List<CapturedMessage>): String? {
        if (messages.isEmpty()) return null

        val dateStr = DATE_FORMAT.format(Date())
        val filename = "WhatsApp_${phone.normalizedNumber}_$dateStr.pdf"
        val outputDir = getOutputDir() ?: return null
        val outputFile = File(outputDir, filename)

        val document = PdfDocument()
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = drawPageHeader(canvas, phone, pageNumber)

        for (message in messages.sortedBy { it.capturedAt }) {
            val lineCount = estimateLines(message.messageText)
            val neededHeight = BUBBLE_PADDING * 2 + LINE_HEIGHT * lineCount + 30f

            // Inicia nova página se necessário
            if (y + neededHeight > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                y = drawPageHeader(canvas, phone, pageNumber)
            }

            y = drawMessageBubble(canvas, message, y)
            y += 6f // Espaço entre mensagens
        }

        // Rodapé na última página
        drawFooter(canvas, messages.size)
        document.finishPage(page)

        try {
            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
            }
            document.close()
            return outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar PDF", e)
            document.close()
            return null
        }
    }

    // ─── Desenho ──────────────────────────────────────────────────────

    /**
     * Desenha o cabeçalho verde com informações do contato.
     * Retorna a posição Y após o cabeçalho.
     */
    private fun drawPageHeader(canvas: Canvas, phone: MonitoredPhone, page: Int): Float {
        // Fundo da página
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), paintBackground)

        // Barra de cabeçalho
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 70f, paintHeader)

        // Ícone placeholder (quadrado verde escuro)
        val iconPaint = Paint().apply { color = Color.parseColor("#054D44"); isAntiAlias = true }
        canvas.drawRoundRect(
            android.graphics.RectF(MARGIN, 10f, MARGIN + 50f, 60f),
            8f, 8f, iconPaint
        )
        canvas.drawText("WA", MARGIN + 12f, 42f, paintHeaderText.apply { textSize = 16f })

        // Nome e número
        canvas.drawText(phone.displayLabel, MARGIN + 60f, 30f, paintHeaderText.apply { textSize = 14f })
        canvas.drawText(
            PhoneNormalizer.formatForDisplay(phone.normalizedNumber),
            MARGIN + 60f, 52f,
            paintHeaderText.apply { textSize = 11f; typeface = Typeface.DEFAULT }
        )

        // Data de geração (canto direito)
        val dateStr = "Gerado em: ${DISPLAY_FORMAT.format(Date())}"
        val dateWidth = paintTimestamp.measureText(dateStr)
        canvas.drawText(dateStr, PAGE_WIDTH - dateWidth - MARGIN, 30f, paintTimestamp.apply {
            color = Color.parseColor("#CCFFCC")
        })
        if (page > 1) {
            canvas.drawText("Página $page", PAGE_WIDTH - MARGIN - 50f, 52f, paintTimestamp.apply {
                color = Color.parseColor("#CCFFCC")
            })
        }

        return 85f // Y inicial das mensagens
    }

    /**
     * Desenha uma bolha de mensagem (estilo WhatsApp).
     * Retorna a posição Y após a bolha.
     */
    private fun drawMessageBubble(canvas: Canvas, msg: CapturedMessage, startY: Float): Float {
        val maxBubbleWidth = PAGE_WIDTH * 0.68f
        val lines = wrapText(msg.messageText, maxBubbleWidth - BUBBLE_PADDING * 2, paintText)
        val bubbleHeight = BUBBLE_PADDING * 2 + LINE_HEIGHT * lines.size + 18f // 18 para timestamp

        val bubblePaint = if (msg.isIncoming) paintIncoming else paintOutgoing
        val bubbleLeft: Float
        val bubbleRight: Float

        if (msg.isIncoming) {
            // Mensagem recebida: alinhada à esquerda
            bubbleLeft = MARGIN
            bubbleRight = MARGIN + maxBubbleWidth
        } else {
            // Mensagem enviada: alinhada à direita
            bubbleRight = PAGE_WIDTH - MARGIN
            bubbleLeft = bubbleRight - maxBubbleWidth
        }

        // Fundo da bolha com bordas arredondadas
        val rect = android.graphics.RectF(bubbleLeft, startY, bubbleRight, startY + bubbleHeight)
        canvas.drawRoundRect(rect, 10f, 10f, bubblePaint)
        canvas.drawRoundRect(rect, 10f, 10f, paintBorder)

        // Texto das mensagens
        var textY = startY + BUBBLE_PADDING + LINE_HEIGHT
        for (line in lines) {
            canvas.drawText(line, bubbleLeft + BUBBLE_PADDING, textY, paintText)
            textY += LINE_HEIGHT
        }

        // Horário e status
        val timeStr = if (msg.isIncoming) msg.messageTime else "✓ ${msg.messageTime}"
        val timeWidth = paintTimestamp.measureText(timeStr)
        canvas.drawText(
            timeStr,
            bubbleRight - timeWidth - BUBBLE_PADDING,
            startY + bubbleHeight - 5f,
            paintTimestamp.apply { color = colorTimestamp }
        )

        // Indicador de imagem
        if (msg.hasImage) {
            canvas.drawText(
                "📷 [Imagem]",
                bubbleLeft + BUBBLE_PADDING,
                startY + bubbleHeight - 5f,
                paintTimestamp.apply { color = Color.parseColor("#075E54") }
            )
        }

        return startY + bubbleHeight
    }

    private fun drawFooter(canvas: Canvas, totalMessages: Int) {
        val footerPaint = Paint().apply {
            color = Color.parseColor("#888888")
            textSize = 9f
            isAntiAlias = true
        }
        val text = "WhatsApp Archiver • $totalMessages mensagem(ns) arquivada(s) • ${DISPLAY_FORMAT.format(Date())}"
        canvas.drawLine(MARGIN, PAGE_HEIGHT - 30f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 30f,
            footerPaint.apply { style = Paint.Style.STROKE; strokeWidth = 0.5f })
        canvas.drawText(text, MARGIN, PAGE_HEIGHT - 15f, footerPaint)
    }

    // ─── Utilitários ──────────────────────────────────────────────────

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) <= maxWidth) {
                current = StringBuilder(test)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines.ifEmpty { listOf("") }
    }

    private fun estimateLines(text: String): Int {
        val maxWidth = PAGE_WIDTH * 0.68f - BUBBLE_PADDING * 2
        return wrapText(text, maxWidth, paintText).size
    }

    private fun getOutputDir(): File? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "WhatsAppPDF"
        )
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created) {
                Log.e(TAG, "Não foi possível criar diretório: ${dir.absolutePath}")
                // Fallback para armazenamento interno
                val fallback = File(context.filesDir, "WhatsAppPDF")
                fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }
}
