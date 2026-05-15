package com.whatsapparchiver.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.whatsapparchiver.R

/**
 * Dialog para adicionar novo número monitorado.
 * Campos: número (obrigatório) e rótulo/nome (opcional).
 */
class AddPhoneDialogFragment(
    private val onConfirm: (rawNumber: String, label: String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_add_phone, null)
        val tilNumber = view.findViewById<TextInputLayout>(R.id.tilPhoneNumber)
        val etNumber  = view.findViewById<TextInputEditText>(R.id.etPhoneNumber)
        val etLabel   = view.findViewById<TextInputEditText>(R.id.etPhoneLabel)

        return AlertDialog.Builder(requireContext())
            .setTitle("Adicionar número")
            .setView(view)
            .setPositiveButton("Adicionar") { _, _ ->
                val number = etNumber.text?.toString()?.trim() ?: ""
                val label  = etLabel.text?.toString()?.trim() ?: ""
                if (number.isBlank()) {
                    Toast.makeText(context, "Informe um número", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(number, label)
            }
            .setNegativeButton("Cancelar", null)
            .create()
    }
}
