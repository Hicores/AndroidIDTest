package `fun`.test.id

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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

    @Test
    fun parsesAndRewritesAbxSsaidEntries() {
        val rewritten = SsaidXmlCodec.replaceValue(
            sampleAbx(),
            packageName = "com.example.second",
            newValue = "1111222233334444"
        )

        assertTrue(rewritten.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x42, 0x58, 0x00)))
        assertEquals(
            listOf(
                SsaidEntry("com.example.first", "0123456789abcdef"),
                SsaidEntry("com.example.second", "1111222233334444")
            ),
            SsaidXmlCodec.parse(rewritten)
        )
    }

    private fun sampleXml(): String =
        """
        <?xml version="1.0" encoding="utf-8" standalone="yes" ?>
        <settings version="1">
            <setting package="com.example.first" value="0123456789abcdef" />
            <setting package="com.example.second" value="fedcba9876543210" />
        </settings>
        """.trimIndent()

    private fun sampleAbx(): ByteArray {
        val output = ByteArrayOutputStream()
        val internedStrings = linkedMapOf<String, Int>()
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf(0x41, 0x42, 0x58, 0x00))
            data.writeByte(0x10)
            writeTag(data, 2, "settings", internedStrings)
            writeAttribute(data, "version", "1", internedStrings)
            writeTag(data, 2, "setting", internedStrings)
            writeAttribute(data, "package", "com.example.first", internedStrings)
            writeAttribute(data, "value", "0123456789abcdef", internedStrings)
            writeTag(data, 3, "setting", internedStrings)
            writeTag(data, 2, "setting", internedStrings)
            writeAttribute(data, "package", "com.example.second", internedStrings)
            writeAttribute(data, "value", "fedcba9876543210", internedStrings)
            writeTag(data, 3, "setting", internedStrings)
            writeTag(data, 3, "settings", internedStrings)
            data.writeByte(0x11)
        }
        return output.toByteArray()
    }

    private fun writeTag(
        output: DataOutputStream,
        token: Int,
        name: String,
        internedStrings: MutableMap<String, Int>
    ) {
        output.writeByte(token or 0x30)
        writeInternedUtf(output, name, internedStrings)
    }

    private fun writeAttribute(
        output: DataOutputStream,
        name: String,
        value: String,
        internedStrings: MutableMap<String, Int>
    ) {
        output.writeByte(15 or 0x20)
        writeInternedUtf(output, name, internedStrings)
        output.writeUTF(value)
    }

    private fun writeInternedUtf(
        output: DataOutputStream,
        value: String,
        internedStrings: MutableMap<String, Int>
    ) {
        val reference = internedStrings[value]
        if (reference != null) {
            output.writeShort(reference)
        } else {
            output.writeShort(0xffff)
            output.writeUTF(value)
            internedStrings[value] = internedStrings.size
        }
    }
}
