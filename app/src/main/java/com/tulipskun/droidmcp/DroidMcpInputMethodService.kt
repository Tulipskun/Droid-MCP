package com.tulipskun.droidmcp

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

class DroidMcpInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(4, 4, 4, 4)
        }

        val rows = listOf(
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM",
            "1234567890"
        )

        rows.forEach { letters ->
            root.addView(createRow(letters.map { it.toString() }))
        }

        root.addView(
            createRow(
                listOf(
                    "⌫" to { currentInputConnection?.deleteSurroundingText(1, 0) },
                    "SPACE" to { currentInputConnection?.commitText(" ", 1) },
                    "↵" to { currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)) }
                )
            )
        )

        return root
    }

    private fun createRow(keys: List<String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        keys.forEach { key ->
            row.addView(createKey(key))
        }

        return row
    }

    private fun createRow(keys: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        keys.forEach { (label, action) ->
            val button = createKey(label)
            button.setOnClickListener { action() }
            row.addView(button)
        }

        return row
    }

    private fun createKey(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            setOnClickListener {
                currentInputConnection?.commitText(label, 1)
            }

            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(2, 2, 2, 2)
            }
        }
    }
}
