package dev.vynkor.agent

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }
        binding.versionText.text = getString(R.string.about_version_fmt, BuildConfig.VERSION_NAME)
        binding.aboutBody.text = getString(
            R.string.about_body,
            BuildConfig.VERSION_NAME,
        )
    }

    private lateinit var binding: ActivityAboutBinding
}
