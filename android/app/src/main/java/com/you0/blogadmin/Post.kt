package com.you0.blogadmin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PostData(
    var title: String = "",
    var tags: List<String> = emptyList(),
    var categories: List<String> = emptyList(),
    var date: String = "",
    var author: String = "",
    var body: String = "",
)

object Post {

    fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun splitList(s: String): List<String> =
        s.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    fun sanitize(t: String): String =
        t.replace(Regex("[\\\\/:*?\"<>|]"), "-").replace(Regex("\\s+"), " ").trim()

    fun build(p: PostData): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("title: ").append(p.title).append("\n")
        sb.append("date: ").append(if (p.date.isBlank()) nowString() else p.date).append("\n")
        if (p.author.isNotBlank()) sb.append("author: ").append(p.author).append("\n")
        sb.append("tags:\n")
        p.tags.forEach { sb.append("  - ").append(it).append("\n") }
        sb.append("categories:\n")
        if (p.categories.isEmpty()) sb.append("  []\n")
        else p.categories.forEach { sb.append("  - ").append(it).append("\n") }
        sb.append("---\n\n")
        sb.append(p.body)
        return sb.toString()
    }

    fun parse(text: String): PostData {
        val out = PostData(body = text)
        val m = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?([\\s\\S]*)$").find(text) ?: return out
        out.body = m.groupValues[2]
        val tags = mutableListOf<String>()
        val cats = mutableListOf<String>()
        var curList: String? = null
        for (raw in m.groupValues[1].split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("-")) {
                when (curList) {
                    "tags" -> tags.add(line.removePrefix("-").trim())
                    "categories" -> cats.add(line.removePrefix("-").trim())
                }
                continue
            }
            val kv = Regex("^([A-Za-z_]+):\\s*(.*)$").find(line) ?: continue
            val key = kv.groupValues[1]
            val value = kv.groupValues[2].trim()
            when (key) {
                "title" -> out.title = value
                "date" -> out.date = value
                "author" -> out.author = value
                "tags", "categories" -> {
                    when {
                        value.isEmpty() || value == "[]" -> curList = null
                        value.startsWith("[") -> {
                            value.removeSurrounding("[", "]").split(',').map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach { if (key == "tags") tags.add(it) else cats.add(it) }
                            curList = null
                        }
                        else -> {
                            if (key == "tags") tags.add(value) else cats.add(value)
                            curList = key
                        }
                    }
                }
                else -> curList = null
            }
        }
        out.tags = tags
        out.categories = cats
        return out
    }
}
