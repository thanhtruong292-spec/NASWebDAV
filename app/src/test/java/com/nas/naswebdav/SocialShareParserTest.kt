package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialShareParserTest {

    @Test
    fun extractsFacebookReelFromCaption() {
        assertEquals(
            "https://www.facebook.com/reel/123456789/",
            SocialShareParser.extractSupportedUrl(
                "Xem reel này nhé https://www.facebook.com/reel/123456789/"
            )
        )
    }

    @Test
    fun acceptsFacebookStoryVideoAndShortLinks() {
        val urls = listOf(
            "https://www.facebook.com/stories/123456789/",
            "https://www.facebook.com/user/videos/123456789/",
            "https://www.facebook.com/watch/?v=123456789",
            "https://www.facebook.com/share/r/AbCdEf123/",
            "https://fb.watch/abc123xyz/"
        )

        urls.forEach { url ->
            assertEquals(url, SocialShareParser.extractSupportedUrl(url))
        }
    }

    @Test
    fun supportsOtherServerPlatforms() {
        val urls = listOf(
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/abc123",
            "https://www.tiktok.com/@creator/video/123456",
            "https://www.instagram.com/reel/ABC123/"
        )

        urls.forEach { url ->
            assertEquals(url, SocialShareParser.extractSupportedUrl("Chia sẻ: $url"))
        }
    }

    @Test
    fun stripsPunctuationAddedBySharingApp() {
        assertEquals(
            "https://www.facebook.com/reel/123456789/",
            SocialShareParser.extractSupportedUrl(
                "Video hay: https://www.facebook.com/reel/123456789/)."
            )
        )
    }

    @Test
    fun rejectsUnsupportedOrMissingText() {
        assertNull(SocialShareParser.extractSupportedUrl(null))
        assertNull(SocialShareParser.extractSupportedUrl(""))
        assertNull(SocialShareParser.extractSupportedUrl("không có đường dẫn"))
        assertNull(SocialShareParser.extractSupportedUrl("https://example.com/video.mp4"))
        assertNull(SocialShareParser.extractSupportedUrl("http://127.0.0.1/video.mp4"))
    }
}
