package com.nas.naswebdav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Transparent share target that queues a NAS download without opening MainActivity. */
class SocialShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_SEND) {
            finishAndRemoveTask()
            return
        }

        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val socialUrl = SocialShareParser.extractSupportedUrl(sharedText)
        if (socialUrl == null) {
            showToast(getString(R.string.social_share_invalid))
            finishAndRemoveTask()
            return
        }

        val hasSavedConnection = runCatching {
            SecurePrefsHelper.getUrl(applicationContext).isNotBlank()
        }.getOrDefault(false)
        if (!hasSavedConnection) {
            showToast(getString(R.string.social_share_login_required))
            finishAndRemoveTask()
            return
        }

        SocialDownloadWorker.enqueue(applicationContext, socialUrl)
        showToast(getString(R.string.social_share_queued))
        finishAndRemoveTask()
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
