package `fun`.test.id

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SsaidXmlCodecInstrumentedTest {

    @Test
    fun parsesSsaidEntries() {
        val entries = SsaidXmlCodec.parse(sampleXml())

        assertEquals(
            listOf(
                SsaidEntry("com.example.first", "0123456789abcdef"),
                SsaidEntry("com.example.second", "fedcba9876543210")
            ),
            entries
        )
    }

    @Test
    fun replacesOnlyTheRequestedPackage() {
        val rewritten = SsaidXmlCodec.replaceValue(
            sampleXml(),
            packageName = "com.example.second",
            newValue = "1111222233334444"
        )

        val entries = SsaidXmlCodec.parse(rewritten)
        assertEquals("0123456789abcdef", entries[0].value)
        assertEquals("1111222233334444", entries[1].value)
        assertTrue(rewritten.contains("version=\"1\""))
    }

    private fun sampleXml(): String =
        """
        <?xml version="1.0" encoding="utf-8" standalone="yes" ?>
        <settings version="1">
            <setting package="com.example.first" value="0123456789abcdef" />
            <setting package="com.example.second" value="fedcba9876543210" />
        </settings>
        """.trimIndent()
}
