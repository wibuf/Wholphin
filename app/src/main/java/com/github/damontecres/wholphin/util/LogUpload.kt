package com.github.damontecres.wholphin.util

/**
 * Fork-only (GooseFlix): logs and crash reports sized for Jellyfin's client log upload, which
 * rejects documents over 1,000,000 bytes. A stack overflow's crash report is far bigger than that
 * (thousands of repeated frames), so sending it failed and nothing reached the server.
 */
object LogUpload {
    /** Under Jellyfin's 1,000,000-byte limit, leaving room for the request around it */
    const val MAX_BYTES = 900_000

    /** Identical consecutive lines kept before the rest are counted instead */
    private const val KEEP_REPEATS = 3

    /**
     * [text] cut to fit [maxBytes] (UTF-8): the start (the header and the error) and the end (the
     * most recent lines) are kept, the middle is replaced by a note saying how much was cut.
     */
    fun fit(
        text: String,
        maxBytes: Int = MAX_BYTES,
    ): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var budget = maxBytes
        while (budget > 1_000) {
            val head = budget / 3
            val tail = budget - head
            val cut = text.length - head - tail
            val fitted =
                text.take(head) +
                    "\n\n… ${"%,d".format(cut)} characters cut to fit the server's upload limit …\n\n" +
                    text.takeLast(tail)
            if (fitted.toByteArray(Charsets.UTF_8).size <= maxBytes) return fitted
            budget = budget * 3 / 4
        }
        return text.takeLast(maxBytes / 4)
    }

    /**
     * A stack trace with runs of the same line (a stack overflow repeats one frame thousands of
     * times) cut to the first few and a count.
     */
    fun collapseRepeats(trace: String): String {
        val out = StringBuilder()
        var previous: String? = null
        var run = 0
        fun flush() {
            if (run > KEEP_REPEATS) {
                out.append("\t... the same line ${"%,d".format(run - KEEP_REPEATS)} more times\n")
            }
        }
        for (line in trace.lineSequence()) {
            if (line == previous) {
                run++
                if (run <= KEEP_REPEATS) out.append(line).append('\n')
            } else {
                flush()
                out.append(line).append('\n')
                previous = line
                run = 1
            }
        }
        flush()
        return out.toString().trimEnd('\n')
    }
}
