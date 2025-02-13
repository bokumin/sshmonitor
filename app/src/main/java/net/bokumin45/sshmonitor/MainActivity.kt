package net.bokumin45.sshmonitor

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
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
        return "$host (${username})"
    }
}

class ServerConfigManager(private val context: Context) {
    private val sharedPreferences = context.getSharedPreferences("ServerConfigs", Context.MODE_PRIVATE)
    private val uriPermissionManager = UriPermissionManager(context)

    fun saveServerConfig(config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        config.privateKeyUri?.let { uri ->
            uriPermissionManager.takePersistablePermission(uri)
        }
        configs.add(config)
        saveConfigs(configs)
    }


    fun updateServerConfig(index: Int, config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        configs[index].privateKeyUri?.let { oldUri ->
            uriPermissionManager.releasePersistablePermission(oldUri)
        }
        // 新しい鍵ファイルのURIに対して永続的なパーミッションを取得
        config.privateKeyUri?.let { newUri ->
            uriPermissionManager.takePersistablePermission(newUri)
        }
        configs[index] = config
        saveConfigs(configs)
    }

    fun removeServerConfig(config: ServerConfig) {
        val configs = getServerConfigs().toMutableList()
        config.privateKeyUri?.let { uri ->
            uriPermissionManager.releasePersistablePermission(uri)
        }
        configs.remove(config)
        saveConfigs(configs)
    }

    private fun saveConfigs(configs: List<ServerConfig>) {
        sharedPreferences.edit().putString("configs", configs.joinToString("|") {
            "${it.host},${it.port},${it.username},${it.privateKeyUri},${it.password}"
        }).apply()
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
}

class UriPermissionManager(private val context: Context) {
    fun takePersistablePermission(uri: Uri) {
        try {
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }

            if (!hasPermission) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (e: SecurityException) {
            Log.e("UriPermissionManager", "Failed to take permission for URI: $uri", e)
        }
    }

    fun releasePersistablePermission(uri: Uri) {
        try {
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }

            if (hasPermission) {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (e: SecurityException) {
            Log.e("UriPermissionManager", "Failed to release permission for URI: $uri", e)
        }
    }
}

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var spinnerServers: Spinner
    private lateinit var btnConnect: Button
    private lateinit var chartCPU: LineChart
    private lateinit var chartGPU: LineChart
    private lateinit var chartMemory: LineChart
    private lateinit var chartDisk: LineChart
    private lateinit var tvUptime: TextView

    private val gson = Gson()
    private lateinit var serverConfigManager: ServerConfigManager
    private var serverConfigs: MutableList<ServerConfig> = mutableListOf()
    private var currentSession: Session? = null
    private var monitoringJob: Job? = null
    private var selectedKeyUri: Uri? = null

    private val REQUEST_CODE_OPEN_FILE = 1

    private var channels: MutableList<ChannelExec> = mutableListOf()
    private var backgroundJob: Job? = null
    private var isInBackground = false
    private var currentDialogView: View? = null

    private var loadingDialog: AlertDialog? = null

    private var graphSettings = listOf(
        GraphSetting("CPU", true, 0),
        GraphSetting("Memory", true, 1),
        GraphSetting("Disk", true, 2),
        GraphSetting("GPU", true, 3)
    )
    private data class GraphView(
        val name: String,
        val chart: LineChart,
        var order: Int
    )

    private lateinit var graphViews: List<GraphView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val savedLanguage = getSharedPreferences("Settings", MODE_PRIVATE)
            .getString("language", null)

