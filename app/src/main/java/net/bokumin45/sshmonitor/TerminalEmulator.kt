package net.bokumin45.sshmonitor

import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

class TerminalEmulator(
    private val terminalOutput: TextView,
    private val commandInput: EditText,
    private val scrollView: ScrollView,
    private val onCommandExecute: (String) -> Unit
) {
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentBuffer = ""
    private var isAlternateBuffer = false
    private var mainBuffer = StringBuilder()
    private var altBuffer = StringBuilder()
    private var savedMainBuffer = ""

    companion object {
        const val PROMPT = "$ "
        const val ESC = 27.toChar()
        private const val MAX_BUFFER_SIZE = 50000
    }

    init {
        setupKeyboardHandling()
        showPrompt()
    }

    private fun setupKeyboardHandling() {
        commandInput.setOnKeyListener { _, keyCode, event ->
            when {
                event.isCtrlPressed -> handleCtrlKey(keyCode)
                event.isAltPressed -> handleAltKey(keyCode)
                else -> handleRegularKey(keyCode, event)
            }
        }
    }

    private fun handleCtrlKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> { // 行頭へ移動
                commandInput.setSelection(0)
                true
            }
            KeyEvent.KEYCODE_E -> { // 行末へ移動
                commandInput.setSelection(commandInput.length())
                true
            }
            KeyEvent.KEYCODE_B -> { // 1文字後退
                val newPos = (commandInput.selectionStart - 1).coerceAtLeast(0)
                commandInput.setSelection(newPos)
                true
            }
            KeyEvent.KEYCODE_F -> { // 1文字前進
                val newPos = (commandInput.selectionStart + 1).coerceAtMost(commandInput.length())
                commandInput.setSelection(newPos)
                true
            }
            KeyEvent.KEYCODE_K -> { // カーソル位置から行末まで削除
                val start = commandInput.selectionStart
                commandInput.text.delete(start, commandInput.length())
                true
            }
            KeyEvent.KEYCODE_U -> { // 行頭からカーソル位置まで削除
                val end = commandInput.selectionStart
                commandInput.text.delete(0, end)
                true
            }
            KeyEvent.KEYCODE_L -> { // 画面クリア
                clearScreen()
                true
            }
            KeyEvent.KEYCODE_C -> { // 中断
                handleCtrlC()
                true
            }
            else -> false
        }
    }

    private fun handleAltKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_B -> {
                moveWordBackward()
                true
            }
            KeyEvent.KEYCODE_F -> {
                moveWordForward()
                true
            }
            KeyEvent.KEYCODE_D -> {
                deleteWord()
                true
            }
            else -> false
        }
    }

    private fun handleRegularKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                executeCommand()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showPreviousHistory()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showNextHistory()
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                // タブ補完機能は将来の実装のために予約
                true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                commandInput.setSelection(0)
                true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                commandInput.setSelection(commandInput.length())
                true
            }
            else -> false
        }
    }

    private fun moveWordBackward() {
        val text = commandInput.text.toString()
        var pos = commandInput.selectionStart
        while (pos > 0 && text[pos - 1].isWhitespace()) pos--
        while (pos > 0 && !text[pos - 1].isWhitespace()) pos--
        commandInput.setSelection(pos)
    }

    private fun moveWordForward() {
        val text = commandInput.text.toString()
        var pos = commandInput.selectionStart
        while (pos < text.length && !text[pos].isWhitespace()) pos++
        while (pos < text.length && text[pos].isWhitespace()) pos++
        commandInput.setSelection(pos)
    }

    private fun deleteWord() {
        val text = commandInput.text
        val start = commandInput.selectionStart
        var end = start
        while (end < text.length && !text[end].isWhitespace()) end++
        while (end < text.length && text[end].isWhitespace()) end++
        text.delete(start, end)
    }

    fun setAlternateBuffer(enabled: Boolean) {
        if (enabled != isAlternateBuffer) {
            if (enabled) {
                savedMainBuffer = mainBuffer.toString()
                mainBuffer.clear()
            } else {
                mainBuffer.clear()
                mainBuffer.append(savedMainBuffer)
            }
            isAlternateBuffer = enabled
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        val currentBuffer = if (isAlternateBuffer) altBuffer else mainBuffer
        terminalOutput.text = currentBuffer
        scrollToBottom()
    }

    fun appendOutput(text: String) {
        val buffer = if (isAlternateBuffer) altBuffer else mainBuffer

        if (buffer.length + text.length > MAX_BUFFER_SIZE) {
            val deleteCount = (buffer.length + text.length - MAX_BUFFER_SIZE) + 1000
            buffer.delete(0, deleteCount)
        }

        processAnsiEscapeCodes(text).let { processedText ->
            buffer.append(processedText)
        }

        updateDisplay()
    }

    private fun processAnsiEscapeCodes(text: String): CharSequence {
        val result = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == ESC && i + 1 < text.length && text[i + 1] == '[') {
                i = processEscapeSequence(text, i + 2, result)
            } else {
                result.append(text[i])
                i++
            }
        }
        return result
    }

    private fun processEscapeSequence(text: String, start: Int, result: SpannableStringBuilder): Int {
        var i = start
        val sequence = StringBuilder()
        while (i < text.length && text[i] != 'm') {
            sequence.append(text[i])
            i++
        }

        when (sequence.toString()) {
            "2J" -> clearScreen()
            "K" -> clearToEndOfLine()
            "?47h" -> setAlternateBuffer(true)
            "?47l" -> setAlternateBuffer(false)
        }

        return i + 1
    }

    private fun clearScreen() {
        if (isAlternateBuffer) {
            altBuffer.clear()
        } else {
            mainBuffer.clear()
        }
        updateDisplay()
        showPrompt()
    }

    private fun clearToEndOfLine() {
        val buffer = if (isAlternateBuffer) altBuffer else mainBuffer
        val lastNewline = buffer.lastIndexOf("\n")
        if (lastNewline != -1) {
            buffer.delete(lastNewline + 1, buffer.length)
        }
        updateDisplay()
    }

    private fun showPrompt() {
        appendOutput(PROMPT)
    }

    private fun executeCommand() {
        val command = commandInput.text.toString()
        if (command.isNotEmpty()) {
            commandHistory.add(command)
            historyIndex = commandHistory.size
            appendOutput("$command\n")
            onCommandExecute(command)
            commandInput.setText("")
        }
    }

    private fun handleCtrlC() {
        appendOutput("^C\n")
        showPrompt()
        commandInput.setText("")
    }

    private fun showPreviousHistory() {
        if (historyIndex > 0) {
            if (historyIndex == commandHistory.size) {
                currentBuffer = commandInput.text.toString()
            }
            historyIndex--
            commandInput.setText(commandHistory[historyIndex])
            commandInput.setSelection(commandInput.length())
        }
    }

    private fun showNextHistory() {
        if (historyIndex < commandHistory.size) {
            historyIndex++
            if (historyIndex == commandHistory.size) {
                commandInput.setText(currentBuffer)
            } else {
                commandInput.setText(commandHistory[historyIndex])
            }
            commandInput.setSelection(commandInput.length())
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}