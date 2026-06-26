package com.portal.commons

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Best-effort async file logging: never throws, no-op until a file is set, writes off the caller's thread. */
class DebugLogTest {

    @After fun tearDown() {
        DebugLog.close() // also exercises the public teardown API between tests
        DebugLog.awaitIdle()
        DebugLog.maxFileBytes = DebugLog.DEFAULT_MAX_FILE_BYTES
    }

    /** Drain the async writer, then read — fails the test rather than racing if the worker stalls. */
    private fun File.readLinesIdle(): List<String> {
        assertTrue("writer did not drain in time", DebugLog.awaitIdle())
        return readLines()
    }

    @Test fun noOpWhenFileUnset() {
        DebugLog.file = null
        DebugLog.log("dropped") // must not throw
    }

    @Test fun appendsLinesWithTimestamps() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        DebugLog.log("first")
        DebugLog.log("second")

        val lines = f.readLinesIdle()
        assertEquals(2, lines.size)
        assertTrue("line should end with its message", lines[0].endsWith(" first"))
        assertTrue(lines[1].endsWith(" second"))
        // Each line is "<millis> <message>" — the prefix must be a number.
        val ts = lines[0].substringBefore(' ')
        assertTrue("prefix should be a timestamp", ts.toLongOrNull() != null)
    }

    @Test fun appendsRatherThanTruncates() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        f.writeText("pre-existing\n")
        DebugLog.file = f
        DebugLog.log("added")

        val lines = f.readLinesIdle()
        assertEquals(2, lines.size)
        assertEquals("pre-existing", lines[0])
        assertTrue(lines[1].endsWith(" added"))
    }

    @Test fun neverThrowsOnUnwritableTarget() {
        // A path whose parent does not exist → open would fail; logging must swallow it.
        val unwritable = File("/this/path/does/not/exist/debug.txt")
        DebugLog.file = unwritable
        DebugLog.log("swallowed") // must not throw
        assertTrue(DebugLog.awaitIdle()) // worker survives the failed open
        assertFalse("no file should be created under a nonexistent parent", unwritable.exists())
    }

    @Test fun unsettingFileStopsLogging() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        DebugLog.log("kept")
        DebugLog.file = null
        DebugLog.log("ignored")
        val lines = f.readLinesIdle()
        assertEquals(1, lines.size)
        assertFalse(f.readText().contains("ignored"))
    }

    @Test fun reassigningSamePathAfterCloseReopens() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        DebugLog.log("before")
        DebugLog.close()
        assertEquals("close() resets file to null", null, DebugLog.file)
        DebugLog.file = f // same path — must re-open, not stay closed
        DebugLog.log("after")

        val lines = f.readLinesIdle()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" before"))
        assertTrue(lines[1].endsWith(" after"))
    }

    @Test fun closeIsIdempotentAndSafeWhenUnset() {
        DebugLog.file = null
        DebugLog.close() // must not throw when already unset
        DebugLog.close()
    }

    @Test fun longMessageIsWrittenAsSingleLine() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        val payload = "x".repeat(5000)
        DebugLog.log(payload)

        assertEquals(1, f.readLinesIdle().size)
        assertTrue(f.readText().trimEnd().endsWith(payload))
    }

    @Test fun burstFromOneThreadIsWrittenIntactAndInOrder() {
        // A single thread floods the async writer; every line must survive and keep its order.
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        repeat(500) { i -> DebugLog.log("line-$i") }

        val lines = f.readLinesIdle()
        assertEquals(500, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue("line $i out of order or malformed: $line", line.endsWith(" line-$i"))
        }
    }

    @Test fun concurrentLogsProduceIntactLines() {
        val f = File.createTempFile("debuglog", ".txt").apply { deleteOnExit() }
        DebugLog.file = f
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(25) { i ->
                    DebugLog.log("thread-$t-line-$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val lines = f.readLinesIdle()
        assertEquals(100, lines.size)
        val linePattern = Regex("^\\d+ thread-(\\d+)-line-(\\d+)$")
        lines.forEach { line ->
            assertTrue("malformed or interleaved line: $line", linePattern.matches(line))
        }
    }

    @Test fun rotatesWhenMaxSizeExceeded() {
        val dir = Files.createTempDirectory("debuglog-rotate").toFile().apply { deleteOnExit() }
        val f = File(dir, "debug.txt")
        f.writeText("x".repeat(50))
        DebugLog.maxFileBytes = 49
        DebugLog.file = f
        DebugLog.log("after-rotate")

        DebugLog.awaitIdle()
        val rotated = File(dir, "debug.txt.1")
        assertTrue("expected rotated file", rotated.exists())
        assertEquals("x".repeat(50), rotated.readText())
        assertEquals(1, f.readLines().size)
        assertTrue(f.readLines()[0].endsWith(" after-rotate"))
    }

    @Test fun switchingFileTargetWritesToNewFile() {
        val f1 = File.createTempFile("debuglog1", ".txt").apply { deleteOnExit() }
        val f2 = File.createTempFile("debuglog2", ".txt").apply { deleteOnExit() }
        DebugLog.file = f1
        DebugLog.log("first")
        DebugLog.file = f2
        DebugLog.log("second")

        assertEquals(1, f2.readLinesIdle().size)
        assertEquals(1, f1.readLines().size)
        assertTrue(f1.readText().contains("first"))
        assertFalse(f1.readText().contains("second"))
        assertTrue(f2.readText().contains("second"))
    }
}
