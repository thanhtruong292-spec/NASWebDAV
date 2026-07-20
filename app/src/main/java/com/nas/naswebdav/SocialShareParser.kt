package com.nas.naswebdav

import java.net.URI

internal object SocialShareParser {
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val trailingSharePunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun extractSupportedUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null

        return urlPattern.findAll(sharedText).mapNotNull { match ->
            val candidate = match.value.trimEnd { it in trailingSharePunctuation }
            candidate.takeIf(::isSupportedContentUrl)
        }.firstOrNull()
    }

    private fun hostMatches(host: String, baseDomain: String): Boolean =
        host == baseDomain || host.endsWith(".$baseDomain")

    private fun isSupportedContentUrl(candidate: String): Boolean {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false

        val host = uri.host?.trimEnd('.')?.lowercase() ?: return false
        if (!host.all { it.code in 0..127 }) return false

        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val pathLower = path.lowercase()
        val queryLower = uri.rawQuery.orEmpty().lowercase()

        if (host == "youtu.be") {
            return path.trim('/').isNotEmpty() && '/' !in path.trim('/')
        }
        if (hostMatches(host, "youtube.com")) {
            return (pathLower == "/watch" && queryHasValue(queryLower, "v")) ||
                pathLower.startsWith("/shorts/") ||
                pathLower.startsWith("/live/") ||
                pathLower.startsWith("/embed/")
        }

        if (hostMatches(host, "tiktok.com")) {
            if (host == "vm.tiktok.com" || host == "vt.tiktok.com") {
                return path.trim('/').isNotEmpty()
            }
            return pathLower.startsWith("/@") && "/video/" in pathLower
        }

        if (hostMatches(host, "instagram.com")) {
            return listOf("/reel/", "/p/", "/tv/", "/stories/")
                .any(pathLower::startsWith)
        }

        if (host == "fb.watch") return path.trim('/').isNotEmpty()
        if (hostMatches(host, "facebook.com")) {
            return "/videos/" in pathLower ||
                listOf(
                    "/reel/",
                    "/stories/",
                    "/s/",
                    "/share/r/",
                    "/share/v/"
                ).any(pathLower::startsWith) ||
                ((pathLower == "/watch" || pathLower == "/watch/") &&
                    queryHasValue(queryLower, "v")) ||
                (pathLower == "/photo.php" && queryHasValue(queryLower, "v"))
        }

        return false
    }

    private fun queryHasValue(query: String, key: String): Boolean =
        query.split('&').any { part ->
            val separator = part.indexOf('=')
            separator > 0 && part.substring(0, separator) == key &&
                part.substring(separator + 1).isNotBlank()
        }
}
