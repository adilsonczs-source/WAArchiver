package com.whatsapparchiver.util

/**
 * PhoneNormalizer — Normaliza números de telefone brasileiros para E.164
 *
 * Suporta os formatos:
 *   65999999999       → +5565999999999
 *   065999999999      → +5565999999999
 *   5565999999999     → +5565999999999
 *   +55 65 99999-9999 → +5565999999999
 *   (65) 99999-9999   → +5565999999999
 *   +5565999999999    → +5565999999999 (já normalizado)
 *
 * Também aceita nomes de contato: retorna null se não for número.
 */
object PhoneNormalizer {

    // DDDs válidos do Brasil (ANATEL)
    private val VALID_DDD = setOf(
        "11","12","13","14","15","16","17","18","19", // SP
        "21","22","24",                               // RJ
        "27","28",                                    // ES
        "31","32","33","34","35","37","38",           // MG
        "41","42","43","44","45","46",                // PR
        "47","48","49",                               // SC
        "51","53","54","55",                          // RS
        "61",                                         // DF
        "62","64",                                    // GO
        "63",                                         // TO
        "65","66",                                    // MT
        "67",                                         // MS
        "68",                                         // AC
        "69",                                         // RO
        "71","73","74","75","77",                     // BA
        "79",                                         // SE
        "81","87",                                    // PE
        "82",                                         // AL
        "83",                                         // PB
        "84",                                         // RN
        "85","88",                                    // CE
        "86","89",                                    // PI
        "91","93","94",                               // PA
        "92","97",                                    // AM
        "95",                                         // RR
        "96",                                         // AP
        "98","99"                                     // MA
    )

    /**
     * Normaliza o número para E.164 (+5565999999999).
     * Retorna null se a entrada não parecer um número de telefone válido.
     */
    fun normalize(input: String): String? {
        if (input.isBlank()) return null

        // Remove caracteres não numéricos (exceto + no início)
        val stripped = input.trim()
            .replace(Regex("[\\s()\\-.]"), "")
            .replace(Regex("^\\+"), "PLUS")
            .replace(Regex("[^0-9]"), "")
            .let {
                if (input.trimStart().startsWith("+")) "+$it" else it
            }

        // Verifica se é texto puro (nome de contato), não número
        if (stripped.replace("+", "").any { !it.isDigit() }) return null
        if (stripped.replace("+", "").length < 8) return null

        val digits = stripped.replace("+", "")

        return when {
            // Já tem código do país: +5565999999999 ou 5565999999999
            digits.startsWith("55") && digits.length >= 12 -> {
                val rest = digits.substring(2)
                buildE164(rest)
            }
            // Tem 0 na frente: 065999999999
            digits.startsWith("0") -> {
                buildE164(digits.substring(1))
            }
            // Sem código do país: 65999999999 (11 dígitos) ou 9999-9999 com DDD implícito
            else -> buildE164(digits)
        }
    }

    /**
     * Constrói E.164 a partir de número sem código do país.
     * Espera DDD + número (8 ou 9 dígitos).
     */
    private fun buildE164(local: String): String? {
        if (local.length < 10 || local.length > 11) return null

        val ddd = local.substring(0, 2)
        val number = local.substring(2)

        if (ddd !in VALID_DDD) return null
        if (number.length != 8 && number.length != 9) return null

        // Celulares com 9 dígitos em MG e SP nem sempre têm o "9" adicional
        // mas normalizamos mantendo como está
        return "+55$ddd$number"
    }

    /**
     * Formata número E.164 para exibição amigável.
     * Ex: +5565999999999 → (65) 99999-9999
     */
    fun formatForDisplay(e164: String): String {
        if (!e164.startsWith("+55")) return e164
        val digits = e164.removePrefix("+55")
        if (digits.length < 10) return e164

        val ddd = digits.substring(0, 2)
        val number = digits.substring(2)

        return when (number.length) {
            9 -> "($ddd) ${number.substring(0, 5)}-${number.substring(5)}"
            8 -> "($ddd) ${number.substring(0, 4)}-${number.substring(4)}"
            else -> e164
        }
    }

    /**
     * Verifica se dois números se referem ao mesmo contato,
     * tolerando variações de formato.
     */
    fun isSameNumber(a: String, b: String): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        return na == nb
    }
}
