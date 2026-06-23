package com.aibhai.handcontrol

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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

    private var lastGesture = "None"
    private var lastGestureStart = 0L
    private var fired = false
    private var savedVolume = -1

    companion object {
        private const val TAG = "GestureControl"
        private const val HOLD_MS = 700L
        private const val CONFIDENCE_MIN = 0.6f
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            setupGestureRecognizer()
            startCamera()
            toast("Hand Control active")
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected crashed", t)
            toast("Startup failed: ${t.javaClass.simpleName}: ${t.message}")
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
            val bitmap: Bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            gestureRecognizer?.recognizeAsync(mpImage, SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            Log.e(TAG, "Frame processing failed", t)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: GestureRecognizerResult) {
        val gestures = result.gestures()
        var detected = "None"
        var score = 0f
        if (gestures.isNotEmpty() && gestures[0].isNotEmpty()) {
            val top = gestures[0][0]
            detected = top.categoryName()
            score = top.score()
        }

        val label = if (detected != "None" && score > CONFIDENCE_MIN) detected else "None"
        val now = SystemClock.uptimeMillis()

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
    }
}
