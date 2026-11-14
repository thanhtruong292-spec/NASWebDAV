package com.nas.naswebdav

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Transparent share target that queues a NAS download without opening MainActivity. */
class SocialShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) {
            finishAndRemoveTask()
            return
        }

        // Facebook and other apps may place the URL in different extras depending
        // on share mode: EXTRA_TEXT (plain caption), EXTRA_STREAM (media URI), or
        // EXTRA_HTML_TEXT (rich text with <a href>). We scan all three and take
        // the first supported URL we find.
        // CC-005 P2: SEND_MULTIPLE — EXTRA_TEXT có thể là ArrayList<String> (nhiều URL một lần).
        val candidates = mutableListOf<String>()
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.os.Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
            }?.forEach { p -> p.toString().takeIf { it.isNotBlank() }?.let { candidates += it } }
            intent?.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach { candidates += it }
        }
        intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { candidates += it }
        intent?.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString()?.let { candidates += it }
        // EXTRA_STREAM: some apps (Facebook) put a content:// URI here that
        // may redirect to the actual share URL. Try to read its first line as
        // text; if it contains a supported URL, we can extract it. If it is an
        // https link itself, the parser will handle it.
        @Suppress("DEPRECATION")
        intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { streamUri ->
            // Direct string conversion (works if URI is https itself)
            streamUri.toString()?.let { if (it.isNotBlank()) candidates += it }
            // Try content resolver — Facebook content:// URIs sometimes redirect
            // to the share URL; read as text line and scan for a URL.
            runCatching {
                val mimeType = contentResolver.getType(streamUri)
                if (mimeType?.startsWith("text/") == true) {
                    contentResolver.openInputStream(streamUri)?.bufferedReader()?.use { reader ->
                        reader.lineSequence().take(5).forEach { line ->
                            SocialShareParser.extractSupportedUrl(line)?.let { found ->
                                candidates += found
                            }
                        }
                    }
                }
            }
        }

        val socialUrls = candidates.flatMap { text ->
            SocialShareParser.extractAllSupportedUrls(text)
        }.distinct()
        if (socialUrls.isEmpty()) {
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

        socialUrls.forEach { SocialDownloadWorker.enqueue(applicationContext, it) }
        showToast(
            if (socialUrls.size > 1) "Đã xếp hàng ${socialUrls.size} link"
            else getString(R.string.social_share_queued)
        )
        finishAndRemoveTask()
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
