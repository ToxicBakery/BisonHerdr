package com.toxicbakery.app.bison.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.toxicbakery.app.bison.activity.MainActivity.Companion.EXTRA_FAKE_SERVER

/**
 * Debug-only launcher entry ("BisonHerdr Demo") that starts [MainActivity] with
 * [EXTRA_FAKE_SERVER] enabled so the simulated multi-machine Herdr fleet is seeded automatically.
 */
class DemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_FAKE_SERVER, true)
        )
        finish()
    }
}
