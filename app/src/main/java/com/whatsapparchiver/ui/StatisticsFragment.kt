package com.whatsapparchiver.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.whatsapparchiver.R
import com.whatsapparchiver.data.AppDatabase
import com.whatsapparchiver.data.ConversationRepository
import com.whatsapparchiver.databinding.FragmentStatisticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * StatisticsFragment — Gráficos inteligentes de atividade do arquivamento.
 *
 * Gráficos disponíveis:
 *   1. BarChart  — Mensagens capturadas por dia (últimos N dias)
 *   2. LineChart — PDFs gerados por semana (linha de tendência)
 *   3. PieChart  — Distribuição de mensagens por contato
 *
 * Períodos: Hoje / 7 dias / 30 dias / Personalizado (DateRangePicker)
 *
 * Para usar, adicione ao NavGraph ou abra via FragmentTransaction.
 * Requer MPAndroidChart no build.gradle (já incluído).
 */
class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: ConversationRepository

    // Período selecionado (timestamps ms)
    private var periodStart: Long = 0L
    private var periodEnd: Long = System.currentTimeMillis()

    companion object {
        // Cores do tema WhatsApp
        private val COLOR_GREEN   = Color.parseColor("#075E54")
        private val COLOR_LIGHT   = Color.parseColor("#25D366")
        private val COLOR_TEAL    = Color.parseColor("#128C7E")
        private val COLOR_BUBBLE  = Color.parseColor("#DCF8C6")
        private val COLOR_MUTED   = Color.parseColor("#888888")

        // Paleta para pizza
        private val PIE_COLORS = intArrayOf(
            Color.parseColor("#075E54"), Color.parseColor("#25D366"),
            Color.parseColor("#128C7E"), Color.parseColor("#34B7F1"),
            Color.parseColor("#ECE5DD"), Color.parseColor("#FFA726"),
            Color.parseColor("#EF5350"), Color.parseColor("#AB47BC")
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getInstance(requireContext())
        repository = ConversationRepository(db)

        setupChipGroup()
        setupCharts()
        selectPeriod(days = 7) // Padrão: últimos 7 dias
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Seletor de Período ────────────────────────────────────────────

    private fun setupChipGroup() {
        binding.chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipDay    -> selectPeriod(days = 1)
                R.id.chipWeek   -> selectPeriod(days = 7)
                R.id.chipMonth  -> selectPeriod(days = 30)
                R.id.chipCustom -> showDateRangePicker()
            }
        }

        binding.etDateFrom.setOnClickListener { showDateRangePicker() }
        binding.etDateTo.setOnClickListener { showDateRangePicker() }
    }

    private fun selectPeriod(days: Int) {
        val cal = Calendar.getInstance()
        periodEnd = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -days)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        periodStart = cal.timeInMillis
        binding.layoutDateRange.visibility = View.GONE
        loadAllCharts()
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Selecionar período")
            .setTheme(R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            periodStart = selection.first ?: return@addOnPositiveButtonClickListener
            periodEnd   = selection.second ?: System.currentTimeMillis()

            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            binding.etDateFrom.setText(fmt.format(Date(periodStart)))
            binding.etDateTo.setText(fmt.format(Date(periodEnd)))
            binding.layoutDateRange.visibility = View.VISIBLE
            loadAllCharts()
        }
        picker.show(parentFragmentManager, "DateRangePicker")
    }

    // ─── Carregamento dos dados ────────────────────────────────────────

    private fun loadAllCharts() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                repository.getStatistics(periodStart, periodEnd)
            }
            updateKpis(data)
            loadDailyBarChart(data.messagesByDay)
            loadWeeklyLineChart(data.pdfsByWeek)
            loadContactPieChart(data.messagesByContact)
        }
    }

    private fun updateKpis(data: StatsData) {
        binding.tvKpiMessages.text = data.totalMessages.toString()
        binding.tvKpiPdfs.text     = data.totalPdfs.toString()
        binding.tvKpiContacts.text = data.activeContacts.toString()
    }

    // ─── Gráfico 1: Barras — Mensagens por Dia ────────────────────────

    private fun loadDailyBarChart(data: Map<String, Int>) {
        val chart = binding.chartDaily
        if (data.isEmpty()) { chart.clear(); chart.invalidate(); return }

        val labels = data.keys.toList()
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }

        val dataSet = BarDataSet(entries, "Mensagens").apply {
            color = COLOR_GREEN
            valueTextColor = Color.WHITE
            valueTextSize = 9f
            setDrawValues(true)
        }

        styleBarChart(chart, labels)
        chart.data = BarData(dataSet).apply { barWidth = 0.6f }
        chart.animateY(600)
        chart.invalidate()
    }

    private fun styleBarChart(chart: BarChart, labels: List<String>) {
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setFitBars(true)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                valueFormatter = IndexAxisValueFormatter(labels.map { it.takeLast(5) })
                labelRotationAngle = -30f
                textSize = 9f
                textColor = Color.parseColor("#555555")
                granularity = 1f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#EEEEEE")
                textColor = COLOR_MUTED
                textSize = 9f
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
        }
    }

    // ─── Gráfico 2: Linha — PDFs por Semana ────────────────────────────

    private fun loadWeeklyLineChart(data: Map<String, Int>) {
        val chart = binding.chartWeekly
        if (data.isEmpty()) { chart.clear(); chart.invalidate(); return }

        val labels = data.keys.toList()
        val entries = data.values.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }

        val dataSet = LineDataSet(entries, "PDFs gerados").apply {
            color = COLOR_LIGHT
            setCircleColor(COLOR_GREEN)
            circleRadius = 4f
            lineWidth = 2.5f
            valueTextSize = 9f
            valueTextColor = COLOR_MUTED
            mode = LineDataSet.Mode.CUBIC_BEZIER // Linha suave
            setDrawFilled(true)
            fillColor = COLOR_LIGHT
            fillAlpha = 40
        }

        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                valueFormatter = IndexAxisValueFormatter(labels)
                textSize = 9f
                textColor = Color.parseColor("#555555")
                labelRotationAngle = -20f
                granularity = 1f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#EEEEEE")
                textColor = COLOR_MUTED
                textSize = 9f
                axisMinimum = 0f
                granularity = 1f
            }
            axisRight.isEnabled = false
            data = LineData(dataSet)
            animateX(800)
            invalidate()
        }
    }

    // ─── Gráfico 3: Pizza — Por Contato ────────────────────────────────

    private fun loadContactPieChart(data: Map<String, Int>) {
        val chart = binding.chartByContact
        if (data.isEmpty()) { chart.clear(); chart.invalidate(); return }

        val entries = data.entries.map { (label, count) ->
            PieEntry(count.toFloat(), label.take(12))
        }

        val dataSet = PieDataSet(entries, "").apply {
            colors = PIE_COLORS.toMutableList()
            sliceSpace = 2f
            valueTextSize = 11f
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }

        chart.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 38f
            transparentCircleRadius = 44f
            holeColor = Color.WHITE
            setUsePercentValues(true)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(10f)

            legend.apply {
                isEnabled = true
                textSize = 10f
                textColor = Color.parseColor("#333333")
                isWordWrapEnabled = true
            }

            centerText = "Contatos"
            setCenterTextSize(13f)
            setCenterTextColor(COLOR_GREEN)

            data = PieData(dataSet)
            animateY(800)
            invalidate()
        }
    }

    // ─── Configuração inicial dos gráficos (estado vazio) ─────────────

    private fun setupCharts() {
        // Estado "sem dados" amigável
        listOf(binding.chartDaily, binding.chartWeekly, binding.chartByContact).forEach { chart ->
            chart.setNoDataText("Carregando dados...")
            chart.setNoDataTextColor(COLOR_MUTED)
        }
    }
}

