package com.example.necremote

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val functions = listOf(
        "Encendido", "Modo", "Silencio", "Menú",
        "Volumen +", "Volumen -", "Siguiente", "Anterior",
        "Play/Pausa", "Arriba", "Abajo", "OK"
    )

    private var ir: ConsumerIrManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var command = 0

    private lateinit var addrInput: EditText
    private lateinit var info: TextView
    private lateinit var spinner: Spinner
    private lateinit var savedBox: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var fastScan: CheckBox

    private val prefs by lazy { getSharedPreferences("codigos", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        buildUi()
        if (ir?.hasIrEmitter() != true) {
            info.text = "Este teléfono no tiene emisor IR"
        } else {
            refreshInfo()
        }
        refreshSaved()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    // ---------- NEC ----------

    private fun necPattern(addr: Int, cmd: Int): IntArray {
        val data = (addr and 0xFF) or
                ((addr.inv() and 0xFF) shl 8) or
                ((cmd and 0xFF) shl 16) or
                ((cmd.inv() and 0xFF) shl 24)
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (i in 0 until 32) {
            p.add(560)
            p.add(if (((data shr i) and 1) == 1) 1690 else 560)
        }
        p.add(560)
        return p.toIntArray()
    }

    private fun send(addr: Int, cmd: Int) {
        val emitter = ir ?: return
        if (!emitter.hasIrEmitter()) return
        emitter.transmit(38000, necPattern(addr, cmd))
    }

    private fun address(): Int =
        addrInput.text.toString().trim().ifEmpty { "0" }.toIntOrNull(16)?.coerceIn(0, 255) ?: 0

    // ---------- Escaneo ----------

    private fun scanStep() {
        if (!scanning) return
        send(address(), command)
        refreshInfo()
        handler.postDelayed({
            if (scanning) {
                command = (command + 1) and 0xFF
                scanStep()
            }
        }, if (fastScan.isChecked) 500L else 1200L)
    }

    private fun startScan() {
        scanning = true
        scanBtn.text = "Detener"
        scanStep()
    }

    private fun stopScan() {
        scanning = false
        handler.removeCallbacksAndMessages(null)
        if (::scanBtn.isInitialized) scanBtn.text = "Auto"
    }

    private fun refreshInfo() {
        info.text = "Dirección: %02X   Comando: %02X (%d)".format(address(), command, command)
    }

    // ---------- Guardado ----------

    private fun saveCurrent() {
        stopScan()
        val name = spinner.selectedItem as String
        prefs.edit().putString(name, "${address()}:$command").apply()
        Toast.makeText(this, "$name = %02X/%02X".format(address(), command), Toast.LENGTH_SHORT).show()
        refreshSaved()
    }

    private fun refreshSaved() {
        savedBox.removeAllViews()
        for (name in functions) {
            val value = prefs.getString(name, null)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val b = Button(this).apply {
                text = if (value == null) "$name (sin asignar)" else {
                    val (a, c) = value.split(":").map { it.toInt() }
                    "$name  [%02X/%02X]".format(a, c)
                }
                isEnabled = value != null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val (a, c) = value!!.split(":").map { it.toInt() }
                    send(a, c)
                }
            }
            val clear = Button(this).apply {
                text = "X"
                isEnabled = value != null
                setOnClickListener {
                    prefs.edit().remove(name).apply()
                    refreshSaved()
                }
            }
            row.addView(b)
            row.addView(clear)
            savedBox.addView(row)
        }
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Buscador de códigos NEC"
            textSize = 20f
        })

        val addrRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        addrRow.addView(TextView(this).apply { text = "Dirección (hex): " })
        addrInput = EditText(this).apply {
            setText("00")
            minEms = 3
            setSelectAllOnFocus(true)
        }
        addrRow.addView(addrInput)
        root.addView(addrRow)

        info = TextView(this).apply { textSize = 18f; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(info)

        val navRow = LinearLayout(this)
        fun navButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            navRow.addView(this)
        }
        navButton("◀") {
            stopScan(); command = (command - 1) and 0xFF; refreshInfo()
        }
        navButton("Enviar") {
            stopScan(); send(address(), command); refreshInfo()
        }
        navButton("▶") {
            stopScan(); command = (command + 1) and 0xFF; send(address(), command); refreshInfo()
        }
        scanBtn = navButton("Auto") {
            if (scanning) stopScan() else startScan()
        }
        root.addView(navRow)

        fastScan = CheckBox(this).apply { text = "Auto rápido (0.5 s)" }
        root.addView(fastScan)

        root.addView(TextView(this).apply {
            text = "Cuando el equipo reaccione, pulsa Detener, elige la función y guarda:"
            setPadding(0, dp(12), 0, dp(4))
        })

        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, functions)
        }
        root.addView(spinner)

        root.addView(Button(this).apply {
            text = "Guardar como principal"
            setOnClickListener { saveCurrent() }
        })

        root.addView(TextView(this).apply {
            text = "Botones guardados"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        })
        savedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedBox)

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
