package com.windinput.ime.keyboard.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapWriterTest {
    private val sample: Map<String, Map<String, Map<Gesture, GestureOverride>>> = mapOf(
        "letters" to mapOf(
            "z" to mapOf(
                Gesture.SWIPE_UP to GestureOverride(output = KeyOutput.Text("《")),
                Gesture.SWIPE_DOWN to GestureOverride(output = KeyOutput.Func("paste")),
                Gesture.LONG_PRESS to GestureOverride(bubble = listOf(KeyOutput.Text("〔"), KeyOutput.Text("·"))),
            ),
            "x2c" to mapOf(Gesture.SWIPE_UP to GestureOverride(output = KeyOutput.Template("{date} \"引\"\\"))),
            "q" to mapOf(Gesture.SWIPE_DOWN to GestureOverride(output = KeyOutput.Phrase("rq"))),
            "a" to mapOf(Gesture.SWIPE_UP to GestureOverride(output = KeyOutput.Char('!'))),
        ),
    )

    @Test
    fun roundTripsThroughParser() {
        val text = KeymapWriter.write(sample)
        val back = KeymapParser.parse(text)
        assertTrue(back.warnings.toString(), back.warnings.isEmpty())
        assertEquals(sample, back.layers)
    }

    @Test
    fun emptyEditsWriteOnlyHeader() {
        val back = KeymapParser.parse(KeymapWriter.write(emptyMap()))
        assertTrue(back.layers.isEmpty())
        assertTrue(back.warnings.isEmpty())
    }

    @Test
    fun explicitEmptyBubbleSurvives() {
        val m = mapOf("letters" to mapOf("comma" to mapOf(Gesture.LONG_PRESS to GestureOverride(bubble = emptyList()))))
        assertEquals(m, KeymapParser.parse(KeymapWriter.write(m)).layers)
    }

    @Test
    fun outputIsStable() {
        assertEquals(KeymapWriter.write(sample), KeymapWriter.write(sample))
    }
}
