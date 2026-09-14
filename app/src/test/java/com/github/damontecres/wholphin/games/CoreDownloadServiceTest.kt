package com.github.damontecres.wholphin.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreDownloadServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, String>): File {
        val file = tmp.newFile("support.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `support archive is flattened under its top folder and traversal is skipped`() {
        val archive =
            zip(
                mapOf(
                    "PPSSPP/compat.ini" to "ok",
                    "PPSSPP/flash0/font/a.pgf" to "font",
                    "PPSSPP\\backslash.txt" to "slash",
                    "PPSSPP/../escape.txt" to "bad",
                    "Other/ignored.txt" to "no",
                ),
            )
        val dest = tmp.newFolder("dest")
        CoreDownloadService.unpackSupportArchive(archive, "PPSSPP", dest)

        assertEquals("ok", File(dest, "compat.ini").readText())
        assertEquals("font", File(dest, "flash0/font/a.pgf").readText())
        assertEquals("slash", File(dest, "backslash.txt").readText())
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(dest, "escape.txt").exists())
        assertFalse(File(dest, "ignored.txt").exists())
        assertTrue(dest.walk().filter { it.isFile }.count() == 3)
    }
}
