package com.barret.navbridge

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.barret.navbridge.databinding.ActivityAboutBinding

/**
 * What this app is, what it talks to, and what else has to be installed for it
 * to be any use.
 *
 * The last part is the reason this screen exists at all: NavBridge is useless
 * on its own. Without BRouter installed and its offline data downloaded, GO on
 * the dashboard just returns an error, and nothing anywhere else in the app
 * says so.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.about_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.tvVersion.text = AppInfo.versionLabel(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
