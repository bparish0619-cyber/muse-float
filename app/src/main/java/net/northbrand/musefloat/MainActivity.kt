package net.northbrand.musefloat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The one and only settings screen. It explains the bubble, walks the user
 * through the "display over other apps" permission, lets them pick what a
 * bubble tap opens, and starts/stops the floating service. Below that:
 * the boot-restart toggle, a bubble size slider, and an idle-opacity
 * slider, both applied to the live bubble. At the bottom: the voice
 * notes microphone grant (a service cannot ask for runtime permissions,
 * so the quick capture card sends the user here for it).
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var targetApp: RadioButton
    private lateinit var targetNote: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var voiceNoteStatus: TextView
    private lateinit var micButton: Button
    private lateinit var faceDayPreview: ImageView
    private lateinit var faceNightPreview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // v1.5: keep the whole screen clear of the bars at the top.
        padContentBelowBars()

        statusText = findViewById(R.id.statusText)
        overlayButton = findViewById(R.id.overlayButton)
        targetApp = findViewById(R.id.targetApp)
        targetNote = findViewById(R.id.targetNote)
        sizeLabel = findViewById(R.id.sizeLabel)
        opacityLabel = findViewById(R.id.opacityLabel)
        voiceNoteStatus = findViewById(R.id.voiceNoteStatus)
        micButton = findViewById(R.id.micButton)
        val targetWeb: RadioButton = findViewById(R.id.targetWeb)
        val targetGroup: RadioGroup = findViewById(R.id.targetGroup)

        // Overlay permission: Android only grants this from its own settings
        // screen, so the button just takes the user there.
        overlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Pre-select the saved choice, or the sensible default (app if a
        // Muse-labeled app is installed, otherwise the web).
        when (Prefs.effectiveTarget(this)) {
            Prefs.TARGET_APP -> targetApp.isChecked = true
            else -> targetWeb.isChecked = true
        }
        targetGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setTarget(
                this,
                if (checkedId == R.id.targetApp) Prefs.TARGET_APP else Prefs.TARGET_WEB
            )
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { startBubble() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            Prefs.setEnabled(this, false)
            stopService(Intent(this, FloatingService::class.java))
            Prefs.setRunning(this, false)
            refreshStatus()
        }

        // Boot restart toggle: whether the bubble may come back on its own
        // after a reboot (only matters while the bubble is enabled).
        val bootSwitch: Switch = findViewById(R.id.bootSwitch)
        bootSwitch.isChecked = Prefs.bootEnabled(this)
        bootSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setBootEnabled(this, isChecked)
        }

        // Bubble size slider: persists on every change; the live bubble is
        // resized when the thumb lifts, so it does not judder mid-slide.
        val sizeSeek: SeekBar = findViewById(R.id.sizeSeek)
        sizeSeek.min = Prefs.SIZE_MIN_DP
        sizeSeek.max = Prefs.SIZE_MAX_DP
        sizeSeek.progress = Prefs.bubbleSizeDp(this)
        updateSizeLabel(sizeSeek.progress)
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                Prefs.setBubbleSizeDp(this@MainActivity, progress)
                updateSizeLabel(progress)
            }

            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = applySettingsToService()
        })

        // Idle opacity slider: how see-through the bubble gets when idle.
        val opacitySeek: SeekBar = findViewById(R.id.opacitySeek)
        opacitySeek.min = Prefs.OPACITY_MIN_PCT
        opacitySeek.max = Prefs.OPACITY_MAX_PCT
        opacitySeek.progress = Prefs.idleOpacityPct(this)
        updateOpacityLabel(opacitySeek.progress)
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                Prefs.setIdleOpacityPct(this@MainActivity, progress)
                updateOpacityLabel(progress)
            }

            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = applySettingsToService()
        })

        // Voice notes: the microphone runtime permission can only be
        // requested from an activity, so the grant lives here; the
        // capture card's mic button sends the user to this screen when
        // it has not been granted yet.
        micButton.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        refreshMicStatus()

        // Bubble face: day and night picks from the user's own images.
        // The picks are copied into the app's private storage by Faces,
        // so no lasting photo-library access is needed.
        faceDayPreview = findViewById(R.id.faceDayPreview)
        faceNightPreview = findViewById(R.id.faceNightPreview)
        clipCircle(faceDayPreview)
        clipCircle(faceNightPreview)
        findViewById<Button>(R.id.faceDayButton).setOnClickListener { pickFace(night = false) }
        findViewById<Button>(R.id.faceNightButton).setOnClickListener { pickFace(night = true) }
        findViewById<Button>(R.id.faceResetButton).setOnClickListener {
            Faces.clearAll(this)
            refreshFacePreviews()
            applySettingsToService()
            Toast.makeText(this, "Back to the default face.", Toast.LENGTH_SHORT).show()
        }
        refreshFacePreviews()

        // v1.4 restyle: gold control tints and the round header face.
        styleControls()

        // Android 13+: the foreground-service notification needs this
        // runtime permission. The bubble works without it; the notification
        // (and its Stop button) simply stays hidden.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * v1.5 top-clipping fix. This app targets API 35, and on Android 15
     * that means the system forces edge-to-edge drawing: the window no
     * longer tucks its content inside the system windows, so the settings
     * ScrollView is laid out from the physical top of the screen and the
     * status bar plus the theme's action bar draw across its top ~88dp.
     * Nothing in the app handled window insets, so the header row (the
     * first 20dp padding + 44dp row + 14dp margin = 78dp of content)
     * vanished behind the bars and the intro line below it peeked out
     * with its glyph tops sliced off.
     *
     * The fix: pad the scroll root down by the system bars' top inset
     * plus the action bar's height, so the content starts fully below
     * both. The content's own 20dp padding (activity_main.xml) then
     * provides the breathing room under the bars, and ScrollView keeps
     * the padded offset when scrolled back to the top.
     */
    @Suppress("DEPRECATION") // systemWindowInsetTop is the pre-API-30 path
    private fun padContentBelowBars() {
        val root = findViewById<View>(R.id.settingsRoot)
        val actionBarPx = actionBarHeightPx()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val barsTop = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout()
                ).top
            } else {
                insets.systemWindowInsetTop
            }
            // Recomputed from the insets on every dispatch, never added
            // on top of a previous pass, so rotation and re-layouts stay
            // exact. Left, right, and bottom padding are untouched.
            v.setPadding(
                v.paddingLeft,
                barsTop + actionBarPx,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    /** The theme's action bar height in px (56dp for Theme.Material). */
    private fun actionBarHeightPx(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            0
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshTargetAvailability()
        refreshMicStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Request code 2 is the microphone ask above; refreshMicStatus
        // turns the outcome into the status line either way.
        if (requestCode == 2) refreshMicStatus()
    }

    /**
     * Northbrand control tints for the v1.4 settings restyle: gold where
     * the stock Material theme used teal. State lists so checked/on reads
     * gold while unchecked/off stays muted or hairline. Also clips the
     * header thumbnail to a circle, the same trick the bubble uses.
     */
    private fun styleControls() {
        val gold = Color.rgb(0xc9, 0xa2, 0x27)
        val line = Color.rgb(0x2c, 0x30, 0x3e)
        val muted = Color.rgb(0xa7, 0xaa, 0xbc)
        fun onOff(on: Int, off: Int) = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(on, off)
        )
        val goldOnly = ColorStateList.valueOf(gold)

        for (id in intArrayOf(R.id.sizeSeek, R.id.opacitySeek)) {
            val seek = findViewById<SeekBar>(id)
            seek.thumbTintList = goldOnly
            seek.progressTintList = goldOnly
            seek.progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        for (id in intArrayOf(R.id.targetApp, R.id.targetWeb)) {
            findViewById<RadioButton>(id).buttonTintList = onOff(gold, muted)
        }
        val boot = findViewById<Switch>(R.id.bootSwitch)
        boot.thumbTintList = onOff(gold, muted)
        boot.trackTintList = onOff(gold, line)

        val thumb = findViewById<ImageView>(R.id.headerThumb)
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        thumb.clipToOutline = true
    }

    /** Voice note status: hides the grant button once access is on. */
    private fun refreshMicStatus() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        micButton.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        voiceNoteStatus.text = if (granted) {
            "Microphone access is on. Voice notes are ready."
        } else {
            "Microphone access is off, so voice notes stay off until it is granted."
        }
    }

    private fun updateSizeLabel(dp: Int) {
        sizeLabel.text = "Bubble size: $dp dp"
    }

    private fun updateOpacityLabel(pct: Int) {
        opacityLabel.text = "Idle fade opacity: $pct%"
    }

    /** Nudges a running service to re-read the appearance preferences. */
    private fun applySettingsToService() {
        if (!Prefs.running(this)) return
        try {
            startService(
                Intent(this, FloatingService::class.java)
                    .setAction(FloatingService.ACTION_APPLY_SETTINGS)
            )
        } catch (_: Exception) {
            // Service stopped between the check and the call; the saved
            // preferences still apply on its next start.
        }
    }

    /** Hides the grant button once granted, and keeps the status honest. */
    private fun refreshStatus() {
        val granted = Settings.canDrawOverlays(this)
        overlayButton.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        val snoozed = Prefs.snoozeUntil(this) > System.currentTimeMillis()
        statusText.text = when {
            !granted -> "Bubble is off. First grant the overlay permission below."
            Prefs.running(this) && snoozed ->
                "Bubble is snoozed. It comes back on its own, or tap Show bubble on its notification."
            Prefs.running(this) -> "Bubble is on."
            else -> "Bubble is off. Tap Start bubble when ready."
        }
    }

    /** Disables the "Muse app" choice (with a note) when none is installed. */
    private fun refreshTargetAvailability() {
        val found = Prefs.findMusePackage(this) != null
        targetApp.isEnabled = found
        targetNote.text = if (found) {
            ""
        } else {
            "No app labeled Muse was found on this phone, so a tap opens the web instead."
        }
        // If the app choice is impossible and it was selected, fall back.
        if (!found && targetApp.isChecked) {
            findViewById<RadioButton>(R.id.targetWeb).isChecked = true
            Prefs.setTarget(this, Prefs.TARGET_WEB)
        }
    }

    // ---- bubble face ----------------------------------------------------

    /** Opens the system document picker for one face slot. */
    private fun pickFace(night: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, if (night) REQ_FACE_NIGHT else REQ_FACE_DAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val night = when (requestCode) {
            REQ_FACE_DAY -> false
            REQ_FACE_NIGHT -> true
            else -> return
        }
        if (Faces.save(this, night, uri)) {
            refreshFacePreviews()
            applySettingsToService()
            Toast.makeText(this, "Face saved.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "That image could not be read.", Toast.LENGTH_LONG).show()
        }
    }

    /** Repaints the two slot previews and the header thumbnail. */
    private fun refreshFacePreviews() {
        faceDayPreview.setImageBitmap(Faces.bitmapOrDefault(this, night = false))
        faceNightPreview.setImageBitmap(Faces.bitmapOrDefault(this, night = true))
        findViewById<ImageView>(R.id.headerThumb)
            .setImageBitmap(Faces.bitmapOrDefault(this, night = false))
    }

    /** Clips a square image view to a circle, like the bubble itself. */
    private fun clipCircle(view: ImageView) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        view.clipToOutline = true
    }

    private companion object {
        // Face picker request codes (1 and 2 belong to the permission asks).
        const val REQ_FACE_DAY = 10
        const val REQ_FACE_NIGHT = 11
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Grant the overlay permission first, then tap Start.",
                Toast.LENGTH_LONG
            ).show()
            overlayButton.performClick()
            return
        }
        Prefs.setEnabled(this, true)
        // A fresh start clears any leftover snooze from a previous run.
        Prefs.setSnoozeUntil(this, 0L)
        val intent = Intent(this, FloatingService::class.java)
        // startForegroundService (not startService) because the service may
        // be launched while this activity is about to leave the foreground.
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Muse bubble is on.", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }
}
