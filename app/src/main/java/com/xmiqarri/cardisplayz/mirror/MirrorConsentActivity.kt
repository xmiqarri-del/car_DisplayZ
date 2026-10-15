package com.xmiqarri.cardisplayz.mirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent screen that asks the user to allow screen capture (Android requires it every time),
 * then starts MirrorService with the answer.
 */
class MirrorConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val service = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
                .putExtra(MirrorService.EXTRA_DISPLAY_ID, intent.getIntExtra(MirrorService.EXTRA_DISPLAY_ID, -1))
            startForegroundService(service)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
}
