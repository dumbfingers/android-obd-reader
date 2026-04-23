package com.github.pires.obd.reader.activity

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface

class ConfirmDialog {
    interface Listener {
        fun onConfirmationDialogResponse(id: Int, confirmed: Boolean)
    }

    companion object {
        const val DIALOG_CONFIRM_DELETE_ID = 1

        fun createDialog(id: Int, context: Context, listener: Listener): Dialog? {
            return when (id) {
                DIALOG_CONFIRM_DELETE_ID -> {
                    val title = "Delete trip record"
                    val message = "Are you sure?"
                    getDialog(id, title, message, context, listener)
                }
                else -> null
            }
        }

        fun getDialog(
            id: Int,
            title: String,
            message: String,
            context: Context,
            listener: Listener
        ): Dialog {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(title)
            builder.setMessage(message)
            builder.setCancelable(true)
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                listener.onConfirmationDialogResponse(id, true)
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                listener.onConfirmationDialogResponse(id, false)
            }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(false)
            return dialog
        }
    }
}
