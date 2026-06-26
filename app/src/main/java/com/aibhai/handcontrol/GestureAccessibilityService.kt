package com.aibhai.handcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.Executors

class GestureAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gestureRecognizer: GestureRecognizer? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var previewImageView: ImageView? = null
    private var statusTextView: TextView? = null

    private var lastGesture = "None"
    private var lastGestureStart = 0L
    private var fired = false
    private var savedVolume = -1

    private var swipeBaselineX: Float? = null
    private var swipeBaselineY: Float? = null
    private var swipeBaselineTime: Long = 0L
    private var lastSwipeTime: Long = 0L

    private var airMouseActive = false
    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var wasPinching = false
    private var pinchStartTime: Long? = null
    private val pinchActivationTimestamps = mutableListOf<Long>()

    companion object {
        private const val TAG = "GestureControl"
        private const val HOLD_MS = 700L
        private const val CONFIDENCE_MIN = 0.6f
        private const val SWIPE_MIN_DELTA = 0.16f
        private const val SWIPE_MAX_WINDOW_MS = 400L
        private const val SWIPE_COOLDOWN_MS = 1200L
        private const val TARGET_APP_PACKAGE = "com.whatsapp"
        private const val PINCH_DISTANCE_THRESHOLD = 0.06f
        private const val PINCH_EXIT_HOLD_MS = 1000L
        private const val DOUBLE_PINCH_WINDOW_MS = 800L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            setupOverlay()
            setupGestureRecognizer()
            startCamera()
            toast("Hand Control active")
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected crashed", t)
            toast("Startup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setupOverlay() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val density = resources.displayMetrics.density

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xCC000000.toInt())
                setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            }

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((130 * density).toInt(), (170 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                scaleX = -1f
            }
            container.addView(preview)
            previewImageView = preview

            val status = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                text = "Starting…"
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            container.addView(status)
            statusTextView = status

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = (8 * density).toInt()
            params.y = (100 * density).toInt()

            wm.addView(container, params)
            overlayView = container

            val cursorSize = (28 * density).toInt()
            val cursor = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFF5252.toInt())
                    setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
                }
                visibility = View.GONE
            }
            val cParams = WindowManager.LayoutParams(
                cursorSize, cursorSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            cParams.gravity = Gravity.TOP or Gravity.START
            wm.addView(cursor, cParams)
            cursorView = cursor
            cursorParams = cParams
        } catch (t: Throwable) {
            Log.e(TAG, "Overlay setup failed", t)
            toast("Overlay failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun updatePreview(bitmap: Bitmap) {
        mainHandler.post {
            previewImageView?.setImageBitmap(bitmap)
        }
    }

    private fun updateStatus(text: String) {
        mainHandler.post {
            statusTextView?.text = text
        }
    }

    private fun setupGestureRecognizer() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { e -> Log.e(TAG, "Gesture recognizer error: ${e.message}") }
                .build()

            gestureRecognizer = GestureRecognizer.createFromOptions(this, options)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set up gesture recognizer", t)
            toast("Gesture model failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera bind failed", t)
                toast("Camera failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val rawBitmap: Bitmap = imageProxy.toBitmap()
            val bitmap = rotateBitmap(rawBitmap, rotation)
            updatePreview(bitmap)
            val mpImage = BitmapImageBuilder(bitmap).build()
            gestureRecognizer?.recognizeAsync(mpImage, SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            Log.e(TAG, "Frame processing failed", t)
            updateStatus("Frame error: ${t.javaClass.simpleName}")
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun handleResult(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()

        val gestures = result.gestures()
        var detected = "None"
        var score = 0f
        if (gestures.isNotEmpty() && gestures[0].isNotEmpty()) {
            val top = gestures[0][0]
            detected = top.categoryName()
            score = top.score()
        }

        val label = if (detected != "None" && score > CONFIDENCE_MIN) detected else "None"

        val liveText = if (detected == "None") "No hand detected" else "$detected  ${(score * 100).toInt()}%"
        updateStatus(liveText)

        // Two-finger point (index + middle, ring/pinky curled) swipe — detected
        // directly from raw landmarks, independent of the built-in pose classifier.
        trackSwipe(result, now)
        handleAirMouse(result, now)

        if (label != lastGesture) {
            lastGesture = label
            lastGestureStart = now
            fired = false
        }

        if (label != "None" && now - lastGestureStart >= HOLD_MS && !fired) {
            fired = true
            performAction(label)
        }
    }

    private fun trackSwipe(result: GestureRecognizerResult, now: Long) {
        val landmarksList = result.landmarks()
        if (landmarksList.isEmpty() || landmarksList[0].isEmpty()) {
            swipeBaselineX = null
            swipeBaselineY = null
            return
        }
        val lm = landmarksList[0]

        // Index and middle extended, ring and pinky curled — two fingers held
        // together pointing, regardless of what the pose classifier calls it.
        val indexExtended = lm[8].y() < lm[6].y() - 0.03f
        val middleExtended = lm[12].y() < lm[10].y() - 0.03f
        val ringCurled = lm[16].y() > lm[14].y() - 0.01f
        val pinkyCurled = lm[20].y() > lm[18].y() - 0.01f
        val isTwoFingerPoint = indexExtended && middleExtended && ringCurled && pinkyCurled

        if (!isTwoFingerPoint) {
            swipeBaselineX = null
            swipeBaselineY = null
            return
        }

        // Track the fingertip midpoint itself, so the swipe follows finger
        // movement specifically rather than the whole hand/wrist.
        val x = (lm[8].x() + lm[12].x()) / 2f
        val y = (lm[8].y() + lm[12].y()) / 2f

        if (swipeBaselineX == null || swipeBaselineY == null) {
            swipeBaselineX = x
            swipeBaselineY = y
            swipeBaselineTime = now
            return
        }

        val elapsed = now - swipeBaselineTime
        val deltaX = x - swipeBaselineX!!
        val deltaY = y - swipeBaselineY!!
        val cooldownOver = now - lastSwipeTime > SWIPE_COOLDOWN_MS
        val absX = kotlin.math.abs(deltaX)
        val absY = kotlin.math.abs(deltaY)

        if (cooldownOver && (absX > SWIPE_MIN_DELTA || absY > SWIPE_MIN_DELTA)) {
            lastSwipeTime = now
            fired = true
            if (absX >= absY) {
                // Front camera buffer is unmirrored: the user's own right-hand
                // movement decreases raw x, even though the on-screen preview
                // (mirrored for natural selfie viewing) shows it moving right.
                if (deltaX < 0) performSwipe("RIGHT") else performSwipe("LEFT")
            } else {
                if (deltaY > 0) performSwipe("DOWN") else performSwipe("UP")
            }
            swipeBaselineX = x
            swipeBaselineY = y
            swipeBaselineTime = now
        } else if (elapsed > SWIPE_MAX_WINDOW_MS) {
            swipeBaselineX = x
            swipeBaselineY = y
            swipeBaselineTime = now
        }
    }

    private fun handleAirMouse(result: GestureRecognizerResult, now: Long) {
        val landmarksList = result.landmarks()
        if (landmarksList.isEmpty() || landmarksList[0].isEmpty()) {
            wasPinching = false
            pinchStartTime = null
            return
        }
        val lm = landmarksList[0]
        val indexTip = lm[8]
        val thumbTip = lm[4]

        val dx = thumbTip.x() - indexTip.x()
        val dy = thumbTip.y() - indexTip.y()
        val pinchDist = kotlin.math.sqrt(dx * dx + dy * dy)
        val isPinching = pinchDist < PINCH_DISTANCE_THRESHOLD
        val pinchJustStarted = isPinching && !wasPinching
        wasPinching = isPinching

        if (!airMouseActive) {
            if (pinchJustStarted) {
                pinchActivationTimestamps.add(now)
                pinchActivationTimestamps.removeAll { now - it > DOUBLE_PINCH_WINDOW_MS }
                if (pinchActivationTimestamps.size >= 2) {
                    pinchActivationTimestamps.clear()
                    fired = true
                    setAirMouseActive(true)
                }
            }
            return
        }

        // --- Air mouse active: cursor follows the index fingertip ---
        val metrics = resources.displayMetrics
        val screenX = (1f - indexTip.x()) * metrics.widthPixels
        val screenY = indexTip.y() * metrics.heightPixels
        moveCursor(screenX, screenY)

        if (isPinching) {
            if (pinchStartTime == null) pinchStartTime = now
            val held = now - (pinchStartTime ?: now)
            if (held >= PINCH_EXIT_HOLD_MS) {
                pinchStartTime = null
                setAirMouseActive(false)
            }
        } else {
            // A pinch that was released before reaching the exit threshold counts as a click.
            if (pinchStartTime != null) {
                dispatchTap(screenX, screenY)
            }
            pinchStartTime = null
        }
    }

    private fun setAirMouseActive(active: Boolean) {
        airMouseActive = active
        mainHandler.post {
            cursorView?.visibility = if (active) View.VISIBLE else View.GONE
        }
        toast(if (active) "🖱️ Air mouse ON" else "🖱️ Air mouse OFF")
    }

    private fun moveCursor(screenX: Float, screenY: Float) {
        mainHandler.post {
            val params = cursorParams ?: return@post
            val view = cursorView ?: return@post
            params.x = screenX.toInt() - view.width / 2
            params.y = screenY.toInt() - view.height / 2
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (t: Throwable) {
                Log.e(TAG, "Cursor move failed", t)
            }
        }
    }

    private fun dispatchTap(x: Float, y: Float) {
        try {
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Dispatch tap failed", t)
        }
    }

    private fun performSwipe(direction: String) {
        dispatchSwipe(direction)
        val arrow = when (direction) {
            "UP" -> "⬆️"
            "DOWN" -> "⬇️"
            "LEFT" -> "⬅️"
            else -> "➡️"
        }
        toast("$arrow Two-finger swipe $direction")
    }

    private fun dispatchSwipe(direction: String) {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            val centerX = width / 2f
            val centerY = height / 2f

            val startX: Float
            val startY: Float
            val endX: Float
            val endY: Float
            when (direction) {
                "LEFT" -> { startX = width * 0.8f; startY = centerY; endX = width * 0.2f; endY = centerY }
                "RIGHT" -> { startX = width * 0.2f; startY = centerY; endX = width * 0.8f; endY = centerY }
                "UP" -> { startX = centerX; startY = height * 0.8f; endX = centerX; endY = height * 0.2f }
                else -> { startX = centerX; startY = height * 0.2f; endX = centerX; endY = height * 0.8f }
            }

            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val stroke = GestureDescription.StrokeDescription(path, 0, 250)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Dispatch swipe failed", t)
            toast("Swipe dispatch failed: ${t.javaClass.simpleName}")
        }
    }

    private fun performAction(gesture: String) {
        when (gesture) {
            "Open_Palm" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                toast("🖐️ Home")
            }
            "Closed_Fist" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                toast("✊ Back")
            }
            "Victory" -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                toast("✌️ Recent apps")
            }
            "Thumb_Up" -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                toast("👍 Volume up")
            }
            "Thumb_Down" -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                toast("👎 Volume down")
            }
            "Pointing_Up" -> {
                openApp(TARGET_APP_PACKAGE)
                toast("☝️ Opening app")
            }
            "ILoveYou" -> {
                toggleMute()
                toast("🤟 Mute toggled")
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun toggleMute() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current > 0) {
            savedVolume = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        } else {
            val restore = if (savedVolume > 0) savedVolume else
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun openApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                toast("App not installed: $packageName")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Open app failed", t)
            toast("Couldn't open app: ${t.javaClass.simpleName}")
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }

    override fun onInterrupt() { /* not needed */ }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
        gestureRecognizer?.close()
        overlayView?.let { view ->
            try { windowManager?.removeView(view) } catch (t: Throwable) { Log.e(TAG, "Overlay removal failed", t) }
        }
        cursorView?.let { view ->
            try { windowManager?.removeView(view) } catch (t: Throwable) { Log.e(TAG, "Cursor removal failed", t) }
        }
    }
}
