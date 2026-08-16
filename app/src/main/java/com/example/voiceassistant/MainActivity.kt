package com.example.voiceassistant

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ============================================================
    // AC UI
    // ============================================================

    private lateinit var acTemperature: TextView
    private lateinit var acRoomTemperature: TextView
    private lateinit var acPowerStatus: TextView
    private lateinit var acConnectionText: TextView
    private lateinit var acDeviceText: TextView
    private lateinit var acModeGlyph: TextView
    private lateinit var acFanGlyph: TextView
    private lateinit var acPowerToggle: androidx.appcompat.widget.SwitchCompat

    private lateinit var dialTemperature: com.example.voiceassistant.widget.RotaryDialView
    private lateinit var dialFanSpeed: com.example.voiceassistant.widget.RotaryDialView

    private lateinit var featurePowerful: LinearLayout
    private lateinit var featureEco: LinearLayout
    private lateinit var featureClean: LinearLayout
    private lateinit var featureDisplay: LinearLayout
    private lateinit var featureSwingV: LinearLayout
    private lateinit var featureSwingH: LinearLayout
    private lateinit var featureConverti8: LinearLayout
    private lateinit var converti8StateText: TextView

    private val AC_MODES = listOf("cool", "dry", "fan", "auto")
    private val AC_MODE_GLYPHS = mapOf(
        "cool" to "❄",
        "dry" to "☂",
        "fan" to "≋",
        "auto" to "⟳"
    )
    private val FAN_STEPS = listOf("Low", "Medium", "High", "Auto")

    private var currentAcTemperature = 24
    private var currentAcMode: String? = null
    private var currentFanSpeed: String? = null
    private var swingOn = false
    private var swingHOn = false
    private var acDisplayOn = true
    private var convertiOn = false
    private var currentAcPreset: String? = null

    /** Guards against acPowerToggle.setOnCheckedChangeListener firing while
     * we're just reflecting a status refresh, not a user tap. */
    private var updatingPowerToggleProgrammatically = false

    // ============================================================
    // VOICE UI
    // ============================================================

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var micRing: View

    private var pulseAnimator: AnimatorSet? = null

    // ============================================================
    // HOME TABS
    // ============================================================

    private lateinit var acPanel: View
    private lateinit var tvPanel: View
    private lateinit var idleCard: View
    private lateinit var tabAc: Button
    private lateinit var tabTv: Button

    private var lastTabAcTapTime = 0L
    private var lastTabTvTapTime = 0L
    private val DOUBLE_TAP_WINDOW_MS = 300L

    // ============================================================
    // PERMISSIONS
    // ============================================================

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CAMERA
    )

    private val PERMISSION_REQUEST_CODE = 101

    private var flashlightOn = false

    // ============================================================
    // PHONE
    // ============================================================

    private val DEFAULT_COUNTRY_CODE = "+91"

    // ============================================================
    // ON CREATE
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // --------------------------------------------------------
        // Find views
        // --------------------------------------------------------

        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        micButton = findViewById(R.id.micButton)
        micRing = findViewById(R.id.micRing)

        acTemperature = findViewById(R.id.acTemperature)
        acRoomTemperature = findViewById(R.id.acRoomTemperature)
        acPowerStatus = findViewById(R.id.acPowerStatus)
        acConnectionText = findViewById(R.id.acConnectionText)
        acDeviceText = findViewById(R.id.acDeviceText)
        acModeGlyph = findViewById(R.id.acModeGlyph)
        acFanGlyph = findViewById(R.id.acFanGlyph)
        acPowerToggle = findViewById(R.id.acPowerToggle)

        dialTemperature = findViewById(R.id.dialTemperature)
        dialFanSpeed = findViewById(R.id.dialFanSpeed)

        featurePowerful = findViewById(R.id.featurePowerful)
        featureEco = findViewById(R.id.featureEco)
        featureClean = findViewById(R.id.featureClean)
        featureDisplay = findViewById(R.id.featureDisplay)
        featureSwingV = findViewById(R.id.featureSwingV)
        featureSwingH = findViewById(R.id.featureSwingH)
        featureConverti8 = findViewById(R.id.featureConverti8)
        converti8StateText = findViewById(R.id.converti8StateText)

        // --------------------------------------------------------
        // Setup
        // --------------------------------------------------------

        setupHomeTabs()
        setupAcControls()
        setupTvControls()

        tts = TextToSpeech(this, this)

        setupSpeechRecognizer()
        requestPermissionsIfNeeded()

        // --------------------------------------------------------
        // TV bridge
        // --------------------------------------------------------

        TvBridge.init(this)

        // --------------------------------------------------------
        // Microphone
        // --------------------------------------------------------

        micButton.setOnClickListener {
            startListening()
        }

        micButton.setOnLongClickListener {
            showTvPairingDialog()
            true
        }

        // --------------------------------------------------------
        // AC initial status
        // --------------------------------------------------------

        loadInitialAcStatus()
    }

    // ============================================================
    // HOME AC / TV TABS
    // ============================================================

    private fun setupHomeTabs() {

        tabAc = findViewById(R.id.tabAc)
        tabTv = findViewById(R.id.tabTv)

        acPanel = findViewById(R.id.acPanel)
        tvPanel = findViewById(R.id.tvPanel)
        idleCard = findViewById(R.id.idleCard)

        fun showAc() {

            idleCard.visibility = View.GONE
            acPanel.visibility = View.VISIBLE
            tvPanel.visibility = View.GONE

            tabAc.setTextColor(Color.WHITE)
            tabTv.setTextColor(
                Color.rgb(143, 163, 184)
            )

            tabAc.setBackgroundResource(
                R.drawable.bg_tab_active
            )

            tabTv.setBackgroundResource(
                R.drawable.bg_tab_inactive
            )
        }

        fun showTv() {

            idleCard.visibility = View.GONE
            acPanel.visibility = View.GONE
            tvPanel.visibility = View.VISIBLE

            tabTv.setTextColor(Color.WHITE)
            tabAc.setTextColor(
                Color.rgb(143, 163, 184)
            )

            tabTv.setBackgroundResource(
                R.drawable.bg_tab_active
            )

            tabAc.setBackgroundResource(
                R.drawable.bg_tab_inactive
            )
        }

        fun showIdle() {

            idleCard.visibility = View.VISIBLE
            acPanel.visibility = View.GONE
            tvPanel.visibility = View.GONE

            tabAc.setTextColor(Color.rgb(143, 163, 184))
            tabTv.setTextColor(Color.rgb(143, 163, 184))

            tabAc.setBackgroundResource(
                R.drawable.bg_tab_inactive
            )

            tabTv.setBackgroundResource(
                R.drawable.bg_tab_inactive
            )
        }

        // Double-tapping a tab (whether it's already open or not) sends you
        // back to the starting homepage. A single tap just opens that panel.
        tabAc.setOnClickListener {

            val now = System.currentTimeMillis()

            if (acPanel.visibility == View.VISIBLE &&
                now - lastTabAcTapTime < DOUBLE_TAP_WINDOW_MS
            ) {
                showIdle()
                lastTabAcTapTime = 0L
            } else {
                showAc()
                lastTabAcTapTime = now
            }
        }

        tabTv.setOnClickListener {

            val now = System.currentTimeMillis()

            if (tvPanel.visibility == View.VISIBLE &&
                now - lastTabTvTapTime < DOUBLE_TAP_WINDOW_MS
            ) {
                showIdle()
                lastTabTvTapTime = 0L
            } else {
                showTv()
                lastTabTvTapTime = now
            }
        }

        // IMPORTANT: no device panel is selected when the app starts.
        // The user must explicitly tap AC or TV.
        showIdle()
    }

    // ============================================================
    // AC CONTROLS
    // ============================================================

    private fun setupAcControls() {

        findViewById<Button>(R.id.btnAcOn)
            .setOnClickListener {
                controlAcOn()
            }

        findViewById<Button>(R.id.btnAcOff)
            .setOnClickListener {
                controlAcOff()
            }

        findViewById<Button>(R.id.btnRefreshAc)
            .setOnClickListener {
                refreshAcStatus()
            }

        // Compact power switch beside the AC illustration — mirrors
        // btnAcOn / btnAcOff so either control works.
        acPowerToggle.setOnCheckedChangeListener { _, isChecked ->

            if (updatingPowerToggleProgrammatically) return@setOnCheckedChangeListener

            if (isChecked) controlAcOn() else controlAcOff()
        }

        setupAcDials()
        setupAcFeatureGrid()
    }

    // ============================================================
    // AC ROTARY DIALS (temperature + mode / fan speed)
    // ============================================================

    private fun setupAcDials() {

        dialTemperature.minValue = 16f
        dialTemperature.maxValue = 30f
        dialTemperature.value = currentAcTemperature.toFloat()

        dialTemperature.onValueChanged = { newValue, final ->

            val rounded = Math.round(newValue)

            acTemperature.text = "$rounded°"

            if (final) {
                currentAcTemperature = rounded
                setAcTemperature(rounded)
            }
        }

        // Tapping the centre of the temperature dial cycles the HVAC mode,
        // same idea as tapping the mode icon in the MirAIe app.
        dialTemperature.onCenterTap = {

            val current = currentAcMode ?: "cool"
            val nextIndex = (AC_MODES.indexOf(current) + 1) % AC_MODES.size
            val next = AC_MODES[nextIndex]

            highlightAcMode(next)
            setAcMode(next)
        }

        dialFanSpeed.steps = FAN_STEPS
        dialFanSpeed.value = 1f // Medium by default

        dialFanSpeed.onValueChanged = { newValue, final ->

            val index = Math.round(newValue).coerceIn(0, FAN_STEPS.size - 1)
            dialFanSpeed.centerLabel = FAN_STEPS[index]
            dialFanSpeed.invalidate()

            if (final) {
                val speed = FAN_STEPS[index].lowercase()
                highlightFanSpeed(speed)
                setAcFanSpeed(speed)
            }
        }

        // Tapping the centre of the fan dial also cycles the speed —
        // handy for a quick change without dragging.
        dialFanSpeed.onCenterTap = {

            val index = (Math.round(dialFanSpeed.value) + 1) % FAN_STEPS.size
            dialFanSpeed.value = index.toFloat()
            val speed = FAN_STEPS[index].lowercase()
            highlightFanSpeed(speed)
            setAcFanSpeed(speed)
        }

        highlightAcMode("cool")
        highlightFanSpeed("medium")
    }

    // ============================================================
    // AC FEATURE GRID (Powerful / Eco / Clean / Display / Swing / Converti8)
    // ============================================================

    private fun setupAcFeatureGrid() {

        featurePowerful.setOnClickListener {
            val turningOn = currentAcPreset != "boost"
            highlightPreset(if (turningOn) "boost" else "none")
            setAcPreset(if (turningOn) "boost" else "none")
        }

        featureEco.setOnClickListener {
            val turningOn = currentAcPreset != "eco"
            highlightPreset(if (turningOn) "eco" else "none")
            setAcPreset(if (turningOn) "eco" else "none")
        }

        featureClean.setOnClickListener {
            val turningOn = currentAcPreset != "clean"
            highlightPreset(if (turningOn) "clean" else "none")
            setAcPreset(if (turningOn) "clean" else "none")
        }

        featureDisplay.setOnClickListener {
            val turningOn = !acDisplayOn
            highlightDisplay(turningOn)
            setAcDisplay(if (turningOn) "on" else "off")
        }

        // Toggles continuous vertical swing on/off, exactly like the
        // physical remote's swing button.
        featureSwingV.setOnClickListener {
            val turningOn = !swingOn
            highlightSwing(turningOn)
            setAcSwingMode(if (turningOn) "auto" else "three")
        }

        featureSwingH.setOnClickListener {
            val turningOn = !swingHOn
            highlightSwingH(turningOn)
            setAcSwingHorizontal(if (turningOn) "auto" else "three")
        }

        featureConverti8.setOnClickListener {
            val turningOn = !convertiOn
            highlightConverti(turningOn)
            setAcConverti(if (turningOn) "on" else "off")
        }

        // AC Display defaults to on, matching the reference app.
        highlightDisplay(true)
    }

    // ============================================================
    // AC MODE / FAN / SWING / PRESET HIGHLIGHTING
    // ============================================================

    private fun highlightAcMode(mode: String) {

        currentAcMode = mode

        dialTemperature.centerIcon = AC_MODE_GLYPHS[mode] ?: "❄"
        dialTemperature.centerLabel = mode.replaceFirstChar { it.uppercase() }
        dialTemperature.invalidate()

        acModeGlyph.text = AC_MODE_GLYPHS[mode] ?: "❄"
    }

    private fun highlightFanSpeed(speed: String) {

        currentFanSpeed = speed

        val index = FAN_STEPS.indexOfFirst {
            it.equals(speed, ignoreCase = true)
        }.coerceAtLeast(0)

        dialFanSpeed.value = index.toFloat()
        dialFanSpeed.centerLabel = FAN_STEPS[index]
        dialFanSpeed.centerIcon = "≋"
        dialFanSpeed.invalidate()

        acFanGlyph.text = FAN_STEPS[index]
    }

    private fun highlightSwing(on: Boolean) {

        swingOn = on

        featureSwingV.setBackgroundResource(
            if (on) R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )
    }

    private fun highlightSwingH(on: Boolean) {

        swingHOn = on

        featureSwingH.setBackgroundResource(
            if (on) R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )
    }

    private fun highlightDisplay(on: Boolean) {

        acDisplayOn = on

        featureDisplay.setBackgroundResource(
            if (on) R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )
    }

    private fun highlightConverti(on: Boolean) {

        convertiOn = on

        featureConverti8.setBackgroundResource(
            if (on) R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )

        converti8StateText.text = if (on) "ON" else "OFF"
    }

    private fun highlightPreset(mode: String) {

        currentAcPreset = if (mode == "none") null else mode

        featurePowerful.setBackgroundResource(
            if (mode == "boost") R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )

        featureEco.setBackgroundResource(
            if (mode == "eco") R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )

        featureClean.setBackgroundResource(
            if (mode == "clean") R.drawable.bg_ac_button_active else R.drawable.bg_ac_button
        )
    }

    /** Reflects the AC's actual power state onto the toggle switch without
     * re-triggering another network call via its listener. */
    private fun setPowerToggleState(on: Boolean) {

        updatingPowerToggleProgrammatically = true
        acPowerToggle.isChecked = on
        updatingPowerToggleProgrammatically = false
    }

    // ============================================================
    // AC ON
    // ============================================================

    private fun controlAcOn() {

        lifecycleScope.launch {

            val result = AcApiClient.turnOn()

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "TURN ON SUCCESS: $response"
                )

                runOnUiThread {

                    acPowerStatus.text = "ON"

                    acPowerStatus.setTextColor(
                        Color.rgb(
                            105,
                            240,
                            174
                        )
                    )

                    acConnectionText.text =
                        "● AC ONLINE"

                    setPowerToggleState(true)

                    speak("AC turned on")
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "TURN ON ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't turn on the AC"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC OFF
    // ============================================================

    private fun controlAcOff() {

        lifecycleScope.launch {

            val result = AcApiClient.turnOff()

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "TURN OFF SUCCESS: $response"
                )

                runOnUiThread {

                    acPowerStatus.text = "OFF"

                    acPowerStatus.setTextColor(
                        Color.rgb(
                            255,
                            123,
                            136
                        )
                    )

                    setPowerToggleState(false)

                    speak("AC turned off")
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "TURN OFF ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't turn off the AC"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC TEMPERATURE
    // ============================================================

    private fun setAcTemperature(
        temperature: Int
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setTemperature(
                    temperature
                )

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "TEMPERATURE SUCCESS: $response"
                )

                runOnUiThread {

                    currentAcTemperature = temperature

                    acTemperature.text =
                        "$temperature°"

                    dialTemperature.value = temperature.toFloat()

                    speak(
                        "Temperature set to $temperature degrees"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "TEMPERATURE ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the AC temperature"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC MODE
    // ============================================================

    private fun setAcMode(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setMode(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "MODE SUCCESS: $response"
                )

                runOnUiThread {

                    if (AC_MODES.contains(mode)) {
                        highlightAcMode(mode)
                    }

                    speak(
                        "AC set to $mode mode"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "MODE ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the AC mode"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC FAN SPEED
    // ============================================================

    private fun setAcFanSpeed(
        speed: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setFanSpeed(speed)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "FAN SPEED SUCCESS: $response"
                )

                runOnUiThread {

                    highlightFanSpeed(speed)

                    speak(
                        "Fan speed set to $speed"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "FAN SPEED ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the fan speed"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC SWING
    // ============================================================

    private fun setAcSwingMode(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setSwing(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "SWING SUCCESS: $response"
                )

                runOnUiThread {

                    speak(
                        if (mode == "auto") {
                            "Swing turned on"
                        } else {
                            "Swing turned off"
                        }
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "SWING ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the swing setting"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC HORIZONTAL SWING
    // ============================================================

    private fun setAcSwingHorizontal(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setSwingHorizontal(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "SWING H SUCCESS: $response"
                )

                runOnUiThread {

                    speak(
                        if (mode == "auto") {
                            "Horizontal swing turned on"
                        } else {
                            "Horizontal swing turned off"
                        }
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "SWING H ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the horizontal swing setting"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC PRESET (POWERFUL / ECO MODE / CLEAN)
    // ============================================================

    private fun setAcPreset(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setPreset(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "PRESET SUCCESS: $response"
                )

                runOnUiThread {

                    speak(
                        when (mode) {
                            "boost" -> "Powerful mode on"
                            "eco" -> "Eco mode on"
                            "clean" -> "Clean mode on"
                            else -> "Preset cleared"
                        }
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "PRESET ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the AC preset"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC DISPLAY
    // ============================================================

    private fun setAcDisplay(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setDisplay(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "DISPLAY SUCCESS: $response"
                )

                runOnUiThread {

                    speak(
                        if (mode == "on") "AC display turned on" else "AC display turned off"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "DISPLAY ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the AC display setting"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC CONVERTI 8
    // ============================================================

    private fun setAcConverti(
        mode: String
    ) {

        lifecycleScope.launch {

            val result =
                AcApiClient.setConverti(mode)

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "CONVERTI SUCCESS: $response"
                )

                runOnUiThread {

                    speak(
                        if (mode == "on") "Converti8 turned on" else "Converti8 turned off"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "CONVERTI ERROR",
                    error
                )

                runOnUiThread {

                    speak(
                        "I couldn't change the Converti8 setting"
                    )
                }
            }
        }
    }

    // ============================================================
    // AC STATUS PARSER
    // ============================================================

    private fun applyAcStatus(
        response: String
    ) {

        val temperature =
            Regex(
                "Temperature:\\s*([0-9]+(?:\\.[0-9]+)?)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        val roomTemperature =
            Regex(
                "Room temperature:\\s*([0-9]+(?:\\.[0-9]+)?)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        val powerMode =
            Regex(
                "Power mode:\\s*PowerMode\\.([A-Z_]+)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)

        val hvacMode =
            Regex(
                "Hvac mode:\\s*HVACMode\\.([A-Z_]+)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)

        val fanMode =
            Regex(
                "Fan mode:\\s*FanMode\\.([A-Z_]+)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)

        val vSwingMode =
            Regex(
                "Vertical swing mode:\\s*SwingMode\\.([A-Z_]+)"
            )
                .find(response)
                ?.groupValues
                ?.getOrNull(1)

        if (temperature != null) {

            currentAcTemperature =
                temperature.toInt()

            acTemperature.text =
                if (temperature % 1.0 == 0.0) {
                    "${temperature.toInt()}°"
                } else {
                    "${temperature}°"
                }
        }

        if (roomTemperature != null) {

            acRoomTemperature.text =
                if (roomTemperature % 1.0 == 0.0) {
                    "Room ${roomTemperature.toInt()}°"
                } else {
                    "Room ${roomTemperature}°"
                }
        }

        if (hvacMode != null) {
            highlightAcMode(hvacMode.lowercase())
        }

        if (fanMode != null) {
            highlightFanSpeed(fanMode.lowercase())
        }

        if (vSwingMode != null) {
            highlightSwing(vSwingMode == "AUTO")
        }

        when (powerMode) {

            "OFF" -> {

                acPowerStatus.text = "OFF"

                acPowerStatus.setTextColor(
                    Color.rgb(
                        255,
                        123,
                        136
                    )
                )

                setPowerToggleState(false)
            }

            "ON" -> {

                acPowerStatus.text = "ON"

                acPowerStatus.setTextColor(
                    Color.rgb(
                        105,
                        240,
                        174
                    )
                )

                setPowerToggleState(true)
            }
        }
    }

    // ============================================================
    // REFRESH AC
    // ============================================================

    private fun refreshAcStatus() {

        lifecycleScope.launch {

            val result =
                AcApiClient.getStatus()

            result.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "REFRESH STATUS: $response"
                )

                runOnUiThread {

                    applyAcStatus(response)

                    acConnectionText.text =
                        "● AC ONLINE"

                    acDeviceText.text =
                        "panasonic-ac"

                    speak(
                        "AC status refreshed"
                    )
                }
            }

            result.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "REFRESH ERROR",
                    error
                )

                runOnUiThread {

                    acConnectionText.text =
                        "● AC OFFLINE"

                    speak(
                        "AC is currently unavailable"
                    )
                }
            }
        }
    }

    // ============================================================
    // INITIAL AC STATUS
    // ============================================================

    private fun loadInitialAcStatus() {

        lifecycleScope.launch {

            // ----------------------------------------------------
            // Health
            // ----------------------------------------------------

            val healthResult =
                AcApiClient.health()

            healthResult.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "HEALTH SUCCESS: $response"
                )
            }

            healthResult.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "HEALTH ERROR",
                    error
                )
            }

            // ----------------------------------------------------
            // Status
            // ----------------------------------------------------

            val statusResult =
                AcApiClient.getStatus()

            statusResult.onSuccess { response ->

                android.util.Log.d(
                    "AC_API",
                    "STATUS SUCCESS: $response"
                )

                runOnUiThread {

                    applyAcStatus(response)

                    acConnectionText.text =
                        "● AC ONLINE"

                    acDeviceText.text =
                        "panasonic-ac"
                }
            }

            statusResult.onFailure { error ->

                android.util.Log.e(
                    "AC_API",
                    "STATUS ERROR",
                    error
                )

                runOnUiThread {

                    acConnectionText.text =
                        "● AC OFFLINE"
                }
            }
        }
    }

    // ============================================================
    // TV CONTROLS
    // ============================================================

    private fun setupTvControls() {

        findViewById<Button>(R.id.btnTvPair)
            .setOnClickListener {
                showTvPairingDialog()
            }

        findViewById<Button>(R.id.btnTvPower)
            .setOnClickListener {
                tvPower()
            }

        findViewById<Button>(R.id.btnTvVolDown)
            .setOnClickListener {
                tvVolume("down")
            }

        findViewById<Button>(R.id.btnTvMute)
            .setOnClickListener {
                tvVolume("mute")
            }

        findViewById<Button>(R.id.btnTvVolUp)
            .setOnClickListener {
                tvVolume("up")
            }

        findViewById<Button>(R.id.btnTvHome)
            .setOnClickListener {
                tvNavigation("home")
            }

        findViewById<Button>(R.id.btnTvBack)
            .setOnClickListener {
                tvNavigation("back")
            }

        findViewById<Button>(R.id.btnTvPlayPause)
            .setOnClickListener {
                tvNavigation("play")
            }

        findViewById<Button>(R.id.btnTvUp)
            .setOnClickListener {
                tvNavigation("up")
            }

        findViewById<Button>(R.id.btnTvDown)
            .setOnClickListener {
                tvNavigation("down")
            }

        findViewById<Button>(R.id.btnTvLeft)
            .setOnClickListener {
                tvNavigation("left")
            }

        findViewById<Button>(R.id.btnTvSelect)
            .setOnClickListener {
                tvNavigation("select")
            }

        findViewById<Button>(R.id.btnTvRight)
            .setOnClickListener {
                tvNavigation("right")
            }

        findViewById<Button>(R.id.btnTvNetflix)
            .setOnClickListener {
                openTvKnownApp("netflix")
            }

        findViewById<Button>(R.id.btnTvYouTube)
            .setOnClickListener {
                openTvKnownApp("youtube")
            }

        findViewById<Button>(R.id.btnTvPrime)
            .setOnClickListener {
                openTvKnownApp("prime")
            }

        findViewById<Button>(R.id.btnTvSpotify)
            .setOnClickListener {
                openTvKnownApp("spotify")
            }

        setupTvTouchpadSwipe()
    }

    // ============================================================
    // TV TOUCHPAD SWIPE-TO-NAVIGATE
    // ============================================================

    /** Minimum finger travel (px) before a drag counts as a swipe rather
     * than a stray tap. */
    private val tvSwipeDistanceThreshold = 60

    /** Minimum fling velocity (px/s) required to register a swipe. */
    private val tvSwipeVelocityThreshold = 80

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTvTouchpadSwipe() {

        val touchpad = findViewById<android.widget.FrameLayout>(
            R.id.tvTouchpad
        )

        val gestureDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {

                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {

                    if (e1 == null) return false

                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y

                    val isHorizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)

                    val distance = if (isHorizontal) dx else dy
                    val velocity = if (isHorizontal) velocityX else velocityY

                    if (kotlin.math.abs(distance) < tvSwipeDistanceThreshold ||
                        kotlin.math.abs(velocity) < tvSwipeVelocityThreshold
                    ) {
                        return false
                    }

                    val direction = when {
                        isHorizontal && dx > 0 -> "right"
                        isHorizontal && dx < 0 -> "left"
                        !isHorizontal && dy > 0 -> "down"
                        else -> "up"
                    }

                    tvNavigation(direction)
                    return true
                }

                override fun onSingleTapConfirmed(
                    e: android.view.MotionEvent
                ): Boolean {

                    // A plain tap on the open surface (not on one of the
                    // D-pad buttons, which handle their own clicks) acts
                    // as Select — matches a real remote trackpad's click.
                    tvNavigation("select")
                    return true
                }
            }
        )

        touchpad.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ============================================================
    // TV POWER
    // ============================================================

    private fun tvPower() {

        TvBridge.power { success, message ->

            runOnUiThread {

                if (success) {

                    speak(
                        "TV power command sent"
                    )

                } else {

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()

                    speak(message)
                }
            }
        }
    }

    // ============================================================
    // TV VOLUME
    // ============================================================

    private fun tvVolume(
        direction: String
    ) {

        TvBridge.volume(
            direction
        ) { success, message ->

            runOnUiThread {

                if (success) {

                    when (direction) {

                        "up" ->
                            speak("TV volume up")

                        "down" ->
                            speak("TV volume down")

                        else ->
                            speak("TV muted")
                    }

                } else {

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()

                    speak(message)
                }
            }
        }
    }

    // ============================================================
    // TV NAVIGATION
    // ============================================================

    private fun tvNavigation(
        action: String
    ) {

        TvBridge.nav(
            action
        ) { success, message ->

            runOnUiThread {

                if (!success) {

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // TV APPS
    // ============================================================

    private fun openTvKnownApp(
        searchName: String
    ) {

        val appName =
            TvBridge.KNOWN_APPS.keys.firstOrNull { key ->

                key.lowercase(
                    Locale.getDefault()
                ).contains(searchName)
            }

        val appId =
            appName?.let {
                TvBridge.KNOWN_APPS[it]
            }

        if (appId == null) {

            speak(
                "That TV app is not configured"
            )

            return
        }

        TvBridge.launchApp(
            appId
        ) { success, message ->

            runOnUiThread {

                if (success) {

                    speak(
                        "Opening $appName on the TV"
                    )

                } else {

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()

                    speak(message)
                }
            }
        }
    }

    // ============================================================
    // TV PAIRING
    // ============================================================

    private fun showTvPairingDialog() {

        val input =
            android.widget.EditText(this).apply {

                hint =
                    "TV's local IP, e.g. 192.168.1.10"

                setTextColor(Color.WHITE)

                setHintTextColor(
                    Color.GRAY
                )
            }

        android.app.AlertDialog.Builder(this)
            .setTitle("Pair with TV")
            .setView(input)
            .setPositiveButton(
                "Start pairing"
            ) { _, _ ->

                val host =
                    input.text
                        .toString()
                        .trim()

                if (host.isEmpty()) {
                    return@setPositiveButton
                }

                statusText.text =
                    "Connecting to the TV..."

                TvBridge.startPairing(
                    host,

                    onCodeShown = {

                        runOnUiThread {
                            showTvCodeDialog()
                        }
                    },

                    onError = { message ->

                        runOnUiThread {

                            statusText.text =
                                "Pairing failed"

                            Toast.makeText(
                                this,
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // ============================================================
    // TV CODE
    // ============================================================

    private fun showTvCodeDialog() {

        val input =
            android.widget.EditText(this).apply {

                hint =
                    "6-digit code shown on the TV"

                setTextColor(Color.WHITE)

                setHintTextColor(
                    Color.GRAY
                )
            }

        android.app.AlertDialog.Builder(this)
            .setTitle(
                "Enter the code on your TV screen"
            )
            .setView(input)
            .setPositiveButton(
                "Pair"
            ) { _, _ ->

                val code =
                    input.text
                        .toString()
                        .trim()

                TvBridge.submitPairingCode(
                    code
                ) { success, message ->

                    runOnUiThread {

                        statusText.text =
                            if (success) {
                                "TV paired"
                            } else {
                                "Pairing failed"
                            }

                        Toast.makeText(
                            this,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

    private fun requestPermissionsIfNeeded() {

        val missing =
            REQUIRED_PERMISSIONS.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    // ============================================================
    // TEXT TO SPEECH
    // ============================================================

    override fun onInit(
        status: Int
    ) {

        if (
            status ==
            TextToSpeech.SUCCESS
        ) {

            tts.language =
                Locale.US
        }
    }

    private fun speak(
        text: String
    ) {

        statusText.text = text

        if (::tts.isInitialized) {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "assistant"
            )
        }
    }

    // ============================================================
    // SPEECH RECOGNIZER
    // ============================================================

    private fun setupSpeechRecognizer() {

        speechRecognizer =
            SpeechRecognizer
                .createSpeechRecognizer(this)

        speechRecognizer
            .setRecognitionListener(

                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {

                        statusText.text =
                            "Listening..."

                        startPulseAnimation()
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) {}

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {}

                    override fun onEndOfSpeech() {

                        statusText.text =
                            "Processing..."

                        stopPulseAnimation()
                    }

                    override fun onError(
                        error: Int
                    ) {

                        statusText.text =
                            "Didn't catch that, tap and try again"

                        stopPulseAnimation()
                    }

                    override fun onResults(
                        results: Bundle?
                    ) {

                        stopPulseAnimation()

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val spokenText =
                            matches?.firstOrNull()
                                ?: return

                        heardText.text =
                            "You said: \"$spokenText\""

                        handleCommand(
                            spokenText.lowercase(
                                Locale.getDefault()
                            )
                        )
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )
    }

    // ============================================================
    // MIC ANIMATION
    // ============================================================

    private fun startPulseAnimation() {

        pulseAnimator?.cancel()

        val scaleX =
            ObjectAnimator.ofFloat(
                micRing,
                "scaleX",
                1f,
                1.25f
            ).apply {

                repeatMode =
                    ObjectAnimator.REVERSE

                repeatCount =
                    ObjectAnimator.INFINITE

                duration = 700
            }

        val scaleY =
            ObjectAnimator.ofFloat(
                micRing,
                "scaleY",
                1f,
                1.25f
            ).apply {

                repeatMode =
                    ObjectAnimator.REVERSE

                repeatCount =
                    ObjectAnimator.INFINITE

                duration = 700
            }

        val alpha =
            ObjectAnimator.ofFloat(
                micRing,
                "alpha",
                1f,
                0.3f
            ).apply {

                repeatMode =
                    ObjectAnimator.REVERSE

                repeatCount =
                    ObjectAnimator.INFINITE

                duration = 700
            }

        pulseAnimator =
            AnimatorSet().apply {

                playTogether(
                    scaleX,
                    scaleY,
                    alpha
                )

                interpolator =
                    LinearInterpolator()

                start()
            }
    }

    private fun stopPulseAnimation() {

        pulseAnimator?.cancel()

        pulseAnimator = null

        micRing.scaleX = 1f
        micRing.scaleY = 1f
        micRing.alpha = 1f
    }

    // ============================================================
    // START LISTENING
    // ============================================================

    private fun startListening() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissionsIfNeeded()

            return
        }

        val intent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault()
                )

                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak a command..."
                )
            }

        speechRecognizer.startListening(
            intent
        )
    }

    // ============================================================
    // COMMAND ROUTER
    // ============================================================

    private fun handleCommand(
        command: String
    ) {

        when {

            // ====================================================
            // AC OFF
            // ====================================================

            isAcCommand(command) &&
                    command.contains("off") -> {

                speak("Turning off the AC")

                lifecycleScope.launch {

                    val result =
                        AcApiClient.turnOff()

                    result.onSuccess { response ->

                        android.util.Log.d(
                            "AC_API",
                            "TURN OFF SUCCESS: $response"
                        )

                        runOnUiThread {

                            acPowerStatus.text =
                                "OFF"

                            speak(
                                "AC turned off"
                            )
                        }
                    }

                    result.onFailure { error ->

                        android.util.Log.e(
                            "AC_API",
                            "TURN OFF ERROR",
                            error
                        )

                        runOnUiThread {

                            speak(
                                "I couldn't turn off the AC"
                            )
                        }
                    }
                }
            }

            // ====================================================
            // AC ON
            // ====================================================

            isAcCommand(command) &&
                    command.contains("on") -> {

                speak("Turning on the AC")

                lifecycleScope.launch {

                    val result =
                        AcApiClient.turnOn()

                    result.onSuccess { response ->

                        android.util.Log.d(
                            "AC_API",
                            "TURN ON SUCCESS: $response"
                        )

                        runOnUiThread {

                            acPowerStatus.text =
                                "ON"

                            acPowerStatus.setTextColor(
                                Color.rgb(
                                    105,
                                    240,
                                    174
                                )
                            )

                            speak(
                                "AC turned on"
                            )
                        }
                    }

                    result.onFailure { error ->

                        android.util.Log.e(
                            "AC_API",
                            "TURN ON ERROR",
                            error
                        )

                        runOnUiThread {

                            speak(
                                "I couldn't turn on the AC"
                            )
                        }
                    }
                }
            }

            // ====================================================
            // AC TEMPERATURE
            // ====================================================

            isAcCommand(command) &&
                    (
                            command.contains("degree") ||
                                    command.contains("temperature") ||
                                    Regex("\\d+")
                                        .containsMatchIn(command)
                            ) -> {

                val temp =
                    Regex("\\d+")
                        .find(command)
                        ?.value
                        ?.toIntOrNull()

                if (temp != null) {

                    if (temp !in 16..30) {

                        speak(
                            "Please choose a temperature between 16 and 30 degrees"
                        )

                        return
                    }

                    speak(
                        "Setting the AC to $temp degrees"
                    )

                    setAcTemperature(temp)

                } else {

                    speak(
                        "What temperature should I set the AC to?"
                    )
                }
            }

            // ====================================================
            // AC MODE
            // ====================================================

            isAcCommand(command) &&
                    (
                            command.contains("cool") ||
                                    command.contains("dry") ||
                                    command.contains("heat") ||
                                    command.contains("auto") ||
                                    command.contains("fan")
                            ) -> {

                val mode =
                    when {

                        command.contains("cool") ->
                            "cool"

                        command.contains("dry") ->
                            "dry"

                        command.contains("heat") ->
                            "heat"

                        command.contains("auto") ->
                            "auto"

                        else ->
                            "fan"
                    }

                setAcMode(mode)
            }

            // ====================================================
            // TV OPEN APP
            // ====================================================

            isTvCommand(command) &&
                    command.contains("open") -> {

                val appName =
                    TvBridge.KNOWN_APPS.keys
                        .firstOrNull {
                            command.contains(
                                it.lowercase(
                                    Locale.getDefault()
                                )
                            )
                        }

                val appId =
                    appName?.let {
                        TvBridge.KNOWN_APPS[it]
                    }

                if (appId != null) {

                    speak(
                        "Opening $appName on the TV"
                    )

                    TvBridge.launchApp(
                        appId
                    ) { success, message ->

                        if (!success) {
                            runOnUiThread {
                                speak(message)
                            }
                        }
                    }

                } else {

                    speak(
                        "I don't know that TV app yet"
                    )
                }
            }

            // ====================================================
            // TV VOLUME UP
            // ====================================================

            isTvCommand(command) &&
                    (
                            command.contains("volume up") ||
                                    command.contains("louder") ||
                                    command.contains("increase volume")
                            ) -> {

                TvBridge.volume(
                    "up"
                ) { success, message ->

                    if (!success) {
                        runOnUiThread {
                            speak(message)
                        }
                    }
                }
            }

            // ====================================================
            // TV VOLUME DOWN
            // ====================================================

            isTvCommand(command) &&
                    (
                            command.contains("volume down") ||
                                    command.contains("quieter") ||
                                    command.contains("decrease volume")
                            ) -> {

                TvBridge.volume(
                    "down"
                ) { success, message ->

                    if (!success) {
                        runOnUiThread {
                            speak(message)
                        }
                    }
                }
            }

            // ====================================================
            // TV MUTE
            // ====================================================

            isTvCommand(command) &&
                    command.contains("mute") -> {

                TvBridge.volume(
                    "mute"
                ) { success, message ->

                    if (!success) {
                        runOnUiThread {
                            speak(message)
                        }
                    }
                }
            }

            // ====================================================
            // TV HOME
            // ====================================================

            isTvCommand(command) &&
                    command.contains("home") -> {

                tvNavigation("home")
            }

            // ====================================================
            // TV BACK
            // ====================================================

            isTvCommand(command) &&
                    command.contains("back") -> {

                tvNavigation("back")
            }

            // ====================================================
            // TV PLAY / PAUSE
            // ====================================================

            isTvCommand(command) &&
                    (
                            command.contains("play") ||
                                    command.contains("pause")
                            ) -> {

                tvNavigation("play")
            }

            // ====================================================
            // TV UP
            // ====================================================

            isTvCommand(command) &&
                    command.contains("up") -> {

                tvNavigation("up")
            }

            // ====================================================
            // TV DOWN
            // ====================================================

            isTvCommand(command) &&
                    command.contains("down") -> {

                tvNavigation("down")
            }

            // ====================================================
            // TV LEFT
            // ====================================================

            isTvCommand(command) &&
                    command.contains("left") -> {

                tvNavigation("left")
            }

            // ====================================================
            // TV RIGHT
            // ====================================================

            isTvCommand(command) &&
                    command.contains("right") -> {

                tvNavigation("right")
            }

            // ====================================================
            // TV SELECT
            // ====================================================

            isTvCommand(command) &&
                    (
                            command.contains("select") ||
                                    command.contains("okay") ||
                                    command.contains(" ok")
                            ) -> {

                tvNavigation("select")
            }

            // ====================================================
            // TV POWER
            // ====================================================

            isTvCommand(command) &&
                    (
                            command.contains("power") ||
                                    command.contains("switch") ||
                                    command.endsWith(" on") ||
                                    command.endsWith(" off")
                            ) -> {

                tvPower()
            }

            // ====================================================
            // YOUTUBE SEARCH
            // ====================================================

            command.contains("search") &&
                    command.contains("on youtube") -> {

                val query =
                    command
                        .replace("search for", " ")
                        .replace("search", " ")
                        .replace("on youtube", " ")
                        .trim()

                if (query.isNotEmpty()) {

                    searchYoutube(query)

                } else {

                    speak(
                        "What should I search for on YouTube?"
                    )
                }
            }

            // ====================================================
            // GOOGLE SEARCH
            // ====================================================

            command.startsWith("search for") ||
                    command.startsWith("search") -> {

                val query =
                    command
                        .removePrefix("search for")
                        .removePrefix("search")
                        .trim()

                if (query.isNotEmpty()) {

                    openUrl(
                        "https://www.google.com/search?q=" +
                                Uri.encode(query)
                    )

                    speak(
                        "Searching for $query"
                    )

                } else {

                    speak(
                        "What should I search for?"
                    )
                }
            }

            // ====================================================
            // WHATSAPP
            // ====================================================

            command.contains("whatsapp") &&
                    command.contains("with") -> {

                val name =
                    command
                        .substringAfter("with")
                        .trim()

                if (name.isEmpty()) {

                    speak(
                        "Who should I open WhatsApp with?"
                    )

                } else {

                    val number =
                        lookupContactNumber(name)

                    if (number == null) {

                        speak(
                            "I couldn't find a contact named $name"
                        )

                    } else {

                        openWhatsAppChat(
                            number,
                            name,
                            false
                        )
                    }
                }
            }

            // ====================================================
            // CAMERA
            // ====================================================

            command.contains("camera") -> {

                try {

                    startActivity(
                        Intent(
                            "android.media.action.IMAGE_CAPTURE"
                        )
                    )

                    speak(
                        "Opening camera"
                    )

                } catch (e: Exception) {

                    speak(
                        "Couldn't open camera"
                    )
                }
            }

            // ====================================================
            // MAP
            // ====================================================

            command.contains("map") -> {

                openUrl(
                    "geo:0,0?q=" +
                            Uri.encode("nearby")
                )

                speak(
                    "Opening maps"
                )
            }

            // ====================================================
            // SETTINGS
            // ====================================================

            command.contains("settings") -> {

                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_SETTINGS
                    )
                )

                speak(
                    "Opening settings"
                )
            }

            // ====================================================
            // FLASHLIGHT OFF
            // ====================================================

            command.contains("flashlight") &&
                    command.contains("off") -> {

                toggleFlashlight(false)
            }

            // ====================================================
            // FLASHLIGHT ON
            // ====================================================

            command.contains("flashlight") ||
                    command.contains("torch") -> {

                toggleFlashlight(true)
            }

            // ====================================================
            // OPEN WHATSAPP
            // ====================================================

            command.contains("whatsapp") -> {

                openApp(
                    "com.whatsapp",
                    "WhatsApp"
                )
            }

            // ====================================================
            // OPEN INSTALLED APP
            // ====================================================

            (
                    command.startsWith("open ") ||
                            command.startsWith("launch ") ||
                            command.startsWith("start ")
                    ) &&
                    !isTvCommand(command) -> {

                val appName =
                    command
                        .removePrefix("open ")
                        .removePrefix("launch ")
                        .removePrefix("start ")
                        .trim()

                openAppByName(appName)
            }

            // ====================================================
            // CALL
            // ====================================================

            command.startsWith("call ") -> {

                val target =
                    command
                        .removePrefix("call ")
                        .trim()

                handleCallCommand(
                    target,
                    false
                )
            }

            // ====================================================
            // TIME
            // ====================================================

            command.contains("what's the time") ||
                    command.contains("what is the time") ||
                    command.contains("tell me the time") -> {

                val time =
                    SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(Date())

                speak(
                    "It's $time"
                )
            }

            // ====================================================
            // DATE
            // ====================================================

            command.contains("what's the date") ||
                    command.contains("what is the date") ||
                    command.contains("today's date") -> {

                val date =
                    SimpleDateFormat(
                        "EEEE, MMMM d, yyyy",
                        Locale.getDefault()
                    ).format(Date())

                speak(
                    "Today is $date"
                )
            }

            // ====================================================
            // GREETING
            // ====================================================

            command.contains("hello") ||
                    command.contains("hi ") -> {

                speak(
                    "Hello! How can I help you?"
                )
            }

            // ====================================================
            // WHO ARE YOU
            // ====================================================

            command.contains("who are you") -> {

                speak(
                    "I'm your voice assistant, built to help with quick tasks."
                )
            }

            // ====================================================
            // FALLBACK
            // ====================================================

            else -> {

                speak(
                    "I don't know how to do that yet."
                )
            }
        }
    }

    // ============================================================
    // AC COMMAND DETECTION
    // ============================================================

    private fun isAcCommand(
        command: String
    ): Boolean {

        return command.contains(
            "air condition"
        ) ||
                Regex("\\bac\\b")
                    .containsMatchIn(command)
    }

    // ============================================================
    // TV COMMAND DETECTION
    // ============================================================

    private fun isTvCommand(
        command: String
    ): Boolean {

        return command.contains(
            "television"
        ) ||
                Regex("\\btv\\b")
                    .containsMatchIn(command)
    }

    // ============================================================
    // URL
    // ============================================================

    private fun openUrl(
        url: String
    ) {

        try {

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )

            startActivity(intent)

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Unable to open link",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // YOUTUBE SEARCH
    // ============================================================

    private fun searchYoutube(
        query: String
    ) {

        try {

            val intent =
                Intent(
                    Intent.ACTION_SEARCH
                ).apply {

                    setPackage(
                        "com.google.android.youtube"
                    )

                    putExtra(
                        "query",
                        query
                    )
                }

            startActivity(intent)

        } catch (e: Exception) {

            openUrl(
                "https://www.youtube.com/results?search_query=" +
                        Uri.encode(query)
            )
        }

        speak(
            "Searching YouTube for $query"
        )
    }

    // ============================================================
    // OPEN APP
    // ============================================================

    private fun openApp(
        packageName: String,
        friendlyName: String
    ) {

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    packageName
                )

        if (launchIntent != null) {

            startActivity(
                launchIntent
            )

            speak(
                "Opening $friendlyName"
            )

        } else {

            speak(
                "$friendlyName isn't installed"
            )
        }
    }

    // ============================================================
    // INSTALLED APPS
    // ============================================================

    private fun getInstalledApps():
            List<Pair<String, String>> {

        val launcherIntent =
            Intent(
                Intent.ACTION_MAIN,
                null
            ).addCategory(
                Intent.CATEGORY_LAUNCHER
            )

        return packageManager
            .queryIntentActivities(
                launcherIntent,
                0
            )
            .map {

                it.loadLabel(
                    packageManager
                ).toString() to
                        it.activityInfo.packageName
            }
    }

    private fun openAppByName(
        spokenName: String
    ) {

        if (spokenName.isBlank()) {

            speak(
                "Which app should I open?"
            )

            return
        }

        val query =
            spokenName
                .trim()
                .lowercase(
                    Locale.getDefault()
                )

        val apps =
            getInstalledApps()

        val match =
            apps.firstOrNull {

                it.first
                    .lowercase(
                        Locale.getDefault()
                    ) == query

            } ?: apps.firstOrNull {

                it.first
                    .lowercase(
                        Locale.getDefault()
                    )
                    .contains(query)

            } ?: apps.firstOrNull {

                query.contains(
                    it.first
                        .lowercase(
                            Locale.getDefault()
                        )
                )
            }

        if (match != null) {

            val launchIntent =
                packageManager
                    .getLaunchIntentForPackage(
                        match.second
                    )

            if (launchIntent != null) {

                startActivity(
                    launchIntent
                )

                speak(
                    "Opening ${match.first}"
                )

                return
            }
        }

        speak(
            "I couldn't find an app called $spokenName"
        )
    }

    // ============================================================
    // PHONE NUMBER
    // ============================================================

    private fun sanitizePhoneNumber(
        number: String
    ): String {

        val trimmed =
            number.trim()

        val hasPlus =
            trimmed.startsWith("+")

        var digitsOnly =
            trimmed.filter {
                it.isDigit()
            }

        if (hasPlus) {
            return "+$digitsOnly"
        }

        if (
            digitsOnly.length == 11 &&
            digitsOnly.startsWith("0")
        ) {

            digitsOnly =
                digitsOnly.substring(1)
        }

        return if (
            digitsOnly.length == 10
        ) {

            "$DEFAULT_COUNTRY_CODE$digitsOnly"

        } else {

            digitsOnly
        }
    }

    // ============================================================
    // CALL
    // ============================================================

    private fun makeCall(
        number: String
    ) {

        val cleanNumber =
            sanitizePhoneNumber(number)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            val intent =
                Intent(
                    Intent.ACTION_CALL,
                    Uri.parse(
                        "tel:$cleanNumber"
                    )
                )

            startActivity(intent)

            speak(
                "Calling $cleanNumber"
            )

        } else {

            speak(
                "I need call permission first"
            )

            requestPermissionsIfNeeded()
        }
    }

    // ============================================================
    // CALL COMMAND
    // ============================================================

    private fun handleCallCommand(
        spoken: String,
        viaWhatsApp: Boolean
    ) {

        val query =
            spoken.trim()

        if (query.isEmpty()) {

            speak(
                "Who should I call?"
            )

            return
        }

        val isRawNumber =
            query.all {

                it.isDigit() ||
                        it == '+' ||
                        it == ' ' ||
                        it == '-'
            }

        val number =
            if (isRawNumber) {

                sanitizePhoneNumber(query)

            } else {

                lookupContactNumber(query)
            }

        if (number == null) {

            speak(
                "I couldn't find a contact named $query"
            )

            return
        }

        if (viaWhatsApp) {

            openWhatsAppChat(
                number,
                query,
                true
            )

        } else {

            makeCall(number)
        }
    }

    // ============================================================
    // CONTACT LOOKUP
    // ============================================================

    private fun lookupContactNumber(
        name: String
    ): String? {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissionsIfNeeded()

            return null
        }

        val cursor =
            contentResolver.query(

                ContactsContract
                    .CommonDataKinds
                    .Phone
                    .CONTENT_URI,

                arrayOf(

                    ContactsContract
                        .CommonDataKinds
                        .Phone
                        .DISPLAY_NAME,

                    ContactsContract
                        .CommonDataKinds
                        .Phone
                        .NUMBER
                ),

                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",

                arrayOf(
                    "%$name%"
                ),

                null
            )

        cursor?.use {

            if (it.moveToFirst()) {

                val numberIndex =
                    it.getColumnIndex(
                        ContactsContract
                            .CommonDataKinds
                            .Phone
                            .NUMBER
                    )

                if (numberIndex >= 0) {

                    return it.getString(
                        numberIndex
                    )
                }
            }
        }

        return null
    }

    // ============================================================
    // WHATSAPP
    // ============================================================

    private fun openWhatsAppChat(
        number: String,
        displayName: String,
        forCall: Boolean
    ) {

        val cleanNumber =
            sanitizePhoneNumber(number)

        val digitsOnly =
            cleanNumber.filter {
                it.isDigit()
            }

        val uri =
            Uri.parse(
                "https://wa.me/$digitsOnly"
            )

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                uri
            ).apply {

                setPackage(
                    "com.whatsapp"
                )
            }

        try {

            startActivity(intent)

            if (forCall) {

                speak(
                    "Opening WhatsApp chat with $displayName. Tap the call button to start the call."
                )

            } else {

                speak(
                    "Opening WhatsApp chat with $displayName"
                )
            }

        } catch (e: Exception) {

            speak(
                "WhatsApp isn't installed"
            )
        }
    }

    // ============================================================
    // SMS
    // ============================================================

    private fun sendSms(
        number: String,
        message: String
    ) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            val smsManager =
                SmsManager.getDefault()

            smsManager.sendTextMessage(
                number,
                null,
                message,
                null,
                null
            )

            speak(
                "Message sent"
            )

        } else {

            speak(
                "I need SMS permission first"
            )

            requestPermissionsIfNeeded()
        }
    }

    // ============================================================
    // FLASHLIGHT
    // ============================================================

    private fun toggleFlashlight(
        turnOn: Boolean
    ) {

        try {

            val cameraManager =
                getSystemService(
                    CAMERA_SERVICE
                ) as CameraManager

            val cameraId =
                cameraManager
                    .cameraIdList
                    .firstOrNull()

            if (cameraId == null) {

                speak(
                    "No camera was found"
                )

                return
            }

            cameraManager.setTorchMode(
                cameraId,
                turnOn
            )

            flashlightOn =
                turnOn

            speak(
                if (turnOn) {
                    "Flashlight on"
                } else {
                    "Flashlight off"
                }
            )

        } catch (e: Exception) {

            speak(
                "Couldn't access the flashlight"
            )
        }
    }

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        pulseAnimator?.cancel()

        super.onDestroy()
    }
}