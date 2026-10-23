package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.FileCopy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

class FileCopyTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun copy(source: java.io.File, skip: Long = 0L): ByteArray {
        val target = folder.newFile()
        val copied = FileInputStream(source).use { input ->
            input.skip(skip)
            FileOutputStream(target).use { output -> FileCopy.copy(input, output) }
        }
        assertEquals(target.length(), copied)
        return target.readBytes()
    }

    @Test
    fun aRawSizedFileIsCopiedByteForByte() {
        val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)
        val source = folder.newFile().apply { writeBytes(bytes) }

        assertArrayEquals(bytes, copy(source))
    }

    @Test
    fun copyingStartsWhereTheInputStreamAlreadyIs() {
        val bytes = Random(3).nextBytes(100_000)
        val source = folder.newFile().apply { writeBytes(bytes) }

        assertArrayEquals(bytes.copyOfRange(1_000, bytes.size), copy(source, skip = 1_000))
    }

    @Test
    fun anEmptyFileCopiesNothing() {
        assertEquals(0, copy(folder.newFile()).size)
    }
}
