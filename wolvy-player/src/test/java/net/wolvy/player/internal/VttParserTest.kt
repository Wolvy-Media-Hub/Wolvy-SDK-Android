package net.wolvy.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VttParserTest {
    private val vtt = """
        WEBVTT

        NOTE written by hand

        1
        00:00:01.000 --> 00:00:03.500
        <v Teacher>Hello <b>there</b></v>

        00:04.000 --> 00:06,250
        Short form, comma decimals
        on two lines

        01:00:00.000 --> 01:00:02.000
        An hour in
    """.trimIndent()

    @Test fun parsesHoursOptionalCommaAndStripsTags() {
        val cues = VttParser.parse(vtt)
        assertEquals(3, cues.size)
        assertEquals(Cue(1.0, 3.5, "Hello there"), cues[0])
        assertEquals(Cue(4.0, 6.25, "Short form, comma decimals\non two lines"), cues[1])
        assertEquals(3600.0, cues[2].start, 0.0)
    }

    @Test fun findsTheCueAtATime() {
        val cues = VttParser.parse(vtt)
        assertEquals("Hello there", VttParser.at(cues, 1.0)?.text)
        assertNull(VttParser.at(cues, 3.5))  // end is exclusive
        assertNull(VttParser.at(cues, 0.5))
    }

    @Test fun windowsLineEndingsAndEmptyCues() {
        val cues = VttParser.parse("WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\n<i></i>\r\n\r\n00:00:02.000 --> 00:00:03.000\r\nok\r\n")
        assertEquals(listOf(Cue(2.0, 3.0, "ok")), cues)
    }
}
