package dev.vynkor.agent

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar

/**
 * R-20 (№34): pair with enableEdgeToEdge() — draw behind the system bars and
 * pad the content host with the bar insets. The IME inset is folded in so
 * bottom-anchored input (chat composer) rises above the keyboard.
 */
fun AppCompatActivity.applyInsetPadding() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        insets
    }
}

/** R-19: error/feedback surface for activity-scoped messages. */
fun AppCompatActivity.snack(messageRes: Int, duration: Int = Snackbar.LENGTH_SHORT) {
    Snackbar.make(findViewById(android.R.id.content), messageRes, duration).show()
}
