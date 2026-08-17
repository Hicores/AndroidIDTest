package `fun`.test.id

import android.os.Process
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets

data class SsaidEntry(
    val packageName: String,
    val value: String
)

data class SsaidUpdateResult(
    val previousValue: String,
    val entries: List<SsaidEntry>
)

class RootOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads and updates the per-user SSAID settings file through a user-selected su executable. */
class SsaidRootRepository {

    // App UIDs encode the Android user in the high digits (PER_USER_RANGE = 100000).
    private val userId = Process.myUid() / 100000
    private val settingsPath = "/data/system/users/$userId/settings_ssaid.xml"
    private val temporaryPath = "$settingsPath.codex.tmp"

    fun readEntries(suExecutable: String): List<SsaidEntry> {
        requireRoot(suExecutable)
        return readFile(suExecutable)
    }

    fun updateEntry(
        suExecutable: String,
        packageName: String,
        newValue: String
    ): SsaidUpdateResult {
        requireRoot(suExecutable)
        val currentXml = readRawFile(suExecutable)
        val currentEntries = SsaidXmlCodec.parse(currentXml)
        val previousValue = currentEntries.firstOrNull { it.packageName == packageName }?.value
            ?: throw RootOperationException("未找到应用 $packageName 的 SSAID")
        val updatedXml = SsaidXmlCodec.replaceValue(currentXml, packageName, newValue)

        val writeResult = execute(
            suExecutable = suExecutable,
            command = "cat > ${shellQuote(temporaryPath)}" +
                " && chmod 600 ${shellQuote(temporaryPath)}" +
                " && chown system:system ${shellQuote(temporaryPath)}" +
                " && mv -f ${shellQuote(temporaryPath)} ${shellQuote(settingsPath)}",
            input = updatedXml
        )
        if (writeResult.exitCode != 0) {
            throw RootOperationException("写入 SSAID 文件失败: ${outputDetail(writeResult.output)}")
        }

        return SsaidUpdateResult(previousValue, readFile(suExecutable))
    }

    private fun readFile(suExecutable: String): List<SsaidEntry> =
        SsaidXmlCodec.parse(readRawFile(suExecutable))

    private fun readRawFile(suExecutable: String): ByteArray {
        val result = execute(suExecutable, "cat ${shellQuote(settingsPath)}")
        if (result.exitCode != 0) {
            throw RootOperationException(
                "读取 SSAID 文件失败: ${outputDetail(result.output)}\n路径: $settingsPath"
            )
        }
        return result.output
    }

    private fun requireRoot(suExecutable: String) {
        val executable = suExecutable.trim()
        if (executable.isEmpty()) {
            throw RootOperationException("su 文件名或路径不能为空")
        }
        val result = execute(executable, "id")
        if (result.exitCode != 0 || !result.output.toString(StandardCharsets.UTF_8).contains("uid=0")) {
            throw RootOperationException(
                "未获得 Root 权限: ${outputDetail(result.output)}"
            )
        }
    }

