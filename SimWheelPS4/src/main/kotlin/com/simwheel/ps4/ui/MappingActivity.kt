package com.simwheel.ps4.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.view.KeyEvent
import com.simwheel.ps4.R
import com.simwheel.ps4.model.ButtonMapping
import com.simwheel.ps4.model.DefaultMappings
import com.simwheel.ps4.model.Settings as AppSettings

class MappingActivity : Activity() {

    private lateinit var settings: AppSettings
    private var currentMappings: MutableList<ButtonMapping> = mutableListOf()
    private var profileNames: MutableList<String> = mutableListOf()
    private lateinit var adapter: MappingAdapter
    private lateinit var spinnerProfile: Spinner
    private var waitingForKey = false
    private var editingIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapping)

        settings = AppSettings(this)

        spinnerProfile = findViewById(R.id.spinner_profile)
        val listView: ListView = findViewById(R.id.lv_mappings)

        profileNames = settings.getProfileNames().toMutableList()
        if (profileNames.isEmpty()) profileNames.add("Default")
        val profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileNames)
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProfile.adapter = profileAdapter

        val activeIdx = profileNames.indexOf(settings.getActiveProfile())
        if (activeIdx >= 0) spinnerProfile.setSelection(activeIdx)

        loadProfile(settings.getActiveProfile())

        adapter = MappingAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            showRemapDialog(position)
        }

        spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val name = profileNames[pos]
                settings.setActiveProfile(name)
                loadProfile(name)
                adapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        findViewById<Button>(R.id.btn_save_profile).setOnClickListener {
            val name = profileNames[spinnerProfile.selectedItemPosition]
            settings.saveMappingProfile(name, currentMappings)
            Toast.makeText(this, "Profile \"$name\" saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_new_profile).setOnClickListener {
            showNewProfileDialog()
        }

        findViewById<Button>(R.id.btn_reset_mappings).setOnClickListener {
            currentMappings = DefaultMappings.ALL.toMutableList()
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btn_back_mapping).setOnClickListener { finish() }
    }

    private fun loadProfile(name: String) {
        currentMappings = (settings.loadMappingProfile(name) ?: DefaultMappings.ALL).toMutableList()
    }

    private fun showRemapDialog(index: Int) {
        val mapping = currentMappings[index]
        val pcCodes = DefaultMappings.PC_CODE_NAMES.keys.sorted()
        val pcNames = pcCodes.map { DefaultMappings.pcCodeName(it) }

        val currentIdx = pcCodes.indexOf(mapping.pcCode).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Remap: ${mapping.ps4Name}")
            .setItems(pcNames.toTypedArray()) { _, which ->
                val newCode = pcCodes[which]
                currentMappings[index] = mapping.copy(
                    pcCode = newCode,
                    pcName = DefaultMappings.pcCodeName(newCode)
                )
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewProfileDialog() {
        val input = EditText(this)
        input.hint = "Profile name"
        AlertDialog.Builder(this)
            .setTitle("New Profile")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !profileNames.contains(name)) {
                    profileNames.add(name)
                    settings.saveMappingProfile(name, currentMappings)
                    (spinnerProfile.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                    spinnerProfile.setSelection(profileNames.indexOf(name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class MappingAdapter : BaseAdapter() {
        override fun getCount() = currentMappings.size
        override fun getItem(pos: Int) = currentMappings[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MappingActivity)
                .inflate(R.layout.item_mapping, parent, false)
            val m = currentMappings[pos]
            view.findViewById<TextView>(R.id.tv_ps4_button).text = m.ps4Name
            view.findViewById<TextView>(R.id.tv_pc_action).text  = m.pcName
            view.findViewById<TextView>(R.id.tv_code).text       = "[${m.pcCode}]"
            return view
        }
    }
}
