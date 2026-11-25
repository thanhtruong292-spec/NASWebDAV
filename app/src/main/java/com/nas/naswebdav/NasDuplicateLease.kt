package com.nas.naswebdav

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** NAS grants the maintenance slot; Android idle alone never grants permission. */
internal class NasDuplicateLease private constructor(
    private val auth: WebDavManager.AuthState,
    private val owner: String,
    private val token: String,
    val revision: Long
) {
    private val mutex=Mutex()
    @Volatile private var failed: Exception?=null
    @Volatile private var expires=SystemClock.elapsedRealtime()+240000
    private var cursor=""
    private var completed=0
    private var total=0
    private var heartbeat: Job?=null
    private var released=false

    fun start(scope: CoroutineScope) {
        heartbeat=scope.launch(Dispatchers.IO) {
            while(isActive) {
                delay(30000)
                try {checkpoint()}
                catch(error: CancellationException) {throw error}
                catch(error: Exception) {failed=error;break}
            }
        }
    }

    fun ensureValid() {
        failed?.let {throw IOException("Mất quyền tác vụ NAS; dừng quét",it)}
        if(released || SystemClock.elapsedRealtime()>=expires) throw IOException("Quyền tác vụ NAS đã hết hạn")
    }

    suspend fun progress(nextCursor: String, received: Int, expected: Int) = mutex.withLock {
        ensureValid()
        require(received in 0..expected)
        cursor=nextCursor;completed=received;total=expected
        sendCheckpoint("running")
    }

    suspend fun checkpoint(nextCursor: String?=null) = mutex.withLock {
        ensureValid()
        if(nextCursor!=null) cursor=nextCursor
        sendCheckpoint("running")
    }

    suspend fun complete() {
        heartbeat?.cancelAndJoin()
        mutex.withLock {
            ensureValid()
            require(completed==total)
            sendCheckpoint("complete")
            released=true
        }
    }

    fun scanPermit(): JSONObject {
        ensureValid()
        return JSONObject().put("name", "duplicates").put("owner", owner).put("token", token)
    }

    suspend fun cleanupPermit(): JSONObject {
        checkpoint()
        return scanPermit()
    }

    suspend fun retryAndRelease() = withContext(NonCancellable+Dispatchers.IO) {
        heartbeat?.cancelAndJoin()
        if(!released) {
            runCatching {mutex.withLock {sendCheckpoint("retry");released=true}}
        }
    }

    private fun sendCheckpoint(state: String) {
        try {
            val result=post(auth,"checkpoint",JSONObject().put("name","duplicates").put("owner",owner)
                .put("token",token).put("cursor",cursor).put("completed",completed).put("total",total).put("state",state))
            if(!result.optBoolean("accepted",false)) throw IOException("NAS không nhận tiến độ tác vụ")
            expires=SystemClock.elapsedRealtime()+240000
        } catch(error: Exception) {failed=error;throw error}
    }

    companion object {
        suspend fun acquire(auth: WebDavManager.AuthState): NasDuplicateLease = withContext(Dispatchers.IO) {
            post(auth,"request",JSONObject().put("name","duplicates"))
            val id="android-"+UUID.randomUUID().toString()
            val result=post(auth,"claim",JSONObject().put("name","duplicates").put("owner",id))
            val owner=result.getString("owner")
            val token=result.getString("token")
            val revision=result.getLong("catalogue_revision")
            if(owner!="client:"+id || token.isBlank() || revision<0) throw IOException("Quyền tác vụ NAS không hợp lệ")
            NasDuplicateLease(auth,owner,token,revision)
        }

        private fun post(auth: WebDavManager.AuthState, route: String, body: JSONObject): JSONObject {
            val request=Request.Builder().url(auth.baseUrl.toApiBaseUrl()+"/api/work/"+route)
                .tag(WebDavManager.AuthState::class.java,auth).header("Authorization",auth.authHeader)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call=NasApplication.instance.fastApiClient.newCall(request)
            call.timeout().timeout(10,TimeUnit.SECONDS)
            return call.execute().use {response ->
                if(!response.isSuccessful) throw IOException("Tác vụ NAS chưa sẵn sàng (HTTP ${response.code})")
                JSONObject(response.body?.string() ?: throw IOException("Thiếu phản hồi tác vụ NAS"))
            }
        }
    }
}
