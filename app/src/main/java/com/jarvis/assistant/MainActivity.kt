package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val cyan = 0xFF35E0FF.toInt()

        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(cyan); textSize = 12f; setPadding(0, 40, 0, 8)
        }
        fun field(v: String, hint: String) = EditText(this).apply {
            setText(v); this.hint = hint; setTextColor(0xFFBFF6FF.toInt())
            setHintTextColor(0xFF0A6F86.toInt()); inputType = InputType.TYPE_CLASS_TEXT
        }

        val model = field(prefs.getString("model", "llama3.2:3b") ?: "llama3.2:3b", "Ollama model")
        status = TextView(this).apply { setTextColor(cyan); textSize = 14f; setPadding(0, 40, 0, 0) }

        val start = Button(this).apply { text = "START JARVIS" }
        val stop = Button(this).apply { text = "STOP" }

        start.setOnClickListener {
            prefs.edit().putString("model", model.text.toString().trim()).apply()
            startAll()
        }
        stop.setOnClickListener {
            stopService(Intent(this, JarvisService::class.java))
            status.text = "Jarvis stopped."
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF02080F.toInt())
            setPadding(48, 120, 48, 48)
            addView(TextView(context).apply { text = "J.A.R.V.I.S."; setTextColor(cyan); textSize = 28f })
            addView(label("OLLAMA MODEL (must be running in Termux)")); addView(model)
            addView(start); addView(stop); addView(status)
        }
        setContentView(root)
    }

    private fun startAll() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) launch() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launch()
        else status.text = "Microphone permission is required."
    }

    private fun launch() {
        startForegroundService(Intent(this, JarvisService::class.java))
        status.text = "Jarvis is starting. First run downloads a ~40 MB voice model, then say “Jarvis”, pause, and ask."
    }
}
