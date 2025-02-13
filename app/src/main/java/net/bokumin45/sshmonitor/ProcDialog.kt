package net.bokumin45.sshmonitor

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

data class ProcessInfo(
    val pid: String,
    val cpu: String,
    val mem: String,
    val command: String
)

class ProcessAdapter(private val processes: MutableList<ProcessInfo>) :
    RecyclerView.Adapter<ProcessAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvProcess: TextView = view.findViewById(R.id.tvProcess)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val process = processes[position]
        holder.tvProcess.text = String.format(
            "%-8s CPU: %-6s MEM: %-6s %s",
            process.pid, process.cpu, process.mem, process.command
        )
    }

    override fun getItemCount() = processes.size

    fun updateProcesses(newProcesses: List<ProcessInfo>) {
        processes.clear()
        processes.addAll(newProcesses)
        notifyDataSetChanged()
    }
}

class ProcDialog(
    context: Context,
    private val session: Session?
) : Dialog(context) {

    private lateinit var recyclerView: RecyclerView
    private val processes = mutableListOf<ProcessInfo>()
    private lateinit var adapter: ProcessAdapter
    private var updateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_proc)

        recyclerView = findViewById(R.id.procList)
        setupRecyclerView()
        startProcessUpdates()
    }

    private fun setupRecyclerView() {
        adapter = ProcessAdapter(processes)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProcDialog.adapter
        }
    }

    private fun startProcessUpdates() {
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                updateProcessList()
                delay(2000) // 2秒ごとに更新
            }
        }
    }

    private suspend fun updateProcessList() {
        if (session?.isConnected != true) return

        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("ps aux --sort=-%cpu | head -n 21") // トップ20プロセスを取得

            val outputStream = ByteArrayOutputStream()
            channel.outputStream = outputStream
            channel.connect()

            while (channel.isConnected) {
                delay(100)
            }

            val output = outputStream.toString()
            val newProcesses = parseProcessOutput(output)

            withContext(Dispatchers.Main) {
                adapter.updateProcesses(newProcesses)
            }

            channel.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseProcessOutput(output: String): List<ProcessInfo> {
        return output.lines()
            .drop(1) // ヘッダー行をスキップ
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split("\\s+".toRegex())
                ProcessInfo(
                    pid = parts.getOrNull(1) ?: "",
                    cpu = parts.getOrNull(2) ?: "",
                    mem = parts.getOrNull(3) ?: "",
                    command = parts.drop(10).joinToString(" ")
                )
            }
    }

    override fun dismiss() {
        updateJob?.cancel()
        super.dismiss()
    }
}