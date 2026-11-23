/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.api.core

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Reports download progress of a response body as it is read.
 *
 * This used to come from the MCEF (JCEF) library, which was dropped with the browser integration on
 * 2026-09-24; the marketplace and the deep-learning downloads still report progress through it. Same
 * contract as the MCEF class it replaces: [ProgressListener.update] is called after every read with
 * the running total, the body's declared length (`-1` when unknown) and whether the body is done.
 */
class OkHttpProgressInterceptor(private val progressListener: ProgressListener) : Interceptor {

    fun interface ProgressListener {
        fun update(bytesRead: Long, contentLength: Long, done: Boolean)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return response.newBuilder()
            .body(ProgressResponseBody(response.body, progressListener))
            .build()
    }

    private class ProgressResponseBody(
        private val responseBody: ResponseBody,
        private val progressListener: ProgressListener,
    ) : ResponseBody() {

        private var bufferedSource: BufferedSource? = null

        override fun contentType(): MediaType? = responseBody.contentType()

        override fun contentLength(): Long = responseBody.contentLength()

        override fun source(): BufferedSource =
            bufferedSource ?: object : ForwardingSource(responseBody.source()) {
                private var totalBytesRead = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    totalBytesRead += if (read != -1L) read else 0L
                    progressListener.update(totalBytesRead, responseBody.contentLength(), read == -1L)
                    return read
                }
            }.buffer().also { bufferedSource = it }
    }
}
