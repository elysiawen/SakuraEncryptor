package com.sakura.encryptor.core.alist

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * Request body that reports how many bytes have actually been handed to the
 * network while [delegate] is written.
 *
 * OkHttp calls [writeTo] once on the thread that sends the request, so the
 * counting sink sees the real byte stream. That is what lets the upload
 * progress bar advance during the upload instead of sitting at the value the
 * local encryption left it at.
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress(written, total)
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        // Without this the tail of the file would still be sitting in the buffer
        // when the multipart writer moves on to the next part.
        buffered.flush()
    }
}
