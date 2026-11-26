package io.github.openmusic.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Untrusted import boundary. Only semantic score.json is used from a scorepack. */
object ScoreImport {
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024

    fun read(input: InputStream): String {
        val bytes = readBounded(input, MAX_PACKAGE_BYTES)
        val json = if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            readPackage(bytes)
        } else {
            require(bytes.size <= MAX_JSON_BYTES) { "ScoreIR JSON 最大支持 2 MiB。" }
            bytes
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString()
            .removePrefix("\uFEFF")
    }

    private fun readPackage(bytes: ByteArray): ByteArray {
        var score: ByteArray? = null
        var total = 0
        var count = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 128) { "曲谱包条目过多。" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && ':' !in name && name.split('/').none { it == ".." }) {
                    "曲谱包包含非法路径。"
                }
                require(seen.add(name)) { "曲谱包包含重复条目：$name" }
                if (!entry.isDirectory) {
                    val content = readBounded(zip, minOf(MAX_JSON_BYTES, MAX_PACKAGE_BYTES - total))
                    total += content.size
                    require(total <= MAX_PACKAGE_BYTES) { "曲谱包解压内容过大。" }
                    if (name == "score.json") score = content
                }
                zip.closeEntry()
            }
        }
        return requireNotNull(score) { "曲谱包根目录需要包含 score.json。" }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= limit) { "文件或曲谱包内容超过大小限制。" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
