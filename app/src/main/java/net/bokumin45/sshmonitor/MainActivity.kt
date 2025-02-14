package net.bokumin45.sshmonitor

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    var password: String? = null,
    var jumpHostServer: ServerConfig? = null,
    var isJumpHost: Boolean = false
) {
    override fun toString(): String {
        val prefix = if (isJumpHost) "🔄 " else ""
        return "$prefix$host (${username})"
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

        val oldUri = configs[index].privateKeyUri
        if (oldUri != null && oldUri != config.privateKeyUri) {
            uriPermissionManager.releasePersistablePermission(oldUri)
        }

        config.privateKeyUri?.let { newUri ->
            if (newUri != oldUri) {
                uriPermissionManager.takePersistablePermission(newUri)
            }
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
            buildString {
                append("${it.host},${it.port},${it.username}")
                append(",${it.privateKeyUri?.toString() ?: "null"}")
                append(",${it.password ?: "null"}")
                append(",${it.isJumpHost}")
                append(",${it.jumpHostServer?.let { jump ->
                    "${jump.host},${jump.port},${jump.username}," +
                            "${jump.privateKeyUri?.toString() ?: "null"}," +
                            "${jump.password ?: "null"}"
                } ?: "null"}")
            }
        }).apply()
    }

    fun getServerConfigs(): List<ServerConfig> {
        val configString = sharedPreferences.getString("configs", "") ?: ""
        val configs = mutableListOf<ServerConfig>()
        val jumpHostMap = mutableMapOf<String, ServerConfig>()

        configString.split("|").filter { it.isNotEmpty() }.forEach { configStr ->
            val parts = configStr.split(",")
            if (parts.size >= 6) {
                val mainConfig = ServerConfig(
                    host = parts[0],
                    port = parts[1].toInt(),
                    username = parts[2],
                    privateKeyUri = parts[3].takeIf { it != "null" }?.let { Uri.parse(it) },
                    password = parts[4].takeIf { it != "null" },
                    isJumpHost = parts[5].toBoolean()
                )

                if (parts.size > 6 && parts[6] != "null") {
                    val jumpParts = parts.subList(6, parts.size)
                    if (jumpParts.size >= 5) {
                        val jumpConfig = ServerConfig(
                            host = jumpParts[0],
                            port = jumpParts[1].toInt(),
                            username = jumpParts[2],
                            privateKeyUri = jumpParts[3].takeIf { it != "null" }?.let { Uri.parse(it) },
                            password = jumpParts[4].takeIf { it != "null" }
                        )
                        mainConfig.jumpHostServer = jumpConfig
                    }
                }

                configs.add(mainConfig)
            }
        }

        return configs
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
    private lateinit var graphContainer: ViewGroup
    private lateinit var terminalContainer: ViewGroup

    private val gson = Gson()
    private lateinit var serverConfigManager: ServerConfigManager
    private var serverConfigs: MutableList<ServerConfig> = mutableListOf()
    private var currentSession: Session? = null
    private var jumpSession: Session? = null
    private var monitoringJob: Job? = null
    private var selectedKeyUri: Uri? = null

    private val REQUEST_CODE_OPEN_FILE = 1

    private var channels: MutableList<ChannelExec> = mutableListOf()
    private var backgroundJob: Job? = null
    private var isInBackground = false
    private var currentDialogView: View? = null

    private var loadingDialog: AlertDialog? = null

    private lateinit var commandInput: EditText
    private lateinit var terminalOutput: TextView
    private var commandHistory = mutableListOf<String>()
    private var historyPosition = -1
    private var currentInputBeforeHistory: String = ""
    private val prompt = "$ "
    private var currentCommand: ChannelExec? = null
    private var commandJob: Job? = null

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
        title = ""
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
        setupButtons()

        graphContainer = findViewById(R.id.graphContainer)
        terminalContainer = findViewById(R.id.terminalContainer)
        btnConnect = findViewById(R.id.btnConnect)
        commandInput = findViewById(R.id.commandInput)
        terminalOutput = findViewById(R.id.terminalOutput)

        findViewById<Button>(R.id.btnTerminal).setOnClickListener {
            showTerminal()
        }

        btnConnect.setOnClickListener {
            if (isGraphMode()) {
                val selectedServer = spinnerServers.selectedItem as? ServerConfig
                if (selectedServer != null) {
                    if (currentSession == null) {
                        connectSSH(selectedServer)
                    } else {
                        disconnectSSH()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.a_server_select), Toast.LENGTH_SHORT)
                        .show()
                }
            } else {
                showGraphs()
            }
        }
    }

    private fun isGraphMode(): Boolean {
        return graphContainer.visibility == View.VISIBLE
    }

    private fun showGraphs() {
        isTerminalMode = false
        graphContainer.visibility = View.VISIBLE
        terminalContainer.visibility = View.GONE
        btnConnect.text = getString(R.string.connect)
    }
    private fun showTerminal() {
        isTerminalMode = true
        graphContainer.visibility = View.GONE
        terminalContainer.visibility = View.VISIBLE
        btnConnect.text = getString(R.string.graph)
        commandInput.requestFocus()
    }


    private lateinit var terminalScrollView: ScrollView
    private var isTerminalMode = false
    private var currentTerminalChannel: ChannelExec? = null
    private val terminalBuffer = StringBuilder()
    private val TERMINAL_BUFFER_MAX_SIZE = 1000000 // バッファの最大サイズ

    private fun setupTerminal() {
        if (!::commandInput.isInitialized || !::terminalOutput.isInitialized) {
            commandInput = findViewById(R.id.commandInput)
            terminalOutput = findViewById(R.id.terminalOutput)
        }

        commandInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null &&
                        event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
            ) {
                val command = v.text.toString().trim()
                if (command.isNotEmpty()) {
                    executeTerminalCommand(command)
                    v.text=""
                }
                true
            } else {
                false
            }
        }

        commandInput.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when {
                    keyCode == KeyEvent.KEYCODE_ENTER -> {
                        val command = (v as EditText).text.toString().trim()
                        if (command.isNotEmpty()) {
                            executeTerminalCommand(command)
                            v.text.clear()
                        }
                        true
                    }
                    event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_C -> {
                        terminateCurrentCommand()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        commandInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                commandInput.postDelayed({
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT)
                }, 200)
            }
        }
    }

    private fun executeTerminalCommand(command: String) {
        if (currentSession?.isConnected != true) {
            appendToTerminal("Not connected to server\n", Color.RED)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channel = currentSession?.openChannel("exec") as? ChannelExec ?: return@launch
                currentTerminalChannel = channel

                channel.setPtyType("xterm")
                channel.setPty(true)
                channel.setCommand(command)

                val inputStream = channel.inputStream
                val errorStream = channel.errStream

                channel.connect()

                withContext(Dispatchers.Main) {
                    appendToTerminal("$ $command\n", Color.GREEN)
                }

                val buffer = ByteArray(1024)
                while (channel.isConnected) {
                    val readCount = inputStream.read(buffer)
                    if (readCount == -1) break

                    val output = String(buffer, 0, readCount)
                    withContext(Dispatchers.Main) {
                        appendToTerminal(output)
                    }
                }

                // エラー出力の読み取り
                val errorOutput = errorStream.bufferedReader().readText()
                if (errorOutput.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(errorOutput, Color.RED)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendToTerminal("Error: ${e.message}\n", Color.RED)
                }
            } finally {
                currentTerminalChannel?.disconnect()
                currentTerminalChannel = null
            }
        }
    }

    private fun appendToTerminal(text: String, color: Int = Color.WHITE) {
        val spannableString = SpannableStringBuilder(text)
        spannableString.setSpan(
            ForegroundColorSpan(color),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        terminalBuffer.append(text)
        // バッファサイズの制限
        if (terminalBuffer.length > TERMINAL_BUFFER_MAX_SIZE) {
            terminalBuffer.delete(0, terminalBuffer.length - TERMINAL_BUFFER_MAX_SIZE)
        }

        terminalOutput.append(spannableString)
        scrollToBottom()
    }

    private fun terminateCurrentCommand() {
        currentTerminalChannel?.let { channel ->
            channel.sendSignal("INT")
            appendToTerminal("^C\n", Color.RED)
        }
    }


    override fun onBackPressed() {
        if (isTerminalMode) {
            showGraphs()
        } else {
            super.onBackPressed()
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return

        if (historyPosition == commandHistory.size) {
            currentInputBeforeHistory = commandInput.text.toString()
        }

        historyPosition += direction

        when {
            historyPosition < 0 -> historyPosition = 0
            historyPosition >= commandHistory.size -> {
                historyPosition = commandHistory.size
                commandInput.setText(currentInputBeforeHistory)
            }
            else -> commandInput.setText(commandHistory[historyPosition])
        }

        commandInput.setSelection(commandInput.length())
    }

    private fun handleCtrlKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_C) {
            currentCommand?.let {
                it.disconnect()
                appendOutput("^C\n")
                showPrompt()
            }
            return true
        }
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_L) {
            terminalOutput.text = ""
            showPrompt()
            return true
        }
        return false
    }


    private fun showPrompt() {
        val spannableString = SpannableStringBuilder()
        val promptSpan = ForegroundColorSpan(Color.GREEN)
        spannableString.append(prompt)
        spannableString.setSpan(
            promptSpan,
            0,
            prompt.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendToOutput(spannableString)
    }

    private fun appendOutput(text: String) {
        appendToOutput(SpannableStringBuilder(text))
    }

    private fun appendError(text: String) {
        val spannableString = SpannableStringBuilder(text)
        spannableString.setSpan(
            ForegroundColorSpan(Color.RED),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendToOutput(spannableString)
    }

    private fun appendToOutput(text: CharSequence) {
        terminalOutput.append(text)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val scrollView = terminalOutput.parent as? ScrollView
        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
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
            serverListSubmenu.add(Menu.NONE, Menu.FIRST + index, Menu.NONE, config.toString())
                .setOnMenuItemClickListener {
                    showEditServerDialog(index)
                    true
                }
        }

        menu.add(Menu.NONE, R.id.nav_add_server, Menu.NONE, getString(R.string.add_server))
        menu.add(Menu.NONE, R.id.nav_add_jump_host, Menu.NONE, getString(R.string.add_jump_host))
        menu.add(Menu.NONE, R.id.nav_wifi_scan, Menu.NONE, getString(R.string.wifi_scan))
        menu.add(Menu.NONE, R.id.nav_remove_server, Menu.NONE, getString(R.string.remove_server))
        menu.add(Menu.NONE, R.id.nav_language, Menu.NONE, getString(R.string.change_language))
        menu.add(Menu.NONE, R.id.nav_donate, Menu.NONE, getString(R.string.donate))
    }

    @SuppressLint("StringFormatInvalid")
    private fun showEditServerDialog(index: Int) {
        val config = serverConfigs[index]
        val inflater = LayoutInflater.from(this)
        val dialogView = if (config.isJumpHost) {
            inflater.inflate(R.layout.dialog_edit_jump_host, null).apply {
                findViewById<TextView>(R.id.tvJumpHostInfo).text =
                    getString(R.string.jump_host_target, config.jumpHostServer?.host ?: "")
            }
        } else {
            inflater.inflate(R.layout.dialog_add_server, null)
        }
        currentDialogView = dialogView

        val containerView = if (config.isJumpHost) {
            dialogView.findViewById<ViewGroup>(R.id.jumpHostInfoContainer)
                .parent as ViewGroup
        } else {
            dialogView
        }

        val etHost = containerView.findViewById<EditText>(R.id.etHost)
        val etPort = containerView.findViewById<EditText>(R.id.etPort)
        val etUsername = containerView.findViewById<EditText>(R.id.etUsername)
        val etPassword = containerView.findViewById<EditText>(R.id.etPassword)
        val btnSelectKey = containerView.findViewById<Button>(R.id.btnSelectKey)
        val tvSelectedKey = containerView.findViewById<TextView>(R.id.tvSelectedKey)

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
            showKeySelectionDialog(selectedKeyUri) { newUri ->
                selectedKeyUri = newUri  // これは既に存在
            }
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
                    val updatedConfig = ServerConfig(
                        host = host,
                        port = port,
                        username = username,
                        privateKeyUri = selectedKeyUri,  // selectedKeyUriを使用
                        password = password,
                        jumpHostServer = config.jumpHostServer,
                        isJumpHost = config.isJumpHost
                    )

                    serverConfigs[index] = updatedConfig
                    serverConfigManager.updateServerConfig(index, updatedConfig)
                    updateServerSpinner()
                    spinnerServers.setSelection(index)
                    Toast.makeText(this, getString(R.string.update_server), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.require_host_user), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            showKeySelectionDialog(selectedKeyUri) { newUri ->
                selectedKeyUri = newUri
            }
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
                val serverToRemove = serverConfigs[which]

                val dependentServers = serverConfigs.filter {
                    it.jumpHostServer?.host == serverToRemove.host &&
                            it.jumpHostServer?.port == serverToRemove.port &&
                            it.jumpHostServer?.username == serverToRemove.username
                }

                if (dependentServers.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.warning))
                        .setMessage(getString(R.string.jump_host_removal_warning))
                        .setPositiveButton(getString(R.string.remove_all)) { _, _ ->

                            dependentServers.forEach { dependent ->
                                serverConfigs.remove(dependent)
                                serverConfigManager.removeServerConfig(dependent)
                            }
                            serverConfigs.remove(serverToRemove)
                            serverConfigManager.removeServerConfig(serverToRemove)
                            updateServerSpinner()
                            Toast.makeText(this, getString(R.string.servers_removed), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                } else {
                    serverConfigs.remove(serverToRemove)
                    serverConfigManager.removeServerConfig(serverToRemove)
                    updateServerSpinner()
                    Toast.makeText(this, getString(R.string.server_removed), Toast.LENGTH_SHORT).show()
                }
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

                currentSession = if (config.jumpHostServer != null) {
                    connectViaJumpHost(jsch, config)
                } else {
                    jsch.getSession(config.username, config.host, config.port).apply {
                        setConfig("StrictHostKeyChecking", "no")
                        config.password?.let { setPassword(it) }
                        connect(30000)
                    }
                }

                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    btnConnect.text = getString(R.string.disconnect)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.connection_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                startMonitoring()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    btnConnect.text = getString(R.string.connect)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.connection_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun connectViaJumpHost(jsch: JSch, config: ServerConfig): Session {
        try {
            val jump = config.jumpHostServer!!
            jumpSession = jsch.getSession(
                jump.username,
                jump.host,
                jump.port
            ).apply {
                setConfig("StrictHostKeyChecking", "no")
                jump.privateKeyUri?.let { uri ->
                    val keyBytes = readKeyFile(uri)
                    jsch.addIdentity("jump_key", keyBytes, null, null)
                }
                jump.password?.let { setPassword(it) }
                connect(30000)
            }

            val assignedPort = jumpSession?.setPortForwardingL(
                0,
                config.host,
                config.port
            ) ?: throw IllegalStateException("Port forwarding failed")

            return jsch.getSession(
                config.username,
                "127.0.0.1",
                assignedPort
            ).apply {
                setConfig("StrictHostKeyChecking", "no")
                config.privateKeyUri?.let { uri ->
                    val keyBytes = readKeyFile(uri)
                    jsch.addIdentity("target_key", keyBytes, null, null)
                }
                config.password?.let { setPassword(it) }
                connect(30000)
            }
        } catch (e: Exception) {
            jumpSession?.disconnect()
            throw e
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
                        try {
                            session.port.let { port ->
                                jumpSession?.delPortForwardingL(port)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        session.disconnect()
                    }
                }

                jumpSession?.let { session ->
                    if (session.isConnected) {
                        session.disconnect()
                    }
                }

                currentSession = null
                jumpSession = null

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
        showGraphs()
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
            R.id.nav_add_jump_host -> showJumpHostSelection()
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
                        showKeySelectionDialog(selectedKeyUri) { newUri ->
                            selectedKeyUri = newUri
                        }
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
    private fun showJumpHostSelection() {
        if (serverConfigs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_servers), Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = serverConfigs.map { "${it.host} (${it.username})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_jump_host))
            .setItems(serverNames) { _, which ->
                val selectedServer = serverConfigs[which]
                showAddServerWithJumpHost(selectedServer)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAddServerWithJumpHost(jumpHost: ServerConfig) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        currentDialogView = dialogView

        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKey)
        val tvSelectedKey = dialogView.findViewById<TextView>(R.id.tvSelectedKey)

        etPort.setText("22")
        selectedKeyUri = null
        tvSelectedKey.text = getString(R.string.not_select_file)

        btnSelectKey.setOnClickListener {
            showKeySelectionDialog(selectedKeyUri) { newUri ->
                selectedKeyUri = newUri
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.add_server)} (via ${jumpHost.host})")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 22
                val username = etUsername.text.toString()
                val password = etPassword.text.toString().takeIf { it.isNotEmpty() }

                if (host.isNotEmpty() && username.isNotEmpty()) {
                    val config = ServerConfig(
                        host = host,
                        port = port,
                        username = username,
                        privateKeyUri = selectedKeyUri,
                        password = password,
                        jumpHostServer = jumpHost,
                        isJumpHost = true
                    )

                    val index = serverConfigs.indexOf(jumpHost)
                    if (index >= 0) {
                        serverConfigs.add(index + 1, config)
                    } else {
                        serverConfigs.add(config)
                    }

                    serverConfigManager.saveServerConfig(config)
                    updateServerSpinner()
                    Toast.makeText(this, getString(R.string.add_server), Toast.LENGTH_SHORT).show()
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

    private fun showKeySelectionDialog(privateKeyUri: Uri?, onKeySelected: (Uri?) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_key))
            .setItems(arrayOf(
                getString(R.string.select_new_key),
                getString(R.string.remove_current_key),
                getString(R.string.cancel)
            )) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        startActivityForResult(intent, REQUEST_CODE_OPEN_FILE)
                    }
                    1 -> {
                        privateKeyUri?.let { uri ->
                            try {
                                contentResolver.releasePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: SecurityException) {
                                Log.e("KeySelection", "Failed to release permission for URI: $uri", e)
                            }
                        }
                        selectedKeyUri = null
                        onKeySelected(null)
                        currentDialogView?.findViewById<TextView>(R.id.tvSelectedKey)?.text =
                            getString(R.string.not_select_file)
                    }
                    2 -> {
                    }
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSSH()
        currentDialogView = null
    }

    private fun setupButtons() {
        btnConnect = findViewById(R.id.btnConnect)

        findViewById<Button>(R.id.btnTerminal).setOnClickListener {
            val isInTerminalMode = terminalContainer.visibility == View.VISIBLE
            if (!isInTerminalMode) {
                showTerminal()
                btnConnect.text = getString(R.string.graph)
            } else {
                showGraphs()
                updateConnectButtonText()
            }
        }

        btnConnect.setOnClickListener {
            if (terminalContainer.visibility == View.VISIBLE) {
                showGraphs()
                updateConnectButtonText()
            } else {
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
        }
    }

    private fun updateConnectButtonText() {
        btnConnect.text = if (currentSession != null) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.connect)
        }
    }

}