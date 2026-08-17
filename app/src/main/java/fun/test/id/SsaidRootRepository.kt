package `fun`.test.id

import android.os.Process
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
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
            input = updatedXml.toByteArray(StandardCharsets.UTF_8)
        )
        if (writeResult.exitCode != 0) {
            throw RootOperationException("写入 SSAID 文件失败: ${outputDetail(writeResult.output)}")
        }

        return SsaidUpdateResult(previousValue, readFile(suExecutable))
    }

    private fun readFile(suExecutable: String): List<SsaidEntry> =
        SsaidXmlCodec.parse(readRawFile(suExecutable))

    private fun readRawFile(suExecutable: String): String {
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
        if (result.exitCode != 0 || !result.output.contains("uid=0")) {
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

            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return CommandResult(process.waitFor(), output)
        } catch (error: Exception) {
            throw RootOperationException("无法启动 su: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private fun outputDetail(output: String): String =
        output.trim().ifEmpty { "没有返回错误信息" }.takeLast(500)

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )
}

object SsaidXmlCodec {

    fun parse(xml: String): List<SsaidEntry> {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
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
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
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
            return writer.toString()
        } catch (error: RootOperationException) {
            throw error
        } catch (error: Exception) {
            throw RootOperationException("SSAID 文件无法写回", error)
        }
    }
}
