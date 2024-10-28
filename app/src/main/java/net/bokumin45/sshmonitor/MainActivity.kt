package net.bokumin45.sshmonitor

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.documentfile.provider.DocumentFile
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.navigation.NavigationView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.Security
import java.util.Locale

data class ServerConfig(
    val host: String,
    val port: Int,
    val username: String,
    var privateKeyUri: Uri? = null,
    var password: String? = null
){
    override fun toString(): String {
        return host
    }
}

class ServerConfigManager(private val context: Context) {
    private val sharedPreferences = context.getSharedPreferences("ServerConfigs", Context.MODE_PRIVATE)

    fun saveServerConfig(config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        configs.add(config)
        sharedPreferences.edit().putString("configs", configs.joinToString("|") { "${it.host},${it.port},${it.username},${it.privateKeyUri},${it.password}" }).apply()
    }

    fun updateServerConfig(index: Int, config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        configs[index] = config
        sharedPreferences.edit().putString("configs", configs.joinToString("|") { "${it.host},${it.port},${it.username},${it.privateKeyUri},${it.password}" }).apply()
    }

    fun getServerConfigs(): List<ServerConfig> {
        val configString = sharedPreferences.getString("configs", "") ?: ""
        return configString.split("|").filter { it.isNotEmpty() }.map {
            val (host, port, username, privateKeyUri, password) = it.split(",", limit = 5)
            ServerConfig(
                host,
                port.toInt(),
                username,
                privateKeyUri.takeIf { it != "null" }?.let { Uri.parse(it) },
                password.takeIf { it != "null" }
            )
        }
    }

