package com.nas.naswebdav

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavErrorTest {

    @Test
    fun extractApiError_returnsErrorMessageFromValidJson() {
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("{\"error\": \"Tập tin không tồn tại\"}".toResponseBody(null))
            .build()

        val err = WebDavManager.extractApiError(response)
        assertEquals("Tập tin không tồn tại", err)
    }

    @Test
    fun extractApiError_returnsNullWhenBodyIsEmpty() {
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(500)
            .message("Internal Server Error")
            .body("".toResponseBody(null))
            .build()

        val err = WebDavManager.extractApiError(response)
        assertNull(err)
    }
}