        if (savedLanguage != null) {
            val locale = Locale(savedLanguage)
            Locale.setDefault(locale)
            val config = resources.configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            resources.updateConfiguration(config, resources.displayMetrics)
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Security.addProvider(BouncyCastleProvider())

        serverConfigManager = ServerConfigManager(this)
        serverConfigs = serverConfigManager.getServerConfigs().toMutableList()

        initializeViews()
        setupToolbar()
        loadGraphSettings()
        updateGraphVisibility()
        setupDrawer()
        setupListeners()
        setupCharts()
        updateServerSpinner()
        initializeGraphViews()
        loadGraphSettings()
        updateGraphVisibility()
        setupUptimeCard()

    }
    private fun setupUptimeCard() {
        val uptimeHeader = findViewById<LinearLayout>(R.id.uptimeHeader)
        val uptimeContent = findViewById<LinearLayout>(R.id.uptimeContent)
        val expandIcon = findViewById<ImageView>(R.id.expandIcon)

        var isExpanded = false

        uptimeHeader.setOnClickListener {
            val rotateAnimation = if (isExpanded) {
                ObjectAnimator.ofFloat(expandIcon, "rotation", 0f, 180f)
            } else {
                ObjectAnimator.ofFloat(expandIcon, "rotation", 180f, 0f)
            }
            rotateAnimation.duration = 200
            rotateAnimation.start()

            if (isExpanded) {
                uptimeContent.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        uptimeContent.visibility = View.GONE
                    }
            } else {
                uptimeContent.visibility = View.VISIBLE
                uptimeContent.alpha = 0f
                uptimeContent.animate()
                    .alpha(1f)
                    .setDuration(200)
            }

            isExpanded = !isExpanded
        }
    }
    private fun animateToolbarBackground() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        val colorFrom = ContextCompat.getColor(this, R.color.black)
        val colorTo = ContextCompat.getColor(this, R.color.purple_200)

        ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo).apply {
            duration = 2000
            addUpdateListener { animator ->
                toolbar.setBackgroundColor(animator.animatedValue as Int)
            }

            startDelay = 600
            start()
        }
    }
    private fun initializeGraphViews() {
        graphViews = listOf(
            GraphView("CPU", chartCPU, 0),
            GraphView("Memory", chartMemory, 1),
            GraphView("Disk", chartDisk, 2),
            GraphView("GPU", chartGPU, 3)
        )
    }
    private fun initializeViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        spinnerServers = findViewById(R.id.spinnerServers)
        btnConnect = findViewById(R.id.btnConnect)
        chartCPU = findViewById(R.id.chartCPU)
        chartGPU = findViewById(R.id.chartGPU)
        chartMemory = findViewById(R.id.chartMemory)
        chartDisk = findViewById(R.id.chartDisk)
        tvUptime = findViewById(R.id.tvUptime)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_graph_settings -> {
                showGraphSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showGraphSettingsDialog() {
        DialogGraphSettings(this, graphSettings) { newSettings ->
            graphSettings = newSettings
            saveGraphSettings()
            updateGraphVisibility()
        }.show()
    }

    private fun updateGraphVisibility() {
        val orderedGraphs = graphSettings.sortedBy { it.order }
        val parentLayout = chartCPU.parent as? LinearLayout ?: return

        parentLayout.removeView(chartCPU)
        parentLayout.removeView(chartMemory)
        parentLayout.removeView(chartDisk)
        parentLayout.removeView(chartGPU)

        orderedGraphs.forEach { setting ->
            val chart = when (setting.name) {
                "CPU" -> {
                    chartCPU.visibility = if (setting.isVisible) View.VISIBLE else View.GONE
                    chartCPU
                }
                "Memory" -> {
                    chartMemory.visibility = if (setting.isVisible) View.VISIBLE else View.GONE
                    chartMemory
                }
                "Disk" -> {
                    chartDisk.visibility = if (setting.isVisible) View.VISIBLE else View.GONE
                    chartDisk
                }
                "GPU" -> {
                    chartGPU.visibility = if (setting.isVisible) View.VISIBLE else View.GONE
                    chartGPU
                }
                else -> null
            }

            chart?.let { parentLayout.addView(it) }
        }
    }

    private fun saveGraphSettings() {
        val sharedPrefs = getSharedPreferences("GraphSettings", MODE_PRIVATE)
        val settingsJson = gson.toJson(graphSettings)
        sharedPrefs.edit().putString("settings", settingsJson).apply()
    }

    private fun loadGraphSettings() {
        val sharedPrefs = getSharedPreferences("GraphSettings", Context.MODE_PRIVATE)
        val settingsJson = sharedPrefs.getString("settings", null)
        if (settingsJson != null) {
            graphSettings = gson.fromJson(settingsJson, Array<GraphSetting>::class.java).toList()
        }
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
                Toast.makeText(this, getString(R.string.a_server_select), Toast.LENGTH_SHORT).show()
            }
        }

        spinnerServers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (currentSession != null) {
                    disconnectSSH()
                }
                resetCharts()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }
    private fun setupChart(chart: LineChart, label: String, color1: Int, color2: Int? = null) {
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

                if (label.contains("DISK")) {
                    setDrawZeroLine(true)
                    axisMinimum = 0f
                    spaceTop = 100f

                    isAutoScaleMinMaxEnabled = true
                } else {
                    axisMinimum = 0f
                    axisMaximum = 100f
                }
            }

            axisRight.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            val entries = ArrayList<Entry>()

            val dataSet1 = LineDataSet(entries, if (label.contains("DISK")) "Read" else "Value").apply {
                setDrawValues(false)
                setDrawCircles(false)
                lineWidth = 2f
                color = color1
            }

            val dataSets = ArrayList<ILineDataSet>()
            dataSets.add(dataSet1)

            if (color2 != null) {
                val dataSet2 = LineDataSet(ArrayList(), if (label.contains("DISK")) "Write" else "Value").apply {
                    setDrawValues(false)
                    setDrawCircles(false)
                    lineWidth = 2f
                    color = color2
                }
                dataSets.add(dataSet2)
            }

            data = LineData(dataSets)
        }
    }
    private fun setupCharts() {
        setupChart(chartCPU, "CPU Usage (%)", Color.RED)
        setupChart(chartMemory, "Memory Usage (%)", Color.GREEN)
        setupChart(chartDisk, "Disk I/O (blocks/s)", Color.BLUE, Color.CYAN)
        setupChart(chartGPU, "GPU Usage (%)", Color.MAGENTA)
    }
    private fun updateServerSpinner() {
        if (serverConfigs.isEmpty()) {
            spinnerServers.visibility = View.GONE

            findViewById<Button>(R.id.btnAddServer).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    showAddServerDialog()
                }
            }
        } else {
            spinnerServers.visibility = View.VISIBLE
            findViewById<Button>(R.id.btnAddServer).visibility = View.GONE

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, serverConfigs)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerServers.adapter = adapter

            spinnerServers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (currentSession != null) {
                        disconnectSSH()
                    }
                    resetCharts()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

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

        menu.add(Menu.NONE, R.id.nav_wifi_scan, Menu.NONE, getString(R.string.wifi_scan))
        menu.add(Menu.NONE, R.id.nav_add_server, Menu.NONE, getString(R.string.add_server))
        menu.add(Menu.NONE, R.id.nav_remove_server, Menu.NONE, getString(R.string.remove_server))
        menu.add(Menu.NONE, R.id.nav_language, Menu.NONE, getString(R.string.change_language))
        menu.add(Menu.NONE, R.id.nav_donate, Menu.NONE, getString(R.string.donate))
    }

    private fun showEditServerDialog(index: Int) {
        val config = serverConfigs[index]
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        currentDialogView = dialogView
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
            getString(R.string.select_file) + ": ${DocumentFile.fromSingleUri(this, selectedKeyUri!!)?.name}"
        } else {
            getString(R.string.not_select_file)
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
            .setTitle(getString(R.string.edit_server))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.update)) { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 22
                val username = etUsername.text.toString()
                val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                if (host.isNotEmpty() && username.isNotEmpty()) {
                    val updatedConfig = ServerConfig(host, port, username, selectedKeyUri, password)
                    serverConfigs[index] = updatedConfig
                    serverConfigManager.updateServerConfig(index, updatedConfig)
                    updateServerSpinner()
                    spinnerServers.setSelection(index)
                    Toast.makeText(this, getString(R.string.update_server), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.require_host_user), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                currentDialogView = null
            }
            .setOnDismissListener {
                currentDialogView = null
            }
            .show()
    }

    private fun showAddServerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        currentDialogView = dialogView
        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKey)
        val tvSelectedKey = dialogView.findViewById<TextView>(R.id.tvSelectedKey)

        selectedKeyUri = null
        tvSelectedKey.text = getString(R.string.not_select_file)

        btnSelectKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_FILE)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_server))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 22
                val username = etUsername.text.toString()
                val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                if (host.isNotEmpty() && username.isNotEmpty()) {
                    val config = ServerConfig(host, port, username, selectedKeyUri, password)
                    serverConfigs.add(config)
                    serverConfigManager.saveServerConfig(config)
                    updateServerSpinner()
                } else {
                    Toast.makeText(this, getString(R.string.require_host_user), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                currentDialogView = null
            }
            .setOnDismissListener {
                currentDialogView = null
            }
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
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }

        val newLocale = if (currentLocale.language == "en") Locale("ja") else Locale("en")

        val config = resources.configuration
        Locale.setDefault(newLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(newLocale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = newLocale
        }

        resources.updateConfiguration(config, resources.displayMetrics)

        val editor = getSharedPreferences("Settings", MODE_PRIVATE).edit()
        editor.putString("language", newLocale.language)
        editor.apply()

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

                currentDialogView?.findViewById<TextView>(R.id.tvSelectedKey)?.let { textView ->
                    textView.text = getString(R.string.select_file) + ": $fileName"
                }
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                selectedKeyUri = uri
            }
        }
    }

    private fun showLoadingDialog(message: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null)
        dialogView.findViewById<TextView>(R.id.loadingText).text = message

        loadingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
    private fun connectSSH(config: ServerConfig) {
        if (currentSession != null) {
            disconnectSSH()
            return
        }

        showLoadingDialog(getString(R.string.connecting))

        resetCharts()
        cancelBackgroundDisconnect()

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
                    hideLoadingDialog() // 接続成功時にダイアログを非表示
                    btnConnect.text = getString(R.string.disconnect)
                    Toast.makeText(this@MainActivity, getString(R.string.connection_success), Toast.LENGTH_SHORT).show()
                }

                startMonitoring()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoadingDialog() // エラー時にダイアログを非表示
                    btnConnect.text = getString(R.string.connect)
                    Toast.makeText(this@MainActivity, getString(R.string.connection_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectSSH() {
        showLoadingDialog(getString(R.string.disconnecting))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                monitoringJob?.cancel()

                synchronized(channels) {
                    channels.forEach { channel ->
                        try {
                            if (channel.isConnected) {
                                channel.disconnect()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    channels.clear()
                }

                currentSession?.let { session ->
                    if (session.isConnected) {
                        session.disconnect()
                    }
                }
                currentSession = null

                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    btnConnect.text = getString(R.string.connect)
                    Toast.makeText(this@MainActivity, getString(R.string.disconnected), Toast.LENGTH_SHORT).show()
                    resetCharts()
                    tvUptime.text = getString(R.string.uptime_placeholder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    Toast.makeText(this@MainActivity, "Disconnect error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startBackgroundDisconnectTimer() {
        cancelBackgroundDisconnect()

        backgroundJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                delay(60000)
                if (isInBackground) {
                    disconnectSSH()
                }
            } catch (e: CancellationException) {
            }
        }
    }

    private fun cancelBackgroundDisconnect() {
        backgroundJob?.cancel()
        backgroundJob = null
        isInBackground = false
    }

    private fun resetCharts() {
        chartCPU.clear()
        chartMemory.clear()
        chartDisk.clear()
        chartGPU.clear()

        setupChart(chartCPU, "CPU", Color.RED)
        setupChart(chartMemory, "MEM", Color.GREEN)
        setupChart(chartDisk, "DISK", Color.BLUE)
        setupChart(chartGPU, "GPU", Color.MAGENTA)

        tvUptime.text = getString(R.string.uptime_placeholder)
    }

    private fun getGPUUsage(): Float {
        // NVIDIAのGPUの場合
        val nvidiaSmiOutput = executeCommand("which nvidia-smi").trim()
        if (nvidiaSmiOutput.isNotEmpty()) {
            return executeCommand(
                "nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader,nounits"
            )
                .split(",")
                .map { it.trim() }
                .let { values ->
                    when (values.size) {
                        3 -> {
                            val gpuUtil = values[0].toFloatOrNull()
                            val memoryUsed = values[1].toFloatOrNull() ?: 0f
                            val memoryTotal = values[2].toFloatOrNull() ?: 1f
                            gpuUtil ?: (memoryUsed / memoryTotal * 100f)
                        }
                        else -> 0f
                    }
                }
        }

        // Intel GPUの場合
        val intelGpuTopOutput = executeCommand("which intel_gpu_top").trim()
        if (intelGpuTopOutput.isNotEmpty()) {
            return executeCommand(
                "intel_gpu_top -s 1 -o - | head -n 2 | tail -n 1 | awk '{print $2}'"
            ).toFloatOrNull() ?: 0f
        }

        return 0f
    }

    private fun startMonitoring() {
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    val cpuUsage = getCPUUsage()
                    val memoryUsage = getMemoryUsage()
                    val diskUsage = getDiskUsage()
                    val gpuUsage = getGPUUsage()
                    val uptime = getUptime()
                    updateCharts(cpuUsage, memoryUsage, diskUsage,gpuUsage)
                    updateUptime(uptime)
                    delay(200)
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

    private fun getDiskUsage(): Pair<Float, Float> {
        return try {
            val vmstatOutput = executeCommand(
                "vmstat 1 2 | tail -n 1 | awk '{print \$9, \$10}'"
            )
            val parts = vmstatOutput.trim().split("\\s+".toRegex())

            val bi = parts[0].toFloatOrNull() ?: 0f
            val bo = parts[1].toFloatOrNull() ?: 0f

            Pair(bi, bo)
        } catch (e: Exception) {
            Pair(0f, 0f)
        }
    }
    private fun getUptime(): String {
        val uptimeOutput = executeCommand("""
        uptime | 
        sed 's/.*up\s*//' | 
        sed 's/,\s*[0-9]* user.*$//' | 
        awk '{printf "%s\nLoad Average: ", $0}' && 
        uptime | 
        grep -o 'load average:.*' | 
        sed 's/load average: //' | 
        tr '\n' ' ' && 
        uptime | 
        grep -o '[0-9]* user' | 
        awk '{printf ", Users: %s", $1}'
    """.trimIndent())

        val diskUsageOutput = executeCommand("""
    df | 
    awk '!/^(tmpfs|devtmpfs|udev|\/dev\/loop)/ && !seen[$1]++ {
        total+=$2;
        used+=$3
    } END {
        units[0] = "K"; units[1] = "M"; units[2] = "G"; 
        units[3] = "T"; units[4] = "P"; units[5] = "E"; 
        units[6] = "Z"; units[7] = "Y";
        
        unitIndex = 0;
        convertedTotal = total;
        convertedUsed = used;
        
        while (convertedTotal >= 1024 && unitIndex < 7) {
            convertedTotal /= 1024;
            convertedUsed /= 1024;
            unitIndex++;
        }
        
        printf "%.2f%s/%.2f%s (%.1f%%)", 
        convertedUsed, units[unitIndex], 
        convertedTotal, units[unitIndex], 
        (convertedUsed/convertedTotal)*100
    }'
""".trimIndent())

        return "$uptimeOutput\nDisk: $diskUsageOutput"
    }

    private fun executeCommand(command: String): String {
        val channel = currentSession?.openChannel("exec") as? ChannelExec
            ?: throw IllegalStateException(getString(R.string.session_disconnected))

        return try {
            synchronized(channels) {
                channels.add(channel)
            }

            channel.setCommand(command)
            val output = ByteArrayOutputStream()
            channel.outputStream = output
            channel.connect()

            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            output.toString().trim()
        } finally {
            try {
                if (channel.isConnected) {
                    channel.disconnect()
                }
                synchronized(channels) {
                    channels.remove(channel)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        cancelBackgroundDisconnect()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            isInBackground = true
            startBackgroundDisconnectTimer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            cancelBackgroundDisconnect()
            disconnectSSH()
        }
    }

    private suspend fun updateCharts(cpuUsage: Float, memoryUsage: Float, diskUsage: Pair<Float, Float>, gpuUsage: Float) {
        withContext(Dispatchers.Main) {
            updateChart(chartCPU, cpuUsage)
            updateChart(chartMemory, memoryUsage)
            updateDiskChart(chartDisk, diskUsage.first, diskUsage.second)
            updateChart(chartGPU, gpuUsage)
        }
    }
    private fun updateDiskChart(chart: LineChart, biValue: Float, boValue: Float) {
        val data = chart.data ?: return

        while (data.dataSets.size < 2) {
            val newDataSet = LineDataSet(ArrayList<Entry>(), "Write").apply {
                setDrawValues(false)
                setDrawCircles(false)
                lineWidth = 2f
                color = Color.CYAN
            }
            data.addDataSet(newDataSet)
        }

        val biDataSet = data.getDataSetByIndex(0) as? LineDataSet ?: return
        val boDataSet = data.getDataSetByIndex(1) as? LineDataSet ?: return

        data.addEntry(Entry(biDataSet.entryCount.toFloat(), biValue), 0)
        data.addEntry(Entry(boDataSet.entryCount.toFloat(), boValue), 1)

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(60f)
        chart.moveViewToX(data.entryCount.toFloat())

        chart.axisLeft.resetAxisMinimum()
        chart.axisLeft.resetAxisMaximum()
        chart.axisLeft.setDrawZeroLine(true)
    }

    private suspend fun updateUptime(uptime: String) {
        withContext(Dispatchers.Main) {
            tvUptime.text = getString(R.string.uptime_format, uptime)
        }
    }

    private fun updateChart(chart: LineChart, value: Float) {
        val data = chart.data ?: return
        if (data.dataSets.isEmpty()) return

        val dataSet = data.getDataSetByIndex(0) as? LineDataSet ?: return
        data.addEntry(Entry(dataSet.entryCount.toFloat(), value), 0)

        data.notifyDataChanged()
        chart.notifyDataSetChanged()

        chart.setVisibleXRangeMaximum(60f)
        chart.moveViewToX(data.entryCount.toFloat())
    }

    private fun readKeyFile(uri: Uri): ByteArray {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(getString(R.string.cant_open_file))
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
                    throw IllegalArgumentException(getString(R.string.unsupported_key))
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(getString(R.string.key_error)+": ${e.message}", e)
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
                    val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
                    val etHost = dialogView.findViewById<EditText>(R.id.etHost)
                    val etPort = dialogView.findViewById<EditText>(R.id.etPort)
                    val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
                    val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
                    val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKey)
                    val tvSelectedKey = dialogView.findViewById<TextView>(R.id.tvSelectedKey)

                    etHost.setText(ipAddress)
                    etPort.setText("22")
                    selectedKeyUri = null
                    tvSelectedKey.text = getString(R.string.no_key_selected)

                    btnSelectKey.setOnClickListener {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        startActivityForResult(intent, REQUEST_CODE_OPEN_FILE)

                    }

                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.add_server))
                        .setView(dialogView)
                        .setPositiveButton(getString(R.string.add)) { _, _ ->
                            val host = etHost.text.toString()
                            val port = etPort.text.toString().toIntOrNull() ?: 22
                            val username = etUsername.text.toString()
                            val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                            if (host.isNotEmpty() && username.isNotEmpty()) {
                                val config = ServerConfig(host, port, username, selectedKeyUri, password)
                                serverConfigs.add(config)
                                serverConfigManager.saveServerConfig(config)
                                updateServerSpinner()
                            } else {
                                Toast.makeText(this, getString(R.string.require_host_user), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
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
        currentDialogView = null
    }
}