    fun removeServerConfig(config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        configs.remove(config)
        sharedPreferences.edit().putString("configs", configs.joinToString("|") { "${it.host},${it.port},${it.username},${it.privateKeyUri},${it.password}" }).apply()
    }
}

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var spinnerServers: Spinner
    private lateinit var btnConnect: Button
    private lateinit var chartCPU: LineChart
    private lateinit var chartMemory: LineChart
    private lateinit var chartDisk: LineChart
    private lateinit var tvUptime: TextView

    private lateinit var serverConfigManager: ServerConfigManager
    private var serverConfigs: MutableList<ServerConfig> = mutableListOf()
    private var currentSession: Session? = null
    private var monitoringJob: Job? = null
    private var selectedKeyUri: Uri? = null

    private val REQUEST_CODE_OPEN_FILE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        Security.addProvider(BouncyCastleProvider())

        serverConfigManager = ServerConfigManager(this)
        serverConfigs = serverConfigManager.getServerConfigs().toMutableList()

        initializeViews()
        setupToolbar()
        setupDrawer()
        setupListeners()
        setupCharts()
        updateServerSpinner()
    }

    private fun initializeViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        spinnerServers = findViewById(R.id.spinnerServers)
        btnConnect = findViewById(R.id.btnConnect)
        chartCPU = findViewById(R.id.chartCPU)
        chartMemory = findViewById(R.id.chartMemory)
        chartDisk = findViewById(R.id.chartDisk)
        tvUptime = findViewById(R.id.tvUptime)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)
        updateNavigationMenu()
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            val selectedServer = spinnerServers.selectedItem as? ServerConfig
            if (selectedServer != null) {
                if (currentSession == null) {
                    connectSSH(selectedServer)
                } else {
                    disconnectSSH()
                }
            } else {
                Toast.makeText(this, "サーバーを選択してください", Toast.LENGTH_SHORT).show()
            }
        }

        // スピナーにリスナーを追加
        spinnerServers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (currentSession != null) {
                    disconnectSSH()
                }
                resetCharts()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 何もしない
            }
        }
    }    private fun setupChart(chart: LineChart, label: String, color1: Int) {
        chart.apply {
            description.apply {
                isEnabled = true
                text = label
                textSize = 14f
                textColor = Color.WHITE
            }

            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setDrawGridBackground(false)
            setPinchZoom(true)
            legend.apply {
                isEnabled = true
                textColor = Color.WHITE
            }

            xAxis.apply {
                isEnabled = true
                setDrawGridLines(true)
                setDrawLabels(true)
                position = XAxis.XAxisPosition.BOTTOM
                labelRotationAngle = 0f
                textColor = Color.WHITE
                gridColor = Color.GRAY
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return value.toInt().toString()
                    }
                }
            }

            axisLeft.apply {
                isEnabled = true
                setDrawGridLines(true)
                textColor = Color.WHITE
                gridColor = Color.GRAY
                if (label.contains("温度")) {
                    axisMinimum = 0f
                    axisMaximum = 100f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "${value.toInt()}℃"
                        }
                    }
                } else {
                    axisMinimum = 0f
                    axisMaximum = 100f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "${value.toInt()}%"
                        }
                    }
                }
            }

            axisRight.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val dataSet = LineDataSet(null, label).apply {
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            color = color1
        }

        chart.data = LineData(dataSet)
    }

    private fun setupCharts() {
        setupChart(chartCPU, "CPU", Color.RED)
        setupChart(chartMemory, "MEM", Color.GREEN)
        setupChart(chartDisk, "DISK", Color.BLUE)
    }

    private fun updateServerSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, serverConfigs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerServers.adapter = adapter

        updateNavigationMenu()
    }

    private fun updateNavigationMenu() {
        val menu = navView.menu
        menu.clear()

        val serverListSubmenu = menu.addSubMenu(getString(R.string.server_list))

        serverConfigs.forEachIndexed { index, config ->
            serverListSubmenu.add(Menu.NONE, Menu.FIRST + index, Menu.NONE, "${config.host} (${config.username})").apply {
                setOnMenuItemClickListener {
                    showEditServerDialog(index)
                    true
                }
            }
        }

        menu.add(Menu.NONE, R.id.nav_add_server, Menu.NONE, getString(R.string.add_server))
        menu.add(Menu.NONE, R.id.nav_remove_server, Menu.NONE, getString(R.string.remove_server))
        menu.add(Menu.NONE, R.id.nav_language, Menu.NONE, getString(R.string.change_language))
        menu.add(Menu.NONE, R.id.nav_donate, Menu.NONE, getString(R.string.donate))
    }

    private fun showEditServerDialog(index: Int) {
        val config = serverConfigs[index]
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKey)
        val tvSelectedKey = dialogView.findViewById<TextView>(R.id.tvSelectedKey)

        etHost.setText(config.host)
        etPort.setText(config.port.toString())
        etUsername.setText(config.username)
        etPassword.setText(config.password)
        selectedKeyUri = config.privateKeyUri
        tvSelectedKey.text = if (selectedKeyUri != null) {
            "選択したファイル: ${DocumentFile.fromSingleUri(this, selectedKeyUri!!)?.name}"
        } else {
            "鍵ファイルが選択されていません"
        }

        btnSelectKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_FILE)
        }

        AlertDialog.Builder(this)
            .setTitle("サーバー情報を編集")
            .setView(dialogView)
            .setPositiveButton("更新") { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 22
                val username = etUsername.text.toString()
                val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                if (host.isNotEmpty() && username.isNotEmpty()) {
                    val updatedConfig = ServerConfig(host, port, username, selectedKeyUri, password)
                    serverConfigs[index] = updatedConfig
                    serverConfigManager.updateServerConfig(index, updatedConfig)
                    updateServerSpinner()
                    Toast.makeText(this, "サーバー情報が更新されました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ホスト名とユーザー名は必須です", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAddServerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKey)
        val tvSelectedKey = dialogView.findViewById<TextView>(R.id.tvSelectedKey)

        selectedKeyUri = null

        btnSelectKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_FILE)
        }

        AlertDialog.Builder(this)
            .setTitle("サーバーを追加")
            .setView(dialogView)
            .setPositiveButton("追加") { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 22
                val username = etUsername.text.toString()
                val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                if (host.isNotEmpty() && username.isNotEmpty()) {
                    val config = ServerConfig(host, port, username, selectedKeyUri, password)
                    serverConfigs.add(config)
                    serverConfigManager.saveServerConfig(config)
                    updateServerSpinner() // この1つの呼び出しでSpinnerとナビゲーションメニューの両方が更新される
                } else {
                    Toast.makeText(this, "ホスト名とユーザー名は必須です", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showRemoveServerDialog() {
        if (serverConfigs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_servers_to_remove), Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = serverConfigs.map { "${it.host} (${it.username})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_server))
            .setItems(serverNames) { _, which ->
                val removedConfig = serverConfigs.removeAt(which)
                serverConfigManager.removeServerConfig(removedConfig)
                updateServerSpinner()
                Toast.makeText(this, getString(R.string.server_removed), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun toggleLanguage() {
        val currentLocale = resources.configuration.locales[0]
        val newLocale = if (currentLocale.language == "en") Locale("ja") else Locale("en")

        val config = resources.configuration
        config.setLocale(newLocale)
        resources.updateConfiguration(config, resources.displayMetrics)

        recreate()
    }

    private fun showDonateDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.donate))
            .setMessage(getString(R.string.donate_message))
            .setPositiveButton(getString(R.string.open_browser)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://bokumin45.server-on.net"))
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val fileName = DocumentFile.fromSingleUri(this, uri)?.name
                val dialogView = (currentFocus?.parent as? View)?.findViewById<TextView>(R.id.tvSelectedKey)
                dialogView?.text = "選択したファイル: $fileName"

                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                selectedKeyUri?.let { oldUri ->
                    contentResolver.releasePersistableUriPermission(
                        oldUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                selectedKeyUri = uri
            }
        }
    }

    private fun connectSSH(config: ServerConfig) {
        resetCharts()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsch = JSch()
                config.privateKeyUri?.let { uri ->
                    val keyBytes = readKeyFile(uri)
                    addIdentity(jsch, keyBytes)
                }

                currentSession = jsch.getSession(config.username, config.host, config.port).apply {
                    setConfig("StrictHostKeyChecking", "no")
                    config.password?.let { setPassword(it) }
                    connect(30000)
                }

                withContext(Dispatchers.Main) {
                    btnConnect.text = getString(R.string.disconnect)
                    Toast.makeText(this@MainActivity, getString(R.string.connection_success), Toast.LENGTH_SHORT).show()
                }

                startMonitoring()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.connection_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectSSH() {
        monitoringJob?.cancel()
        currentSession?.disconnect()
        currentSession = null

        runOnUiThread {
            btnConnect.text = getString(R.string.connect)
            Toast.makeText(this, getString(R.string.disconnected), Toast.LENGTH_SHORT).show()
            resetCharts()
            tvUptime.text = getString(R.string.uptime_placeholder)
        }
    }

    private fun resetCharts() {
        chartCPU.clear()
        chartMemory.clear()
        chartDisk.clear()

        setupChart(chartCPU, "CPU", Color.RED)
        setupChart(chartMemory, "MEM", Color.GREEN)
        setupChart(chartDisk, "DISK", Color.BLUE)

        tvUptime.text = getString(R.string.uptime_placeholder)
    }
    private fun startMonitoring() {
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    val cpuUsage = getCPUUsage()
                    val memoryUsage = getMemoryUsage()
                    val diskUsage = getDiskUsage()
                    val uptime = getUptime()
                    updateCharts(cpuUsage, memoryUsage, diskUsage)
                    updateUptime(uptime)
                    delay(500)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.monitoring_error, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                disconnectSSH()
            }
        }
    }


    private fun getCPUUsage(): Float {
        return executeCommand(
            "top -bn1 | grep \"Cpu(s)\" | " +
                    "sed \"s/.*, *\\([0-9.]*\\)%* id.*/\\1/\" | " +
                    "awk '{print 100 - \$1}'"
        ).toFloatOrNull() ?: 0f
    }

    private fun getMemoryUsage(): Float {
        return executeCommand(
            "free | grep Mem | awk '{print \$3/\$2 * 100.0}'"
        ).toFloatOrNull() ?: 0f
    }

    private fun getDiskUsage(): Float {
        return executeCommand(
            "df / | tail -1 | awk '{print \$5}' | sed 's/%//'"
        ).toFloatOrNull() ?: 0f
    }
    private fun getUptime(): String {
        return executeCommand("uptime -p")
    }

    private fun executeCommand(command: String): String {
        val channel = currentSession?.openChannel("exec") as? ChannelExec
            ?: throw IllegalStateException("セッションが切断されました")

        return try {
            channel.setCommand(command)
            val output = ByteArrayOutputStream()
            channel.outputStream = output
            channel.connect()

            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            output.toString().trim()
        } finally {
            channel.disconnect()
        }
    }

    private suspend fun updateCharts(cpuUsage: Float, memoryUsage: Float, diskUsage: Float) {
        withContext(Dispatchers.Main) {
            updateChart(chartCPU, cpuUsage)
            updateChart(chartMemory, memoryUsage)
            updateChart(chartDisk, diskUsage)
        }
    }

    private suspend fun updateUptime(uptime: String) {
        withContext(Dispatchers.Main) {
            tvUptime.text = getString(R.string.uptime_format, uptime)
        }
    }

    private fun updateChart(chart: LineChart, value: Float) {
        val data = chart.data
        val dataSet = data.getDataSetByIndex(0) as LineDataSet
        data.addEntry(Entry(dataSet.entryCount.toFloat(), value), 0)
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(60f)
        chart.moveViewToX(data.entryCount.toFloat())
    }

    private fun readKeyFile(uri: Uri): ByteArray {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("ファイルを開けません")
        return inputStream.use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toByteArray()
        }
    }

    private fun addIdentity(jsch: JSch, keyBytes: ByteArray) {
        val keyString = String(keyBytes)

        try {
            when {
                keyString.contains("BEGIN OPENSSH PRIVATE KEY") ||
                        keyString.contains("BEGIN RSA PRIVATE KEY") ||
                        keyString.contains("BEGIN DSA PRIVATE KEY") ||
                        keyString.contains("BEGIN EC PRIVATE KEY") ||
                        keyString.contains("BEGIN PRIVATE KEY") -> {
                    val tempFile = createTempKeyFile(keyBytes)
                    try {
                        jsch.addIdentity(tempFile.absolutePath)
                    } finally {
                        tempFile.delete()
                    }
                }
                else -> {
                    throw IllegalArgumentException("未対応の鍵フォーマットです")
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("鍵の処理中にエラーが発生しました: ${e.message}", e)
        }
    }

    private fun createTempKeyFile(keyBytes: ByteArray): File {
        val tempFile = File.createTempFile("ssh", "key", cacheDir)
        tempFile.setReadable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(false, false)
        tempFile.setWritable(true, true)
        tempFile.setExecutable(false, false)
        tempFile.deleteOnExit()

        FileOutputStream(tempFile).use { it.write(keyBytes) }
        return tempFile
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_add_server -> showAddServerDialog()
            R.id.nav_remove_server -> showRemoveServerDialog()
            R.id.nav_language -> toggleLanguage()
            R.id.nav_donate -> showDonateDialog()
            R.id.nav_wifi_scan -> {
                WifiScanDialog(this) { ipAddress, hostname ->
                    // スキャン結果から新規サーバー追加ダイアログを表示
                    val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
                    dialogView.findViewById<EditText>(R.id.etHost).setText(ipAddress)
                    dialogView.findViewById<EditText>(R.id.etPort).setText("22")

                    AlertDialog.Builder(this)
                        .setTitle("サーバーを追加")
                        .setView(dialogView)
                        // 既存の追加処理と同じ
                        .show()
                }.show()
            }
            else -> {
                val serverIndex = item.itemId - Menu.FIRST
                if (serverIndex in serverConfigs.indices) {
                    spinnerServers.setSelection(serverIndex)
                    if (currentSession != null) {
                        disconnectSSH()
                        connectSSH(serverConfigs[serverIndex])
                    }
                }
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSSH()
    }
}