// ══════════════════════════════════════════════════════
// Modelo de dados para estatísticas
// (adicionar na ConversationRepository)
// ══════════════════════════════════════════════════════

data class StatsData(
    val totalMessages: Int,
    val totalPdfs: Int,
    val activeContacts: Int,
    val messagesByDay: Map<String, Int>,       // "2026-05-14" → 42
    val pdfsByWeek: Map<String, Int>,          // "Sem 20/2026" → 5
    val messagesByContact: Map<String, Int>    // "João Silva" → 120
)

// ── Extensão do repositório para estatísticas ──
// Adicionar ao ConversationRepository.kt:
//
// suspend fun getStatistics(start: Long, end: Long): StatsData {
//     val messages = db.capturedMessageDao().getRange(start, end)
//     val pdfs = db.generatedPdfDao().getRange(start, end)
//     val phones = db.monitoredPhoneDao().getEnabled()
//
//     val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
//     val weekFmt = SimpleDateFormat("'Sem' ww/yyyy", Locale.getDefault())
//
//     val byDay = messages.groupBy { dayFmt.format(it.capturedAt) }
//         .mapValues { it.value.size }
//         .toSortedMap()
//
//     val byWeek = pdfs.groupBy { weekFmt.format(it.generatedAt) }
//         .mapValues { it.value.size }
//         .toSortedMap()
//
//     val phoneMap = phones.associateBy { it.id }
//     val byContact = messages.groupBy { phoneMap[it.phoneId]?.displayLabel ?: "Desconhecido" }
//         .mapValues { it.value.size }
//         .entries.sortedByDescending { it.value }
//         .take(8).associate { it.key to it.value }
//
//     return StatsData(
//         totalMessages = messages.size,
//         totalPdfs = pdfs.size,
//         activeContacts = phones.size,
//         messagesByDay = byDay,
//         pdfsByWeek = byWeek,
//         messagesByContact = byContact
//     )
// }
