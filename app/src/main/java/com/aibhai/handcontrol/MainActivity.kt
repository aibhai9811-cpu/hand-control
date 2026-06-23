package com.aibhai.handcontrol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

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
        root.addView(body("Two one-time permissions are needed for full control:"))

        val accessibilityBtn = Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accessibilityBtn)
        root.addView(body("Find \"Hand Control\" in the list and turn it on. This lets gestures trigger Back / Home / Recent apps."))

        val brightnessBtn = Button(this).apply {
            text = "2. Allow Modify System Settings"
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
        root.addView(body("✌️ Peace sign → Recent apps"))
        root.addView(body("👍 Thumbs up → Volume up"))
        root.addView(body("👎 Thumbs down → Volume down"))
        root.addView(body("☝️ Point up → Brightness up"))
        root.addView(body("🤟 I-love-you sign → Mute / unmute"))

        root.addView(body("\nOnce both permissions are on, gestures work even while using other apps. Hold a gesture steady for about a second."))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