    private fun execute(
        suExecutable: String,
        command: String,
        input: ByteArray? = null
    ): CommandResult {
        try {
            val process = ProcessBuilder(suExecutable, "-c", command)
                .redirectErrorStream(true)
                .start()

            if (input == null) {
                process.outputStream.close()
            } else {
                process.outputStream.use { output ->
                    output.write(input)
                }
            }

            val output = process.inputStream.use { it.readBytes() }
            return CommandResult(process.waitFor(), output)
        } catch (error: Exception) {
            throw RootOperationException("无法启动 su: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private fun outputDetail(output: ByteArray): String =
        output.toString(StandardCharsets.UTF_8).trim().ifEmpty { "没有返回错误信息" }.takeLast(500)

    private data class CommandResult(
        val exitCode: Int,
        val output: ByteArray
    )
}

object SsaidXmlCodec {

    fun parse(xml: String): List<SsaidEntry> {
        return parse(xml.toByteArray(StandardCharsets.UTF_8))
    }

    fun parse(xml: ByteArray): List<SsaidEntry> {
        if (SsaidAbxCodec.isAbx(xml)) {
            return SsaidAbxCodec.parse(xml)
        }

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml.toString(StandardCharsets.UTF_8)))
            val entries = linkedMapOf<String, SsaidEntry>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "setting") {
                    val packageName = parser.getAttributeValue(null, "package")
                    val value = parser.getAttributeValue(null, "value")
                    if (!packageName.isNullOrBlank() && !value.isNullOrBlank()) {
                        entries[packageName] = SsaidEntry(packageName, value)
                    }
                }
                event = parser.next()
            }
            return entries.values.toList()
        } catch (error: Exception) {
            throw RootOperationException("SSAID 文件格式无法解析", error)
        }
    }

    fun replaceValue(xml: String, packageName: String, newValue: String): String {
        return replaceValue(
            xml.toByteArray(StandardCharsets.UTF_8),
            packageName,
            newValue
        ).toString(StandardCharsets.UTF_8)
    }

    fun replaceValue(xml: ByteArray, packageName: String, newValue: String): ByteArray {
        if (SsaidAbxCodec.isAbx(xml)) {
            return SsaidAbxCodec.replaceValue(xml, packageName, newValue)
        }

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml.toString(StandardCharsets.UTF_8)))
            val writer = StringWriter()
            val serializer = Xml.newSerializer()
            serializer.setOutput(writer)
            var found = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_DOCUMENT -> serializer.startDocument("UTF-8", true)
                    XmlPullParser.START_TAG -> {
                        val isTarget = parser.name == "setting" &&
                            parser.getAttributeValue(null, "package") == packageName
                        serializer.startTag(parser.namespace ?: "", parser.name)
                        var hasValue = false
                        for (index in 0 until parser.attributeCount) {
                            val namespace = parser.getAttributeNamespace(index) ?: ""
                            val name = parser.getAttributeName(index)
                            val value = parser.getAttributeValue(index)
                            if (isTarget && name == "value") {
                                serializer.attribute(namespace, name, newValue)
                                hasValue = true
                            } else {
                                serializer.attribute(namespace, name, value)
                            }
                        }
                        if (isTarget && !hasValue) {
                            serializer.attribute("", "value", newValue)
                        }
                        found = found || isTarget
                    }
                    XmlPullParser.END_TAG -> serializer.endTag(parser.namespace ?: "", parser.name)
                    XmlPullParser.TEXT -> serializer.text(parser.text)
                    XmlPullParser.CDSECT -> serializer.cdsect(parser.text)
                    XmlPullParser.ENTITY_REF -> serializer.entityRef(parser.name)
                    XmlPullParser.IGNORABLE_WHITESPACE -> serializer.ignorableWhitespace(parser.text)
                    XmlPullParser.COMMENT -> serializer.comment(parser.text)
                    XmlPullParser.PROCESSING_INSTRUCTION -> serializer.processingInstruction(parser.text)
                    XmlPullParser.DOCDECL -> serializer.docdecl(parser.text)
                }
                event = parser.next()
            }
            serializer.endDocument()

            if (!found) {
                throw RootOperationException("未找到应用 $packageName 的 SSAID")
            }
            return writer.toString().toByteArray(StandardCharsets.UTF_8)
        } catch (error: RootOperationException) {
            throw error
        } catch (error: Exception) {
            throw RootOperationException("SSAID 文件无法写回", error)
        }
    }
}

/**
 * Minimal Android Binary XML (ABX) codec for system settings files.
 *
 * The format is used by newer Android builds for files such as settings_ssaid.xml. It is
 * deliberately decoded and re-encoded here instead of relying on hidden framework APIs, so it
 * also works on the app's minimum supported Android version.
 */
private object SsaidAbxCodec {

    private val magic = byteArrayOf(0x41, 0x42, 0x58, 0x00)

    private const val START_DOCUMENT = 0
    private const val END_DOCUMENT = 1
    private const val START_TAG = 2
    private const val END_TAG = 3
    private const val TEXT = 4
    private const val CDSECT = 5
    private const val ENTITY_REF = 6
    private const val IGNORABLE_WHITESPACE = 7
    private const val PROCESSING_INSTRUCTION = 8
    private const val COMMENT = 9
    private const val DOCDECL = 10
    private const val ATTRIBUTE = 15

    private const val TYPE_NULL = 1 shl 4
    private const val TYPE_STRING = 2 shl 4
    private const val TYPE_STRING_INTERNED = 3 shl 4
    private const val TYPE_BYTES_HEX = 4 shl 4
    private const val TYPE_BYTES_BASE64 = 5 shl 4
    private const val TYPE_INT = 6 shl 4
    private const val TYPE_INT_HEX = 7 shl 4
    private const val TYPE_LONG = 8 shl 4
    private const val TYPE_LONG_HEX = 9 shl 4
    private const val TYPE_FLOAT = 10 shl 4
    private const val TYPE_DOUBLE = 11 shl 4
    private const val TYPE_BOOLEAN_TRUE = 12 shl 4
    private const val TYPE_BOOLEAN_FALSE = 13 shl 4

    fun isAbx(xml: ByteArray): Boolean =
        xml.size >= magic.size && magic.indices.all { xml[it] == magic[it] }

