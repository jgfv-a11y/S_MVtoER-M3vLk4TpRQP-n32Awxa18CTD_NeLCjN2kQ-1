package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class AtomicFileOpsTest {

    @Test
    fun `same-directory replacement overwrites the old file and consumes the temporary file`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "state.json.tmp").apply { writeText("new state") }
        val destination = File(dir, "state.json").apply { writeText("old state") }

        AtomicFileOps.replace(source, destination)

        assertEquals("new state", destination.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `cross-directory replacement is rejected without touching either file`() {
        val parent = createTempDirectory().toFile()
        val sourceDir = File(parent, "source").apply { mkdirs() }
        val destinationDir = File(parent, "destination").apply { mkdirs() }
        val source = File(sourceDir, "temp").apply { writeText("new") }
        val destination = File(destinationDir, "live").apply { writeText("old") }

        val failure = runCatching { AtomicFileOps.replace(source, destination) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("new", source.readText())
        assertEquals("old", destination.readText())
    }
}
