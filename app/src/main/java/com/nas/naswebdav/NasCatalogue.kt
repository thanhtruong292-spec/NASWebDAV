package com.nas.naswebdav

import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.Reader
import java.net.URI
import java.net.URLEncoder

internal class IncompleteNasCatalogueException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal data class NasCatalogueSnapshot(val generation: Long, val total: Int, val updatedAt: Long, val revision: Long? = null)

internal fun catalogueFileUrl(base: String, rawPath: String): String {
    require(rawPath.startsWith('/') && rawPath.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
    val origin = URI(base)
    require(origin.userInfo == null)
    val encoded = rawPath.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    val result = "${origin.scheme}://${origin.rawAuthority}$encoded"
    require(relativeDuplicatePath(base, result) != null) { "Catalogue path escaped NAS root" }
    return result
}

/** A partially received mirror is usable as cache, never as cleanup authority. */
internal suspend fun readNasCatalogue(
    input: Reader, base: String, account: String,
    onBatch: suspend (List<CachedFile>, received: Int, total: Int) -> Unit
): NasCatalogueSnapshot {
    try {
        require(account.isNotBlank())
        var protocol = 0
        var ready = false
        var truncated = true
        var generation = 0L
        var updatedAt = 0L
        var source = ""
        var revision: Long? = null
        var total = -1
        var received = 0
        var sawFiles = false
        val batch = ArrayList<CachedFile>(2000)
        JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                when (reader.nextName()) {
                    "catalogue_protocol" -> protocol = reader.nextInt()
                    "index_ready" -> ready = reader.nextBoolean()
                    "truncated" -> truncated = reader.nextBoolean()
                    "index_generation" -> generation = reader.nextLong()
                    "catalogue_revision" -> {
                        revision = if (reader.peek() == JsonToken.NULL) {reader.nextNull();null} else reader.nextLong().also {require(it >= 0)}
                    }
                    "source" -> source = reader.nextString()
                    "catalogue_updated_at" -> {
                        val value = reader.nextDouble()
                        require(value.isFinite() && value > 0 && value < Long.MAX_VALUE / 1000.0)
                        updatedAt = (value * 1000).toLong()
                    }
                    "total" -> {
                        val value = reader.nextLong()
                        require(value in 0..Int.MAX_VALUE.toLong())
                        total = value.toInt()
                    }
                    "files" -> {
                        require(!sawFiles && protocol == 1 && source == "file_index" && ready && !truncated && generation > 0 && updatedAt > 0 && total >= 0) { "NAS catalogue is not complete" }
                        sawFiles = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            currentCoroutineContext().ensureActive()
                            var name = ""
                            var path = ""
                            var size = -1L
                            var mtime = 0L
                            var mime = "application/octet-stream"
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> name = reader.nextString()
                                    "path" -> path = reader.nextString()
                                    "size" -> size = reader.nextLong()
                                    "mtime" -> mtime = reader.nextLong()
                                    "mime" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else mime = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            require(name.isNotEmpty() && size >= 0 && received < total)
                            val url = catalogueFileUrl(base, path)
                            batch.add(CachedFile(url, name, false, mime, url.substringBeforeLast('/') + "/", size, mtime, accountKey = account))
                            received++
                            if (batch.size == 2000) {
                                onBatch(batch.toList(), received, total)
                                batch.clear()
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && sawFiles && protocol == 1 && source == "file_index" && ready && !truncated && generation > 0 && updatedAt > 0 && received == total)
        }
        if (batch.isNotEmpty()) onBatch(batch.toList(), received, total)
        return NasCatalogueSnapshot(generation,received,updatedAt,revision)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw IncompleteNasCatalogueException("Danh mục NAS chưa đầy đủ; giữ dữ liệu và chờ thử lại", error)
    }
}

internal suspend fun finishNasCatalogueMirror(db: AppDatabase, base: String, account: String, startedAt: Long, snapshot: NasCatalogueSnapshot) = db.withTransaction {
    val root = base.trimEnd('/') + "/"
    // Retain recent local discoveries while a NAS snapshot can be 60 seconds old.
    db.fileDao().pruneCompletedCatalogue(root, root.dropLast(1) + "0", startedAt - 60000, account)
    db.catalogueReceiptDao().save(CatalogueReceipt(account,base,snapshot.generation,snapshot.total,snapshot.updatedAt,System.currentTimeMillis(),snapshot.revision))
}
