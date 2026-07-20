package com.nas.naswebdav

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialShareManifestTest {

    @Test
    fun manifestRegistersAHiddenTextShareTarget() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first(File::isFile).readText()

        val activityBlock = Regex(
            "<activity[^>]*android:name=\"\\.SocialShareActivity\"[\\s\\S]*?</activity>"
        ).find(manifest)?.value.orEmpty()

        assertTrue(activityBlock.contains("android.intent.action.SEND"))
        assertTrue(activityBlock.contains("android:mimeType=\"text/plain\""))
        assertTrue(activityBlock.contains("android:noHistory=\"true\""))
        assertTrue(activityBlock.contains("android:excludeFromRecents=\"true\""))
    }
}
