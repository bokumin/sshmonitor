package net.bokumin45.sshmonitor

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import android.net.wifi.WifiManager
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup

data class ScanResult(
    val ipAddress: String,
    val hostname: String
)

class WifiScanAdapter(
    private val results: MutableList<ScanResult>,
    private val onItemClick: (ScanResult) -> Unit
) : RecyclerView.Adapter<WifiScanAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIpAddress: TextView = view.findViewById(R.id.tvIpAddress)
      //  val tvHostname: TextView = view.findViewById(R.id.tvHostname)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.tvIpAddress.text = result.ipAddress
        //holder.tvHostname.text = result.hostname
        holder.itemView.setOnClickListener { onItemClick(result) }
    }

    override fun getItemCount() = results.size

    fun updateResults(newResults: List<ScanResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }
}

class WifiScanDialog(
    context: Context,
    private val onScanResult: (String, String) -> Unit
) : Dialog(context) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private val results = mutableListOf<ScanResult>()
    private lateinit var adapter: WifiScanAdapter
    private var scanJob: Job? = null

    // よく使用されるポートのリスト
    private val portsToScan = listOf(
        // 基本的なサービス
        20, 21,   // FTP
        22,       // SSH
        23,       // Telnet
        25,       // SMTP
        53,       // DNS
        80,       // HTTP
        110,      // POP3
        143,      // IMAP
        443,      // HTTPS

        // データベース
        1433,     // MS SQL
        1521,     // Oracle
        3306,     // MySQL
        5432,     // PostgreSQL
        27017,    // MongoDB

        // その他の一般的なサービス
        135,      // Microsoft RPC
        137,      // NetBIOS
        138,      // NetBIOS
        139,      // NetBIOS
        445,      // SMB
        548,      // AFP
        631,      // IPP (プリンター)
        3389,     // RDP
        5900,     // VNC
        8080,     // 代替HTTP
        8443,     // 代替HTTPS

        // メディアサービス
        554,      // RTSP
        1935,     // RTMP
        8554,     // 代替RTSP

        // IoTデバイス
        1883,     // MQTT
        8883,     // MQTT over SSL
        5683,     // CoAP

        // NAS関連
        111,      // RPCBind
        2049,     // NFS

        // 開発関連
        3000,     // 開発サーバー
        4000,     // 開発サーバー
        5000,     // 開発サーバー
        8000,     // 開発サーバー
        9000      // 開発サーバー
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_wifi_scan)

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        adapter = WifiScanAdapter(results) { result ->
            onScanResult(result.ipAddress, result.hostname)
            dismiss()
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WifiScanDialog.adapter
        }

        findViewById<Button>(R.id.btnStartScan).setOnClickListener {
            checkWifiAndStartScan()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            scanJob?.cancel()
            dismiss()
        }
    }

    private fun checkWifiAndStartScan() {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.no_wifi))
                .setMessage(context.getString(R.string.no_wifi_message))
                .setPositiveButton(context.getString(R.string.open_wifi_setting)) { _, _ ->
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
            return
        }
        startScan()
    }
    private fun startScan() {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = context.getString(R.string.scanning)
        results.clear()
        adapter.notifyDataSetChanged()

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcpInfo = wifiManager.dhcpInfo
                val subnet = getSubnet(dhcpInfo.ipAddress)

                withContext(Dispatchers.Main) {
                    tvStatus.text = context.getString(R.string.subnet)+": $subnet"+context.getString(R.string.scanning)
                }

                val scannedIPs = mutableSetOf<String>()
                val scanJobs = mutableListOf<Deferred<Unit>>()

                for (i in 1..254) {
                    if (!isActive) return@launch

                    val testIp = "${subnet}.$i"

                    val job = async {
                        var isHostAlive = try {
                            InetAddress.getByName(testIp).isReachable(500)
                        } catch (e: Exception) {
                            false
                        }

                        if (!isHostAlive) {
                            isHostAlive = checkPorts(testIp)
                        }

                        if (isHostAlive && !scannedIPs.contains(testIp)) {
                            scannedIPs.add(testIp)
                            val hostname = try {
                                InetAddress.getByName(testIp).canonicalHostName
                            } catch (e: Exception) {
                                testIp
                            }

                            withContext(Dispatchers.Main) {
                                results.add(ScanResult(testIp, hostname))
                                adapter.notifyItemInserted(results.size - 1)
                                tvStatus.text = "${results.size}"+context.getString(R.string.found_device)
                            }
                        }
                    }
                    scanJobs.add(job)

                    if (scanJobs.size >= 10 || i == 254) {
                        scanJobs.awaitAll()
                        scanJobs.clear()
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        tvStatus.text = context.getString(R.string.found_no_device)
                    } else {
                        tvStatus.text = context.getString(R.string.scanned)+": ${results.size}"+context.getString(R.string.found_device)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = context.getString(R.string.error_occurred)+": ${e.message}"
                }
            }
        }
    }

    private fun checkPorts(ip: String): Boolean {
        for (port in listOf(22, 80, 443, 8080)) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 300
                    socket.connect(InetSocketAddress(ip, port), 300)
                    return true
                }
            } catch (e: Exception) {
            }
        }
        return false
    }
    private fun getSubnet(ipAddress: Int): String {
        return String.format(
            "%d.%d.%d",
            ipAddress and 0xff,
            (ipAddress shr 8) and 0xff,
            (ipAddress shr 16) and 0xff
        )
    }

}