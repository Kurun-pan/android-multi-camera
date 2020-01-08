package com.example.multicameraapi.ui.main

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class ErrorMessageDialog : DialogFragment() {

    companion object {
        @JvmStatic
        private val ARG_MESSAGE = "message"

        @JvmStatic
        fun newInstance(message: String): ErrorMessageDialog = ErrorMessageDialog().apply {
            arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = arguments?.getString(ARG_MESSAGE) ?: ""
        return AlertDialog.Builder(activity!!)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> activity?.finish() }
                .create()
    }
}
