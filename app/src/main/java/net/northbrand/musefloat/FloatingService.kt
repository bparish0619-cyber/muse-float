package net.northbrand.musefloat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

/**
 * Shows the user's chosen avatar as a floating bubble over other apps.
 *
 * Gestures (kept deliberately Messenger-like):
 *  - tap:         reopen Muse (the chosen target; see Prefs)
 *  - double tap:  snooze the bubble for an hour; it reappears on its own,
 *                 or early via the notification's "Show bubble" action
 *  - long press:  quick capture card; type a thought, Send shares it as
 *                 plain text so it lands staged in the Muse composer.
 *                 The card's mic button records a voice note instead
 *                 (up to 5 minutes), shared the same way as an audio file
 *  - drag:        move it; release snaps to the nearest edge, and while
 *                 dragging an X target sits at the bottom center of the
 *                 screen; dropping the bubble on it dismisses the bubble
 *
 * The face swaps by time of day (day face 07:00 to 19:00, night face after
 * that), the bubble idles down to a chosen opacity after a few seconds,
 * and a soft pulse plays briefly after a tap or a drag.
 *
 * It runs as a foreground service (Android requires one for a persistent
 * overlay started this way), declared as type "specialUse" for Android 14.
 */
class FloatingService : Service() {

    companion object {
        // Intent actions other parts of the app send back to this service.
        const val ACTION_STOP = "net.northbrand.musefloat.STOP"
        const val ACTION_SHOW = "net.northbrand.musefloat.SHOW"
        const val ACTION_APPLY_SETTINGS = "net.northbrand.musefloat.APPLY_SETTINGS"

        // ---- CHANGE-ME values: feel and timing -------------------------
        private const val IDLE_FADE_MS = 3000L      // idle time before fading
        private const val TAP_SLOP_DP = 10          // movement still counts as a tap
        private const val SNAP_MS = 140L            // edge-snap animation length
        private const val LONG_PRESS_MS = 500L      // hold time for quick capture
        private const val DOUBLE_TAP_MS = 300L      // window between the two taps
        private const val SNOOZE_MS = 60L * 60L * 1000L // snooze length: 1 hour
        private const val PULSE_MS = 2100L          // soft pulse total length
        private const val DAY_START_HOUR = 7        // day face from 07:00
        private const val NIGHT_START_HOUR = 19     // night face from 19:00
        private const val DISMISS_SIZE_DP = 72      // X target diameter
        private const val DISMISS_BOTTOM_DP = 84    // X target height above screen bottom
        private const val DISMISS_RADIUS_DP = 90    // drop distance that counts as "on the X"
        private const val BUBBLE_RING_DP = 3        // bubble ring stroke; about half of it shows inside the circle (0 = no ring)
        private const val MAX_RECORD_MS = 5 * 60 * 1000 // voice note cap: 5 minutes, auto-sends
        // ---------------------------------------------------------------

        private const val CHANNEL_ID = "muse_float_status"
        private const val NOTIFICATION_ID = 42
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // The bubble and its layout params; both are null while snoozed.
    private var bubble: ImageView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var currentFaceKey: String = ""

    // Drag-to-dismiss target (created lazily, added only while dragging).
    private var dismissTarget: TextView? = null
    private var dismissShown = false
    private var dismissHot = false

    // Quick capture card (created lazily, at most one at a time).
    private var captureCard: LinearLayout? = null
    private var captureEdit: EditText? = null

    // Voice note state: non-null only while the capture card is in
    // recording mode. The recorded file lives in cacheDir and only ever
    // leaves the phone through the share chooser (see shareAudio).
    private var captureTextBox: View? = null
    private var captureRecordBox: View? = null
    private var recordTimeText: TextView? = null
    private var recordDot: View? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordStartMs = 0L
    private var recordPulse: ValueAnimator? = null

    private var snapAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        val until = Prefs.snoozeUntil(this)
        val now = System.currentTimeMillis()
        if (until > now) {
            // Restarted mid-snooze: stay hidden, wake when the snooze ends.
            scheduleSnoozeWake(until - now)
        } else {
            if (until != 0L) Prefs.setSnoozeUntil(this, 0L)
            showBubble()
        }
        scheduleFaceCheck()
        Prefs.setRunning(this, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // An explicit stop also clears "enabled", so the bubble
                // does not resurrect itself on the next reboot.
                Prefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> endSnooze()
            ACTION_APPLY_SETTINGS -> applySettings()
        }
        // Sticky: if the system kills the service, bring the bubble back.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closeCapture()
        hideDismissTarget()
        removeBubble()
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    private fun isSnoozed(): Boolean =
        Prefs.snoozeUntil(this) > System.currentTimeMillis()

    // ------------------------------------------------------------------
    // Foreground notification ("Muse bubble is on", Stop + Show actions)
    // ------------------------------------------------------------------
    private fun buildNotification(): Notification {
        val snoozed = isSnoozed()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_float)
            .setLargeIcon(Faces.bitmapOrDefault(this, !isDaytime()))
            .setContentTitle(if (snoozed) "Muse bubble is snoozed" else "Muse bubble is on")
            .setContentText(
                if (snoozed) "Hidden for up to an hour. Show brings it back now."
                else "Drag the bubble, or tap it to open Muse."
            )
            .setContentIntent(openApp)
            .addAction(0, "Show bubble", show)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Muse Float",
                NotificationManager.IMPORTANCE_LOW // no sound, no heads-up
            )
        )
        val notification = buildNotification()
        // Android 14+: a specialUse foreground service must pass its type
        // explicitly when it starts; older versions take it from the manifest.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------
    // The bubble itself
    // ------------------------------------------------------------------
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(Prefs.bubbleSizeDp(this))
        val view = ImageView(this).apply {
            contentDescription = "Open Muse"
            // The bubble is a circle, chat-head style. The artwork stays a
            // square crop; the round shape comes from clipping the view
            // itself to an oval, with CENTER_CROP filling the square so
            // the circle shows the middle of the crop. Because the clip
            // lives on the view rather than in the artwork, it survives
            // the idle fade (alpha) and the soft pulse (scale), and
            // touches still land on the full square bounds; only the
            // visible rendering is round.
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            // A thin hairline ring just inside the circle's edge, in the
            // Northbrand line color. It is a foreground so it draws over
            // the artwork; the stroke is centered on the circle's edge
            // and the outline clip trims the outer half away, so about
            // half of BUBBLE_RING_DP ends up visible.
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(BUBBLE_RING_DP), Color.rgb(0x2c, 0x30, 0x3e))
            }
        }
        bubble = view
        currentFaceKey = "" // force applyFace to set the image
        applyFace()

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // First run: park on the right edge, a third of the way down.
            // Later runs: wherever the user last left it.
            x = Prefs.bubbleX(this@FloatingService) ?: (screenWidth() - size)
            y = Prefs.bubbleY(this@FloatingService) ?: (screenHeight() / 3)
        }
        clampToScreen()
        view.setOnTouchListener(BubbleTouchListener())
        windowManager.addView(view, bubbleParams)
        resetIdleTimer()
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        pulseAnimator?.cancel()
        handler.removeCallbacks(fadeRunnable)
        handler.removeCallbacks(pendingTapRunnable)
        handler.removeCallbacks(longPressRunnable)
        bubble?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // View already gone; nothing to clean up.
            }
        }
        bubble = null
    }

    private fun clampToScreen() {
        val size = bubbleParams.width
        bubbleParams.x = bubbleParams.x.coerceIn(0, screenWidth() - size)
        bubbleParams.y = bubbleParams.y.coerceIn(0, screenHeight() - size)
    }

    /** Live-apply size (and future appearance settings) from the main screen. */
    private fun applySettings() {
        val view = bubble ?: return
        val size = dp(Prefs.bubbleSizeDp(this))
        if (bubbleParams.width != size) {
            bubbleParams.width = size
            bubbleParams.height = size
            clampToScreen()
            try {
                windowManager.updateViewLayout(view, bubbleParams)
            } catch (_: Exception) {
                // Bubble went away mid-update; the next show uses the prefs.
            }
        }
        // Opacity needs no work here: the fade runnable reads it fresh.
        // A face picked on the main screen applies live through here too.
        applyFace()
    }

    // ------------------------------------------------------------------
    // Day and night faces
    // ------------------------------------------------------------------
    private fun isDaytime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= DAY_START_HOUR && hour < NIGHT_START_HOUR
    }

    private fun applyFace() {
        val night = !isDaytime()
        // The stamp changes when the day/night slot flips (07:00 / 19:00)
        // and whenever the user picks or clears a face, so a new pick
        // applies live without restarting the bubble.
        val stamp = Faces.stamp(this, night)
        if (stamp != currentFaceKey) {
            currentFaceKey = stamp
            bubble?.setImageBitmap(Faces.bitmapOrDefault(this, night))
        }
    }

    private val faceRunnable = Runnable {
        applyFace()
        scheduleFaceCheck()
    }

    /** Re-check the face at the next 07:00 / 19:00 boundary. */
    private fun scheduleFaceCheck() {
        handler.removeCallbacks(faceRunnable)
        val now = Calendar.getInstance()
        fun at(hour: Int, addDays: Int): Calendar {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, addDays)
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c
        }
        val next = listOf(at(DAY_START_HOUR, 0), at(NIGHT_START_HOUR, 0), at(DAY_START_HOUR, 1))
            .first { it.after(now) }
        val delay = (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        handler.postDelayed(faceRunnable, delay)
    }

    // ------------------------------------------------------------------
    // Gestures: tap, double tap, long press, drag
    // ------------------------------------------------------------------
    private var lastTapUpMs = 0L

    private val pendingTapRunnable = Runnable {
        openTarget()
        pulse()
    }

    private val longPressRunnable = Runnable {
        if (gestureDown && !gestureDragging) {
            longPressFired = true
            openCapture()
        }
    }

    // Gesture state shared between the touch listener and the runnables.
    private var gestureDown = false
    private var gestureDragging = false
    private var longPressFired = false

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    pulseAnimator?.cancel()
                    // A fresh press cancels a still-pending single tap; if
                    // this press ends as a second tap, it becomes a snooze.
                    handler.removeCallbacks(pendingTapRunnable)
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    gestureDown = true
                    gestureDragging = false
                    longPressFired = false
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    wakeBubble()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!gestureDragging && abs(dx) + abs(dy) > dp(TAP_SLOP_DP)) {
                        gestureDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        showDismissTarget()
                    }
                    if (gestureDragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampToScreen()
                        windowManager.updateViewLayout(view, bubbleParams)
                        setDismissHot(overDismissTarget())
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureDown = false
                    handler.removeCallbacks(longPressRunnable)
                    val wasDragging = gestureDragging
                    val wasLongPress = longPressFired
                    gestureDragging = false
                    longPressFired = false
                    hideDismissTarget()
                    when {
                        event.action == MotionEvent.ACTION_CANCEL -> Unit
                        wasLongPress -> Unit // quick capture already opened
                        wasDragging -> {
                            if (overDismissTarget()) {
                                dismissBubble()
                                return true
                            }
                            snapToEdge()
                        }
                        else -> handleTapUp()
                    }
                    resetIdleTimer()
                    return true
                }
            }
            return false
        }
    }

    /** Single tap opens Muse; a second tap inside the window snoozes. */
    private fun handleTapUp() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUpMs <= DOUBLE_TAP_MS) {
            lastTapUpMs = 0L
            handler.removeCallbacks(pendingTapRunnable)
            snooze()
        } else {
            lastTapUpMs = now
            // Wait out the double-tap window before treating this as a tap.
            handler.postDelayed(pendingTapRunnable, DOUBLE_TAP_MS)
        }
    }

    /** Glide to whichever vertical edge the bubble's center is closest to. */
    private fun snapToEdge() {
        val view = bubble ?: return
        val size = bubbleParams.width
        val maxX = screenWidth() - size
        val targetX = if (bubbleParams.x + size / 2 < screenWidth() / 2) 0 else maxX
        val fromX = bubbleParams.x
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = SNAP_MS
            addUpdateListener { animator ->
                bubbleParams.x = animator.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, bubbleParams)
                } catch (_: Exception) {
                    cancel()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Prefs.setBubblePos(this@FloatingService, bubbleParams.x, bubbleParams.y)
                    pulse()
                }
            })
            start()
        }
    }

    /** Dropping the bubble on the X: a full stop, same as pressing Stop. */
    private fun dismissBubble() {
        Prefs.setEnabled(this, false)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Drag-to-dismiss target
    // ------------------------------------------------------------------
    private fun dismissCenter(): Pair<Int, Int> =
        Pair(
            screenWidth() / 2,
            screenHeight() - dp(DISMISS_BOTTOM_DP) - dp(DISMISS_SIZE_DP) / 2
        )

    private fun overDismissTarget(): Boolean {
        val size = bubbleParams.width
        val cx = bubbleParams.x + size / 2
        val cy = bubbleParams.y + size / 2
        val (tx, ty) = dismissCenter()
        val dx = cx - tx
        val dy = cy - ty
        val r = dp(DISMISS_RADIUS_DP)
        return dx * dx + dy * dy < r * r
    }

    private fun buildDismissTarget(): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(96, 96, 104))
            setStroke(dp(2), Color.WHITE)
        }
        return TextView(this).apply {
            text = "✕"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg
            contentDescription = "Drag here to dismiss the bubble"
        }
    }

    private fun showDismissTarget() {
        if (dismissShown) return
        val target = dismissTarget ?: buildDismissTarget().also { dismissTarget = it }
        val lp = WindowManager.LayoutParams(
            dp(DISMISS_SIZE_DP), dp(DISMISS_SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DISMISS_BOTTOM_DP)
        }
        try {
            windowManager.addView(target, lp)
            dismissShown = true
            setDismissHot(false)
        } catch (_: Exception) {
            // If the target cannot be shown, dragging still works normally.
        }
    }

    private fun hideDismissTarget() {
        if (!dismissShown) return
        dismissTarget?.let { target ->
            try {
                windowManager.removeView(target)
            } catch (_: Exception) {
                // Already gone.
            }
        }
        dismissShown = false
        dismissHot = false
    }

    private fun setDismissHot(hot: Boolean) {
        if (hot == dismissHot) return
        dismissHot = hot
        val target = dismissTarget ?: return
        (target.background as? GradientDrawable)?.setColor(
            if (hot) Color.rgb(178, 44, 36) else Color.rgb(96, 96, 104)
        )
        target.scaleX = if (hot) 1.15f else 1f
        target.scaleY = if (hot) 1.15f else 1f
    }

    // ------------------------------------------------------------------
    // Snooze (double tap): hide for an hour, service stays alive
    // ------------------------------------------------------------------
    private val snoozeWakeRunnable = Runnable { endSnooze() }

    private fun snooze() {
        Toast.makeText(this, "Bubble snoozed for an hour.", Toast.LENGTH_SHORT).show()
        Prefs.setSnoozeUntil(this, System.currentTimeMillis() + SNOOZE_MS)
        hideDismissTarget()
        removeBubble()
        scheduleSnoozeWake(SNOOZE_MS)
        updateNotification()
    }

    private fun scheduleSnoozeWake(delayMs: Long) {
        handler.removeCallbacks(snoozeWakeRunnable)
        if (delayMs > 0) handler.postDelayed(snoozeWakeRunnable, delayMs)
    }

    private fun endSnooze() {
        handler.removeCallbacks(snoozeWakeRunnable)
        val wasSnoozed = isSnoozed() || bubble == null
        Prefs.setSnoozeUntil(this, 0L)
        if (bubble == null) showBubble()
        if (wasSnoozed) updateNotification()
    }

    // ------------------------------------------------------------------
    // Quick capture (long press): a small card, Send shares the text
    // ------------------------------------------------------------------
    private fun openCapture() {
        if (captureCard != null) return
        wakeBubble()
        // Northbrand styling, matching the pages: dark panel with a
        // hairline border, off-white text, one gold action.
        val ink = Color.rgb(0x14, 0x16, 0x1c)
        val panel = Color.rgb(0x1c, 0x1f, 0x27)
        val hairline = Color.rgb(0x2c, 0x30, 0x3e)
        val paper = Color.rgb(0xec, 0xe9, 0xdf)
        val muted = Color.rgb(0xa7, 0xaa, 0xbc)
        val gold = Color.rgb(0xc9, 0xa2, 0x27)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), hairline)
            }
        }
        // Header: her current face in a small circle, title, subtitle.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val face = ImageView(this).apply {
            setImageBitmap(Faces.bitmapOrDefault(this@FloatingService, !isDaytime()))
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        val headerTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        val title = TextView(this).apply {
            text = "Quick capture"
            textSize = 17f
            setTextColor(paper)
            setTypeface(null, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Send a thought to Muse"
            textSize = 13f
            setTextColor(muted)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        headerTexts.addView(title)
        headerTexts.addView(subtitle)
        header.addView(face)
        header.addView(headerTexts)
        // Text mode: the field and its button row live in one container,
        // swapped out wholesale for the recording layout (built below)
        // while a voice note is being recorded.
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val edit = EditText(this).apply {
            hint = "Type a thought for Muse..."
            setTextColor(paper)
            setHintTextColor(muted)
            textSize = 15f
            setSingleLine(false)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            // Dark input surface; the border lights up gold while focused,
            // and the text cursor is gold too (cursor drawable needs 29+).
            fun fieldBg(strokeColor: Int) = GradientDrawable().apply {
                setColor(ink)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), strokeColor)
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fieldBg(gold))
                addState(intArrayOf(), fieldBg(hairline))
            }
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply {
                    setColor(gold)
                    setSize(dp(2), dp(20))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        // The card's two button styles, shared by text mode and record
        // mode: Cancel is quiet muted text, Send is the one gold pill.
        fun quietButton(label: String) = Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(muted)
            background = null
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
        }
        fun goldButton(label: String) = Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(ink)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(gold)
                cornerRadius = dp(999).toFloat()
            }
            minWidth = 0
            stateListAnimator = null
            setPadding(dp(22), 0, dp(22), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        // Mic: far left, opposite Cancel/Send. Tapping it switches this
        // card into recording mode. Needs the RECORD_AUDIO runtime
        // permission, which the settings screen owns; without it the tap
        // explains itself and opens that grant flow (see onMicTapped).
        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = "Record a voice note"
            isClickable = true
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ink)
                setStroke(dp(1), hairline)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        mic.setOnClickListener { onMicTapped() }
        val cancel = quietButton("Cancel")
        val send = goldButton("Send")
        cancel.setOnClickListener { closeCapture() }
        send.setOnClickListener {
            val text = edit.text.toString().trim()
            // Nothing is stored: the text exists only in this card, and
            // after Send it lives only in the receiving app's composer.
            if (text.isNotEmpty()) shareText(text)
            closeCapture()
        }
        row.addView(mic)
        // Spacer pushes Cancel/Send to the right edge, mic stays left.
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        row.addView(cancel)
        row.addView(send)
        textBox.addView(edit)
        textBox.addView(row)

        // Recording mode layout (hidden until the mic is tapped): a
        // pulsing gold dot, the elapsed time, and Cancel/Send. Cancel
        // discards the take and returns to the text card; Send shares
        // the audio file and closes.
        val recordBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        val recDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(gold)
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(10)
            }
        }
        val recTime = TextView(this).apply {
            text = "0:00"
            textSize = 22f
            setTextColor(paper)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        recRow.addView(recDot)
        recRow.addView(recTime)
        val recButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val recCancel = quietButton("Cancel")
        val recSend = goldButton("Send")
        recCancel.setOnClickListener { finishRecording(send = false) }
        recSend.setOnClickListener { finishRecording(send = true) }
        recButtons.addView(recCancel)
        recButtons.addView(recSend)
        recordBox.addView(recRow)
        recordBox.addView(recButtons)
        card.addView(header)
        card.addView(textBox)
        card.addView(recordBox)

        val lp = WindowManager.LayoutParams(
            (screenWidth() * 0.86).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (for the keyboard) but touches outside still reach
            // the app below, and arrive here as ACTION_OUTSIDE to close.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        card.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                closeCapture()
                true
            } else {
                false
            }
        }
        try {
            windowManager.addView(card, lp)
        } catch (_: Exception) {
            return
        }
        captureCard = card
        captureEdit = edit
        captureTextBox = textBox
        captureRecordBox = recordBox
        recordTimeText = recTime
        recordDot = recDot
        edit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeCapture() {
        // Closing the card mid-recording counts as a cancel: stop and
        // delete the take, nothing is shared.
        if (recorder != null) finishRecording(send = false, refocus = false)
        val card = captureCard ?: return
        captureEdit?.let { edit ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        }
        try {
            windowManager.removeView(card)
        } catch (_: Exception) {
            // Already gone.
        }
        captureCard = null
        captureEdit = null
        captureTextBox = null
        captureRecordBox = null
        recordTimeText = null
        recordDot = null
        resetIdleTimer()
    }

    private fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            // A chooser, so the text can land in the Muse app's share
            // target (its composer) or anywhere else the user picks.
            val chooser = Intent.createChooser(send, "Send to Muse")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, "No app accepted the text.", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Voice notes: the capture card's mic button records an .m4a into
    // cacheDir; Send shares it through the chooser, exactly like text
    // ------------------------------------------------------------------

    /** Elapsed-time ticker for recording mode (200ms is plenty smooth). */
    private val recordTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val elapsed = SystemClock.elapsedRealtime() - recordStartMs
            val totalSec = (elapsed / 1000).toInt()
            recordTimeText?.text =
                "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
            if (elapsed >= MAX_RECORD_MS) {
                // Backup for the recorder's own max-duration callback.
                finishRecording(send = true)
                return
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun onMicTapped() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // A service cannot ask for runtime permissions, so the
            // settings screen owns the ask: explain, close, and take the
            // user to its grant button.
            Toast.makeText(
                this,
                "Microphone access is needed for voice notes. Grant it in Muse Float settings.",
                Toast.LENGTH_LONG
            ).show()
            closeCapture()
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // The toast already explained where to go.
            }
            return
        }
        startRecording()
    }

    private fun startRecording() {
        // Timestamped name: a sent note's file is NOT deleted (the
        // receiving app may still be reading it), so takes never collide.
        val file = File(cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setMaxDuration(MAX_RECORD_MS)
            rec.setOnInfoListener { _, what, _ ->
                // At the cap the note auto-sends, same as tapping Send.
                // Nothing leaves the phone yet: the chooser still asks
                // the user to pick the receiving app.
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    finishRecording(send = true)
                }
            }
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) {}
            file.delete()
            Toast.makeText(this, "Could not start recording.", Toast.LENGTH_SHORT).show()
            return
        }
        recorder = rec
        recordingFile = file
        recordStartMs = SystemClock.elapsedRealtime()
        enterRecordMode()
    }

    /** Swaps the card from text layout to recording layout, dot pulsing. */
    private fun enterRecordMode() {
        captureEdit?.let { edit ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        }
        captureTextBox?.visibility = View.GONE
        captureRecordBox?.visibility = View.VISIBLE
        recordTimeText?.text = "0:00"
        recordPulse?.cancel()
        recordPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                recordDot?.let { dot ->
                    val scale = 1f + 0.35f * f
                    dot.scaleX = scale
                    dot.scaleY = scale
                    dot.alpha = 1f - 0.45f * f
                }
            }
            start()
        }
        handler.post(recordTick)
    }

    private fun exitRecordMode() {
        handler.removeCallbacks(recordTick)
        recordPulse?.cancel()
        recordPulse = null
        recordDot?.let { dot ->
            dot.scaleX = 1f
            dot.scaleY = 1f
            dot.alpha = 1f
        }
        captureRecordBox?.visibility = View.GONE
        captureTextBox?.visibility = View.VISIBLE
    }

    /**
     * Stops the recorder. send=true shares the take and closes the card;
     * send=false deletes it and (unless the card itself is closing)
     * returns the card to text mode with the keyboard back.
     */
    private fun finishRecording(send: Boolean, refocus: Boolean = true) {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (_: Exception) {
            // Stopping too early can throw; the take is unusable anyway
            // and the file handling below covers it.
        }
        try { rec.release() } catch (_: Exception) {}
        exitRecordMode()
        val file = recordingFile
        recordingFile = null
        if (send && file != null && file.isFile && file.length() > 0) {
            shareAudio(file)
            closeCapture()
        } else {
            file?.delete()
            if (!send && refocus) {
                captureEdit?.let { edit ->
                    edit.requestFocus()
                    val imm =
                        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    /** Shares a recorded note, mirroring shareText for the audio path. */
    private fun shareAudio(file: File) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, VoiceNoteProvider.uriFor(file))
                // Temporary read access to this one file for whichever
                // app the user picks in the chooser.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Send to Muse")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, "No app accepted the voice note.", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Idle fade and soft pulse
    // ------------------------------------------------------------------
    private val fadeRunnable = Runnable {
        // The idle opacity is read fresh, so slider changes apply live.
        bubble?.animate()?.alpha(Prefs.idleAlpha(this))?.setDuration(250)?.start()
    }

    private fun wakeBubble() {
        handler.removeCallbacks(fadeRunnable)
        bubble?.let { view ->
            view.animate().cancel()
            view.alpha = 1f
        }
        // Battery pass (v1.5): also re-check the day/night face on every
        // interaction. The boundary callback in scheduleFaceCheck is the
        // primary swap mechanism; this costs nothing and covers a
        // boundary that passed while the device was in deep sleep (a
        // main-looper callback cannot fire until the device wakes, and
        // the first touch after that now corrects the face immediately).
        applyFace()
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(fadeRunnable)
        if (bubble != null) handler.postDelayed(fadeRunnable, IDLE_FADE_MS)
    }

    /**
     * One soft breathing pulse: three gentle swells that decay away over
     * about two seconds, then the bubble sits still again. Runs after a
     * tap and after a drag settles; never loops on its own.
     */
    private fun pulse() {
        val view = bubble ?: return
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            addUpdateListener { animator ->
                val p = animator.animatedValue as Float
                val scale = 1f + (0.08f * sin(p * Math.PI * 3).toFloat() * (1f - p * 0.5f))
                view.scaleX = scale
                view.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }

                override fun onAnimationCancel(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------
    // Tap action: simply reopen Muse
    // ------------------------------------------------------------------
    private fun openTarget() {
        try {
            if (Prefs.effectiveTarget(this) == Prefs.TARGET_APP) {
                val pkg = Prefs.findMusePackage(this)
                val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    return
                }
                // App vanished since it was chosen; fall through to the web.
            }
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.WEB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Never let a failed launch kill the bubble service.
        }
    }
}
