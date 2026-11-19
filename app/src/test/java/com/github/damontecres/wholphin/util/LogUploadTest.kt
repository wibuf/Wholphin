package com.github.damontecres.wholphin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork-only (GooseFlix): logs sized for the server's client log upload */
class LogUploadTest {
    @Test
    fun `a small log is sent as it is`() {
        assertEquals("hello", LogUpload.fit("hello"))
    }

    @Test
    fun `a big log keeps its start and its end, under the limit`() {
        val text = "HEADER\n" + "x".repeat(2_000_000) + "\nLAST LINE"
        val fitted = LogUpload.fit(text)
        assertTrue(fitted.toByteArray().size <= LogUpload.MAX_BYTES)
        assertTrue(fitted.startsWith("HEADER"))
        assertTrue(fitted.endsWith("LAST LINE"))
        assertTrue(fitted.contains("characters cut to fit"))
    }

    @Test
    fun `a stack overflow's repeated frames are counted, not listed`() {
        val frame = "\tat com.example.Foo.onSortChange(Foo.kt:368)"
        val trace = "java.lang.StackOverflowError: stack size 8MB\n" + List(5000) { frame }.joinToString("\n") + "\n\tat com.example.Main.run(Main.kt:1)"
        val collapsed = LogUpload.collapseRepeats(trace)
        assertEquals(
            listOf("java.lang.StackOverflowError: stack size 8MB", frame, frame, frame, "\t... the same line 4,997 more times", "\tat com.example.Main.run(Main.kt:1)"),
            collapsed.lines(),
        )
    }
}
