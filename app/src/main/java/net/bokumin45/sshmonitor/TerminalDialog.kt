package net.bokumin45.sshmonitor

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class TerminalDialog(
    context: Context,
    private val session: Session?
) : Dialog(context) {

    private lateinit var commandInput: EditText
    private lateinit var terminalOutput: TextView
    private var currentCommand: ChannelExec? = null
    private var commandJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_terminal)

        commandInput = findViewById(R.id.commandInput)
        terminalOutput = findViewById(R.id.terminalOutput)

        setupCommandInput()
    }

    private fun setupCommandInput() {
        commandInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeCommand(v.text.toString())
                v.text = ""
                true
            } else false
        }
    }

    private fun executeCommand(command: String) {
        if (session?.isConnected != true) {
            appendOutput("Not connected to server\n")
            return
        }

        commandJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                currentCommand = session.openChannel("exec") as ChannelExec
                currentCommand?.apply {
                    setCommand(command)
                    inputStream = null
                    val outputStream = ByteArrayOutputStream()
                    setOutputStream(outputStream)
                    val errorStream = ByteArrayOutputStream()
                    setErrStream(errorStream)
                    connect()

                    while (isConnected) {
                        delay(100)
                    }

                    withContext(Dispatchers.Main) {
                        appendOutput("$ $command\n")
                        appendOutput(outputStream.toString())
                        if (errorStream.size() > 0) {
                            appendOutput("Error: ${errorStream.toString()}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("Error: ${e.message}\n")
                }
            } finally {
                currentCommand?.disconnect()
            }
        }
    }

    private fun appendOutput(text: String) {
        terminalOutput.append(text)
        val scrollAmount = terminalOutput.layout?.getLineTop(terminalOutput.lineCount) ?: 0
        terminalOutput.scrollTo(0, scrollAmount)
    }

    override fun dismiss() {
        commandJob?.cancel()
        currentCommand?.disconnect()
        super.dismiss()
    }
}


