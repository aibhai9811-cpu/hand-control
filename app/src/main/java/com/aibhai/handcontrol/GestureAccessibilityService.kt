package com.aibhai.handcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import kotlin.math.abs

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

    // Swipe Tracking Variables
    private var isTrackingSwipe = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var lastSwipeTime = 0L

    companion object {
        private const val TAG = "GestureControl"
        private const val HOLD_MS = 700L
        private const val CONFIDENCE_MIN = 0.6f
        private const val SWIPE_THRESHOLD = 0.12f // Hand must move 12% across frame
        private const val SWIPE_COOLDOWN_MS = 800L // Pause between swipes
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
        val gestures = result.gestures()
        val landmarks = result.landmarks()
        var detected = "None"
        var score = 0f

        if (gestures.isNotEmpty() && gestures[0].isNotEmpty()) {
            val top = gestures[0][0]
            detected = top.categoryName()
            score = top.score()
        }

        val label = if (detected != "None" && score > CONFIDENCE_MIN) detected else "None"
        val now = SystemClock.uptimeMillis()

        val liveText = if (detected == "None") "No hand detected" else "$detected  ${(score * 100).toInt()}%"
        updateStatus(liveText)

        // Reset static holding logic if gesture changes
        if (label != lastGesture) {
            lastGesture = label
            lastGestureStart = now
            fired = false
            isTrackingSwipe = false
        }

        // --- SWIPE TRACKING FOR 👆 + 👆 (Victory Gesture) ---
        if (label == "Victory" && landmarks.isNotEmpty() && landmarks[0].isNotEmpty()) {
            val wrist = landmarks[0][0] // Landmark 0 (wrist) is the most stable anchor point
            val x = wrist.x()
            val y = wrist.y()

            if (!isTrackingSwipe) {
                isTrackingSwipe = true
                swipeStartX = x
                swipeStartY = y
                swipeStartTime = now
            } else if (now - lastSwipeTime > SWIPE_COOLDOWN_MS) {
                val dx = x - swipeStartX
                val dy = y - swipeStartY

                if (now - swipeStartTime > 1000) {
                    // Reset anchor if hand hasn't moved for 1 second to avoid slow drifting
                    swipeStartX = x
                    swipeStartY = y
                    swipeStartTime = now
                } else if (abs(dx) > SWIPE_THRESHOLD || abs(dy) > SWIPE_THRESHOLD) {
                    if (abs(dx) > abs(dy)) {
                        // Horizontal Movement
                        if (dx > 0) {
                            performScreenSwipe("Left") // Physical hand moved Left
                        } else {
                            performScreenSwipe("Right") // Physical hand moved Right
                        }
                    } else {
                        // Vertical Movement
                        if (dy < 0) {
                            performScreenSwipe("Up") // Physical hand moved Up
                        } else {
                            performScreenSwipe("Down") // Physical hand moved Down
                        }
                    }
                    lastSwipeTime = now
                    isTrackingSwipe = false
                    fired = true // Pre-empt standard action to prevent conflicts
                }
            }
        } 
        // --- STANDARD STATIC GESTURES (Holding for HOLD_MS) ---
        else if (label != "None" && label != "Victory" && now - lastGestureStart >= HOLD_MS && !fired) {
            fired = true
            performAction(label)
        }
    }

    private fun performScreenSwipe(direction: String) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val cx = width / 2f
        val cy = height / 2f
        val swipeXDistance = width * 0.35f
        val swipeYDistance = height * 0.35f

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction) {
            "Left" -> {
                startX = cx + swipeXDistance
                startY = cy
                endX = cx - swipeXDistance
                endY = cy
                toast("👈 Swiping Left")
            }
            "Right" -> {
                startX = cx - swipeXDistance
                startY = cy
                endX = cx + swipeXDistance
                endY = cy
                toast("👉 Swiping Right")
            }
            "Up" -> { // Swipe down on screen to scroll the content up
                startX = cx
                startY = cy - swipeYDistance
                endX = cx
                endY = cy + swipeYDistance
                toast("👆 Scrolling Up")
            }
            "Down" -> { // Swipe up on screen to scroll the content down
                startX = cx
                startY = cy + swipeYDistance
                endX = cx
                endY = cy - swipeYDistance
                toast("👇 Scrolling Down")
            }
            else -> return
        }

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, 250) // 250ms swipe speed
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
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
            "Thumb_Up" -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                toast("👍 Volume up")
            }
            "Thumb_Down" -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                toast("👎 Volume down")
            }
            "Pointing_Up" -> {
                adjustBrightness()
                toast("☝️ Brightness up")
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

    private fun adjustBrightness() {
        if (!Settings.System.canWrite(this)) return
        val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (current + 25).coerceAtMost(255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
    }
}
