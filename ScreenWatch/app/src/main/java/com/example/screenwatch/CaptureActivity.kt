package com.example.screenwatch

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class CaptureActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val i = Intent(this, ShareService::class.java)
        if (res == RESULT_OK && data != null) {
            i.action = ShareService.ACTION_START_CAPTURE
            i.putExtra("code", res)
            i.putExtra("data", data)
        } else {
            i.action = ShareService.ACTION_CANCEL
        }
        startService(i)
        finish()
    }
}
