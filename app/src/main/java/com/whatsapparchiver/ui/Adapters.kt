package com.whatsapparchiver.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.whatsapparchiver.data.GeneratedPdf
import com.whatsapparchiver.data.MonitoredPhone
import com.whatsapparchiver.databinding.ItemMonitoredPhoneBinding
import com.whatsapparchiver.databinding.ItemPdfHistoryBinding
import com.whatsapparchiver.util.PhoneNormalizer
import java.text.SimpleDateFormat
import java.util.Locale

// ══════════════════════════════════════════════════════
// Adapter: Lista de números monitorados
// ══════════════════════════════════════════════════════

class MonitoredPhoneAdapter(
    private val onToggle: (MonitoredPhone, Boolean) -> Unit,
    private val onDelete: (MonitoredPhone) -> Unit
) : ListAdapter<MonitoredPhone, MonitoredPhoneAdapter.VH>(DIFF) {

    inner class VH(val b: ItemMonitoredPhoneBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemMonitoredPhoneBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val phone = getItem(position)
        with(holder.b) {
            tvPhoneLabel.text = phone.displayLabel
            tvPhoneNumber.text = PhoneNormalizer.formatForDisplay(phone.normalizedNumber)

            // Evita disparar listener ao rebind
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = phone.isEnabled
            switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(phone, checked)
            }

            btnDelete.setOnClickListener { onDelete(phone) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MonitoredPhone>() {
            override fun areItemsTheSame(a: MonitoredPhone, b: MonitoredPhone) = a.id == b.id
            override fun areContentsTheSame(a: MonitoredPhone, b: MonitoredPhone) = a == b
        }
    }
}

// ══════════════════════════════════════════════════════
// Adapter: Histórico de PDFs gerados
// ══════════════════════════════════════════════════════

class PdfHistoryAdapter(
    private val onOpen: (String) -> Unit,
    private val onShare: (String) -> Unit
) : ListAdapter<GeneratedPdf, PdfHistoryAdapter.VH>(DIFF) {

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class VH(val b: ItemPdfHistoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemPdfHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pdf = getItem(position)
        with(holder.b) {
            tvPdfLabel.text = pdf.displayLabel
            tvPdfFilename.text = pdf.filePath.substringAfterLast("/")
            tvPdfDate.text = fmt.format(pdf.generatedAt)
            tvPdfMsgCount.text = "${pdf.messageCount} msgs"

            btnOpenPdf.setOnClickListener { onOpen(pdf.filePath) }
            btnSharePdf.setOnClickListener { onShare(pdf.filePath) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GeneratedPdf>() {
            override fun areItemsTheSame(a: GeneratedPdf, b: GeneratedPdf) = a.id == b.id
            override fun areContentsTheSame(a: GeneratedPdf, b: GeneratedPdf) = a == b
        }
    }
}