    fun parse(xml: ByteArray): List<SsaidEntry> {
        val tokens = readTokens(xml)
        val entries = linkedMapOf<String, SsaidEntry>()

        tokens.forEachIndexed { index, token ->
            if (token.token != START_TAG || token.name != "setting") return@forEachIndexed
            val attributes = attributesAfter(tokens, index)
            val packageName = attributes.firstOrNull { it.name == "package" }?.value?.asString()
            val value = attributes.firstOrNull { it.name == "value" }?.value?.asString()
            if (!packageName.isNullOrBlank() && !value.isNullOrBlank()) {
                entries[packageName] = SsaidEntry(packageName, value)
            }
        }
        return entries.values.toList()
    }

    fun replaceValue(xml: ByteArray, packageName: String, newValue: String): ByteArray {
        val tokens = readTokens(xml).toMutableList()
        var found = false
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]
            if (token.token != START_TAG || token.name != "setting") {
                index++
                continue
            }

            var attributeEnd = index + 1
            while (attributeEnd < tokens.size && tokens[attributeEnd].token == ATTRIBUTE) {
                attributeEnd++
            }
            val attributes = tokens.subList(index + 1, attributeEnd)
            val entryPackageName = attributes
                .firstOrNull { it.name == "package" }
                ?.value
                ?.asString()
            if (entryPackageName == packageName) {
                val valueIndex = attributes.indexOfFirst { it.name == "value" }
                val replacement = AbxValue.StringValue(newValue)
                if (valueIndex >= 0) {
                    val absoluteValueIndex = index + 1 + valueIndex
                    tokens[absoluteValueIndex] = tokens[absoluteValueIndex].copy(
                        type = TYPE_STRING,
                        value = replacement
                    )
                } else {
                    tokens.add(
                        attributeEnd,
                        AbxToken(
                            token = ATTRIBUTE,
                            type = TYPE_STRING,
                            name = "value",
                            value = replacement
                        )
                    )
                    attributeEnd++
                }
                found = true
            }
            index = attributeEnd
        }

        if (!found) {
            throw RootOperationException("未找到应用 $packageName 的 SSAID")
        }
        return writeTokens(tokens)
    }

    private fun attributesAfter(tokens: List<AbxToken>, startTagIndex: Int): List<AbxToken> {
        val result = mutableListOf<AbxToken>()
        var index = startTagIndex + 1
        while (index < tokens.size && tokens[index].token == ATTRIBUTE) {
            result += tokens[index]
            index++
        }
        return result
    }

    private fun readTokens(xml: ByteArray): List<AbxToken> {
        if (!isAbx(xml)) {
            throw RootOperationException("不是有效的 Android Binary XML 文件")
        }

        try {
            val input = DataInputStream(ByteArrayInputStream(xml, magic.size, xml.size - magic.size))
            val internedStrings = mutableListOf<String>()
            val tokens = mutableListOf<AbxToken>()

            while (input.available() > 0) {
                val event = input.readUnsignedByte()
                val token = event and 0x0f
                val type = event and 0xf0
                tokens += when (token) {
                    ATTRIBUTE -> AbxToken(
                        token = token,
                        type = type,
                        name = readInternedUtf(input, internedStrings),
                        value = readValue(input, type, internedStrings)
                    )
                    START_TAG, END_TAG -> AbxToken(
                        token = token,
                        type = type,
                        name = readInternedUtf(input, internedStrings)
                    )
                    START_DOCUMENT, END_DOCUMENT -> AbxToken(token, type)
                    TEXT,
                    CDSECT,
                    ENTITY_REF,
                    IGNORABLE_WHITESPACE,
                    PROCESSING_INSTRUCTION,
                    COMMENT,
                    DOCDECL -> AbxToken(
                        token = token,
                        type = type,
                        value = AbxValue.StringValue(input.readUTF())
                    )
                    else -> throw RootOperationException("不支持的 Android Binary XML 标记: $token")
                }
            }
            return tokens
        } catch (error: RootOperationException) {
            throw error
        } catch (error: Exception) {
            throw RootOperationException("SSAID Binary XML 文件格式无法解析", error)
        }
    }

    private fun readInternedUtf(
        input: DataInputStream,
        internedStrings: MutableList<String>
    ): String {
        val reference = input.readUnsignedShort()
        if (reference != 0xffff) {
            return internedStrings.getOrNull(reference)
                ?: throw RootOperationException("Android Binary XML 字符串索引无效: $reference")
        }

        val value = input.readUTF()
        if (internedStrings.size < 0xffff) {
            internedStrings += value
        }
        return value
    }

    private fun readValue(
        input: DataInputStream,
        type: Int,
        internedStrings: MutableList<String>
    ): AbxValue = when (type) {
        TYPE_NULL,
        TYPE_BOOLEAN_TRUE,
        TYPE_BOOLEAN_FALSE -> AbxValue.NoValue
        TYPE_STRING -> AbxValue.StringValue(input.readUTF())
        TYPE_STRING_INTERNED -> AbxValue.StringValue(readInternedUtf(input, internedStrings))
        TYPE_BYTES_HEX,
        TYPE_BYTES_BASE64 -> AbxValue.BytesValue(ByteArray(input.readUnsignedShort()).also(input::readFully))
        TYPE_INT,
        TYPE_INT_HEX -> AbxValue.IntValue(input.readInt())
        TYPE_LONG,
        TYPE_LONG_HEX -> AbxValue.LongValue(input.readLong())
        TYPE_FLOAT -> AbxValue.FloatValue(input.readFloat())
        TYPE_DOUBLE -> AbxValue.DoubleValue(input.readDouble())
        else -> throw RootOperationException("不支持的 Android Binary XML 属性类型: $type")
    }

    private fun writeTokens(tokens: List<AbxToken>): ByteArray {
        try {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.write(magic)
                val internedStrings = linkedMapOf<String, Int>()
                tokens.forEach { token ->
                    data.writeByte(token.token or token.type)
                    when (token.token) {
                        ATTRIBUTE -> {
                            writeInternedUtf(data, token.name.orEmpty(), internedStrings)
                            writeValue(data, token.type, token.value, internedStrings)
                        }
                        START_TAG, END_TAG -> {
                            writeInternedUtf(data, token.name.orEmpty(), internedStrings)
                        }
                        START_DOCUMENT, END_DOCUMENT -> Unit
                        TEXT,
                        CDSECT,
                        ENTITY_REF,
                        IGNORABLE_WHITESPACE,
                        PROCESSING_INSTRUCTION,
                        COMMENT,
                        DOCDECL -> data.writeUTF(token.value.asString().orEmpty())
                        else -> throw RootOperationException("不支持的 Android Binary XML 标记: ${token.token}")
                    }
                }
            }
            return output.toByteArray()
        } catch (error: RootOperationException) {
            throw error
        } catch (error: Exception) {
            throw RootOperationException("SSAID Binary XML 文件无法写回", error)
        }
    }

    private fun writeInternedUtf(
        output: DataOutputStream,
        value: String,
        internedStrings: MutableMap<String, Int>
    ) {
        val reference = internedStrings[value]
        if (reference != null) {
            output.writeShort(reference)
            return
        }

        output.writeShort(0xffff)
        output.writeUTF(value)
        if (internedStrings.size < 0xffff) {
            internedStrings[value] = internedStrings.size
        }
    }

    private fun writeValue(
        output: DataOutputStream,
        type: Int,
        value: AbxValue,
        internedStrings: MutableMap<String, Int>
    ) {
        when (type) {
            TYPE_NULL,
            TYPE_BOOLEAN_TRUE,
            TYPE_BOOLEAN_FALSE -> Unit
            TYPE_STRING -> output.writeUTF(value.asString().orEmpty())
            TYPE_STRING_INTERNED -> writeInternedUtf(output, value.asString().orEmpty(), internedStrings)
            TYPE_BYTES_HEX,
            TYPE_BYTES_BASE64 -> {
                val bytes = (value as? AbxValue.BytesValue)?.value
                    ?: throw RootOperationException("Android Binary XML 字节属性缺少内容")
                output.writeShort(bytes.size)
                output.write(bytes)
            }
            TYPE_INT,
            TYPE_INT_HEX -> output.writeInt((value as? AbxValue.IntValue)?.value ?: 0)
            TYPE_LONG,
            TYPE_LONG_HEX -> output.writeLong((value as? AbxValue.LongValue)?.value ?: 0L)
            TYPE_FLOAT -> output.writeFloat((value as? AbxValue.FloatValue)?.value ?: 0f)
            TYPE_DOUBLE -> output.writeDouble((value as? AbxValue.DoubleValue)?.value ?: 0.0)
            else -> throw RootOperationException("不支持的 Android Binary XML 属性类型: $type")
        }
    }

    private data class AbxToken(
        val token: Int,
        val type: Int,
        val name: String? = null,
        val value: AbxValue = AbxValue.NoValue
    )

    private sealed interface AbxValue {
        fun asString(): String? = when (this) {
            NoValue -> null
            is StringValue -> value
            is BytesValue -> value.joinToString(separator = "") { "%02x".format(it) }
            is IntValue -> value.toString()
            is LongValue -> value.toString()
            is FloatValue -> value.toString()
            is DoubleValue -> value.toString()
        }

        data object NoValue : AbxValue
        data class StringValue(val value: String) : AbxValue
        data class BytesValue(val value: ByteArray) : AbxValue
        data class IntValue(val value: Int) : AbxValue
        data class LongValue(val value: Long) : AbxValue
        data class FloatValue(val value: Float) : AbxValue
        data class DoubleValue(val value: Double) : AbxValue
    }
}
