package com.aibhai.handcontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var cameraStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun title(text: String) = TextView(this).apply {
            this.text = text
            textSize = 22f
            setPadding(0, padding, 0, padding / 2)
        }

        fun body(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 0, 0, padding / 2)
        }

        root.addView(title("Hand Gesture Control"))
        root.addView(body("Three one-time permissions are needed for full control:"))

        val cameraBtn = Button(this).apply {
            text = "1. Grant Camera Permission"
            setOnClickListener { requestCameraPermission() }
        }
        root.addView(cameraBtn)
        cameraStatus = body(cameraPermissionStatusText())
        root.addView(cameraStatus)

        val accessibilityBtn = Button(this).apply {
            text = "2. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accessibilityBtn)
        root.addView(body("Find \"Hand Control\" in the list and turn it on. This lets gestures trigger Back / Home / Recent apps."))

        val brightnessBtn = Button(this).apply {
            text = "3. Allow Modify System Settings"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
        root.addView(brightnessBtn)
        root.addView(body("Needed so a gesture can raise screen brightness."))

        root.addView(title("Gestures"))
        root.addView(body("🖐️ Open palm → Home"))
        root.addView(body("✊ Closed fist → Back"))
        root.addView(body("✌️ Peace sign (held still) → Recent apps"))
        root.addView(body("✌️ Peace sign + move up/down/left/right → Swipe that direction"))
        root.addView(body("👍 Thumbs up → Volume up"))
        root.addView(body("👎 Thumbs down → Volume down"))
        root.addView(body("☝️ Point up → Open WhatsApp"))
        root.addView(body("🤟 I-love-you sign → Mute / unmute"))

        root.addView(body("\nDo all three above in order. After granting Camera, turn the Accessibility Service OFF then back ON once so it restarts with permission. Hold a gesture steady for about a second."))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        }
    }

    private fun cameraPermissionStatusText(): String {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return if (granted) "Camera: granted ✓" else "Camera: not granted yet"
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            cameraStatus.text = cameraPermissionStatusText()
        }
    }
}
