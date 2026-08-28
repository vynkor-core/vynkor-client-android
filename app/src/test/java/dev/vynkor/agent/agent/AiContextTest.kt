package dev.vynkor.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextTest {

    private fun source(
        name: String,
        text: String? = null,
        type: String = "text",
    ) = AiContext.Source(name, type, "1 KB", text)

    @Test
    fun emptyInputsYieldNull() {
        assertNull(AiContext.buildBlock(null, emptyList()))
        assertNull(AiContext.buildBlock("", emptyList()))
    }

    @Test
    fun projectNameAloneProducesMinimalBlock() {
        val block = AiContext.buildBlock("Home automation", emptyList())!!
        assertTrue(block.contains("Project: Home automation"))
        assertFalse(block.contains("Attached materials"))
    }

    @Test
    fun filesWithTextAreInlinedBetweenMarkers() {
        val block = AiContext.buildBlock(
            null,
            listOf(source("notes.md", text = "# Notes\nhello")),
        )!!
        assertTrue(block.contains("- [1] notes.md"))
        assertTrue(block.contains("--- begin notes.md ---"))
        assertTrue(block.contains("# Notes\nhello"))
        assertTrue(block.contains("--- end notes.md ---"))
    }

    @Test
    fun binarySourcesCarryMetadataOnly() {
        val block = AiContext.buildBlock(null, listOf(source("clip.mp4", text = null, type = "video")))!!
        assertTrue(block.contains("[1] clip.mp4 (video, 1 KB)"))
        assertFalse(block.contains("begin clip.mp4"))
    }

    /** '~' cannot occur in the block's own static text — pure payload counter. */
    @Test
    fun totalInlineTextIsBounded() {
        val big = "~".repeat(20_000)
        val block = AiContext.buildBlock(null, List(3) { i -> source("f$i.txt", text = big) })!!
        assertEquals(24_000, block.count { it == '~' })
        assertTrue(block.contains("omitted due to size limits"))
    }

    @Test
    fun moreThanMaxSourcesAreSummarized() {
        val sources = (0 until 15).map { source("f$it.txt", text = "content") }
        val block = AiContext.buildBlock(null, sources)!!
        assertFalse(block.contains("f12.txt"))
        assertTrue(block.contains("more file(s) not included"))
    }
}
