package com.yashikota.omaigenzo.data

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object FileCopy {
    private const val STREAM_BUFFER_BYTES = 256 * 1024

    /**
     * Copies everything from [input]'s current position to [output] and returns the byte count.
     *
     * Regular files go through FileChannel.transferTo (sendfile in the kernel), so a 25 MB RAW is
     * not pushed through an 8 KB Java buffer 3,000 times. Pipes from document providers report no
     * size and cannot seek; they, and anything transferTo leaves behind, are streamed instead.
     */
    fun copy(input: FileInputStream, output: FileOutputStream): Long {
        val source = input.channel
        val sink = output.channel
        var copied = 0L
        val size = try {
            source.size()
        } catch (_: IOException) {
            0L
        }
        if (size > 0L) {
            try {
                var position = source.position()
                while (position < size) {
                    val sent = source.transferTo(position, size - position, sink)
                    if (sent <= 0L) break
                    position += sent
                    copied += sent
                }
                source.position(position)
            } catch (_: IOException) {
                // Not seekable after all: fall through to streaming from wherever the stream is.
            }
        }
        // One byte first: after a complete transfer this is the only read, and no buffer is needed.
        val first = input.read()
        if (first < 0) return copied
        output.write(first)
        copied++
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
        }
        return copied
    }
}
