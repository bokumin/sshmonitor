package net.bokumin45.sshmonitor

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class GraphSetting(
    val name: String,
    var isVisible: Boolean = false,
    var order: Int = -1
)

class DialogGraphSettings(
    context: Context,
    private val currentSettings: List<GraphSetting>,
    private val onSettingsChanged: (List<GraphSetting>) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_graph_settings)

        val recyclerView = findViewById<RecyclerView>(R.id.rvGraphSettings)
        val adapter = GraphSettingsAdapter(currentSettings.toMutableList()) {
            onSettingsChanged(it)
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
}

class GraphSettingsAdapter(
    private val settings: MutableList<GraphSetting>,
    private val onSettingsChanged: (List<GraphSetting>) -> Unit
) : RecyclerView.Adapter<GraphSettingsAdapter.ViewHolder>() {

    private var currentOrder = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.cbGraphVisible)
        val orderText: TextView = view.findViewById(R.id.etOrder)
    }

    init {
        settings.filter { it.isVisible }.sortedBy { it.order }.forEachIndexed { index, setting ->
            setting.order = index
        }
        currentOrder = settings.filter { it.isVisible }.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_graph_setting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val setting = settings[position]
        holder.checkbox.text = setting.name
        holder.checkbox.isChecked = setting.isVisible

        if (setting.isVisible && setting.order >= 0) {
            val displayOrder = getDisplayOrderForPosition(position)
            holder.orderText.text = displayOrder.toString()
            holder.orderText.visibility = View.VISIBLE
        } else {
            holder.orderText.text = ""
            holder.orderText.visibility = View.INVISIBLE
        }

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                setting.order = currentOrder++
            } else {
                setting.order = -1
                reorderItems()
            }
            setting.isVisible = isChecked
            updateSettings()
            notifyDataSetChanged()
        }
    }

    private fun getDisplayOrderForPosition(position: Int): Int {
        val currentSetting = settings[position]
        return settings.count { it.isVisible && it.order < currentSetting.order } + 1
    }

    override fun getItemCount() = settings.size

    fun moveItem(from: Int, to: Int) {
        val item = settings.removeAt(from)
        settings.add(to, item)
        notifyItemMoved(from, to)
        updateOrders()
    }

    private fun reorderItems() {
        // チェックされているアイテムの順序を振り直す
        settings.filter { it.isVisible }
            .sortedBy { it.order }
            .forEachIndexed { index, setting ->
                setting.order = index
            }
        currentOrder = settings.count { it.isVisible }
    }

    private fun updateOrders() {
        val visibleSettings = settings.filter { it.isVisible }.sortedBy { it.order }
        visibleSettings.forEachIndexed { index, setting ->
            setting.order = index
        }
        currentOrder = visibleSettings.size
        updateSettings()
    }

    private fun updateSettings() {
        val sortedSettings = settings.sortedWith(compareBy(
            { !it.isVisible },
            { if (it.isVisible) it.order else Int.MAX_VALUE }
        ))
        onSettingsChanged(sortedSettings)
    }
}