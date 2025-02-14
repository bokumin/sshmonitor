package net.bokumin45.sshmonitor

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.util.*

class TerminalEmulator(
    private val terminalOutput: TextView,
    private val commandInput: EditText,
    private val scrollView: ScrollView,
    private val onCommandExecute: (String) -> Unit
) {
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentBuffer = ""
    private val terminalBuffer = CircularBuffer(MAX_BUFFER_LINES)
    private var currentCommand = ""
    private var cursorPosition = 0

    companion object {
        const val MAX_BUFFER_LINES = 1000
        const val PROMPT = "$ "
        const val ESC = 27.toChar()
        private val ANSI_COLORS = mapOf(
            "30" to Color.BLACK,
            "31" to Color.RED,
            "32" to Color.GREEN,
            "33" to Color.YELLOW,
            "34" to Color.BLUE,
            "35" to Color.MAGENTA,
            "36" to Color.CYAN,
            "37" to Color.WHITE
        )
    }

    init {
        setupCommandInput()
        showPrompt()
    }

    private fun setupCommandInput() {
        commandInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> handleEnterKey()
                    KeyEvent.KEYCODE_DPAD_UP -> handleUpKey()
                    KeyEvent.KEYCODE_DPAD_DOWN -> handleDownKey()
                    KeyEvent.KEYCODE_TAB -> handleTabKey()
                    else -> handleOtherKeys(keyCode, event)
                }
            } else false
        }
    }

    private fun handleEnterKey(): Boolean {
        val command = commandInput.text.toString()
        if (command.isNotEmpty()) {
            commandHistory.add(command)
            historyIndex = commandHistory.size
            appendToTerminal("$PROMPT$command\n")
            onCommandExecute(command)
            commandInput.setText("")
        }
        return true
    }

    private fun handleUpKey(): Boolean {
        if (historyIndex > 0) {
            if (historyIndex == commandHistory.size) {
                currentBuffer = commandInput.text.toString()
            }
            historyIndex--
            commandInput.setText(commandHistory[historyIndex])
            commandInput.setSelection(commandInput.length())
        }
        return true
    }

    private fun handleDownKey(): Boolean {
        if (historyIndex < commandHistory.size) {
            historyIndex++
            if (historyIndex == commandHistory.size) {
                commandInput.setText(currentBuffer)
            } else {
                commandInput.setText(commandHistory[historyIndex])
            }
            commandInput.setSelection(commandInput.length())
        }
        return true
    }

    private fun handleTabKey(): Boolean {
        // TODO: Implement tab completion
        return true
    }

    private fun handleOtherKeys(keyCode: Int, event: KeyEvent): Boolean {
        return if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> {
                    handleCtrlC()
                    true
                }
                KeyEvent.KEYCODE_L -> {
                    clearScreen()
                    true
                }
                KeyEvent.KEYCODE_A -> {
                    commandInput.setSelection(0)
                    true
                }
                KeyEvent.KEYCODE_E -> {
                    commandInput.setSelection(commandInput.length())
                    true
                }
                KeyEvent.KEYCODE_U -> {
                    commandInput.setText("")
                    true
                }
                else -> false
            }
        } else false
    }

    fun appendOutput(text: String) {
        val processedText = processAnsiEscapeCodes(text)
        appendToTerminal(processedText)
    }

    private fun processAnsiEscapeCodes(text: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        var currentColor = Color.WHITE
        var i = 0

        while (i < text.length) {
            if (text[i] == ESC && i + 1 < text.length && text[i + 1] == '[') {
                i += 2
                val codeBuilder = StringBuilder()
                while (i < text.length && text[i] != 'm') {
                    codeBuilder.append(text[i])
                    i++
                }
                val code = codeBuilder.toString()
                currentColor = ANSI_COLORS[code] ?: Color.WHITE
                i++
            } else {
                val startIndex = result.length
                val char = text[i]
                result.append(char)
                result.setSpan(
                    ForegroundColorSpan(currentColor),
                    startIndex,
                    startIndex + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                i++
            }
        }
        return result
    }

    private fun appendToTerminal(text: CharSequence) {
        terminalOutput.post {
            terminalOutput.append(text)
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showPrompt() {
        val spannableString = SpannableStringBuilder(PROMPT)
        spannableString.setSpan(
            ForegroundColorSpan(Color.GREEN),
            0,
            PROMPT.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendToTerminal(spannableString)
    }

    private fun handleCtrlC() {
        appendToTerminal("^C\n")
        showPrompt()
        commandInput.setText("")
    }

    private fun clearScreen() {
        terminalOutput.text = ""
        showPrompt()
    }

    inner class CircularBuffer(private val maxSize: Int) {
        private val buffer = LinkedList<String>()

        fun append(text: String) {
            val lines = text.split("\n")
            lines.forEach { line ->
                if (buffer.size >= maxSize) {
                    buffer.removeFirst()
                }
                buffer.add(line)
            }
        }

        fun clear() {
            buffer.clear()
        }

        override fun toString(): String {
            return buffer.joinToString("\n")
        }
    }
}