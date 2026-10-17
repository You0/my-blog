package com.you0.blogadmin

object Markdown {

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String {
        var t = escape(s)
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)"), "<img src=\"$2\" alt=\"$1\">")
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("__([^_]+)__"), "<strong>$1</strong>")
        t = t.replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        t = t.replace(Regex("~~([^~]+)~~"), "<del>$1</del>")
        return t
    }

    fun render(src: String): String {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val html = StringBuilder()
        var inCode = false
        val codeBuf = StringBuilder()
        var inList: String? = null
        var inQuote = false

        fun flushList() {
            if (inList != null) {
                html.append("</").append(inList).append(">")
                inList = null
            }
        }

        fun flushQuote() {
            if (inQuote) {
                html.append("</blockquote>")
                inQuote = false
            }
        }

        for (line in lines) {
            if (inCode) {
                if (line.startsWith("```")) {
                    html.append("<pre><code>").append(escape(codeBuf.toString())).append("</code></pre>")
                    inCode = false
                    codeBuf.clear()
                } else {
                    codeBuf.append(line).append('\n')
                }
                continue
            }
            if (line.startsWith("```")) { flushList(); flushQuote(); inCode = true; continue }
            if (line.isBlank()) { flushList(); flushQuote(); continue }

            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (h != null) {
                flushList(); flushQuote()
                val n = h.groupValues[1].length
                html.append("<h").append(n).append(">").append(inline(h.groupValues[2])).append("</h").append(n).append(">")
                continue
            }
            if (Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$").matches(line)) { flushList(); flushQuote(); html.append("<hr>"); continue }

            val q = Regex("^>\\s?(.*)$").find(line)
            if (q != null) {
                flushList()
                if (!inQuote) { html.append("<blockquote>"); inQuote = true }
                html.append(inline(q.groupValues[1]))
                continue
            }
            val ul = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            if (ul != null) {
                flushQuote()
                if (inList != "ul") { flushList(); html.append("<ul>"); inList = "ul" }
                html.append("<li>").append(inline(ul.groupValues[1])).append("</li>")
                continue
            }
            val ol = Regex("^\\s*\\d+[.)]\\s+(.*)$").find(line)
            if (ol != null) {
                flushQuote()
                if (inList != "ol") { flushList(); html.append("<ol>"); inList = "ol" }
                html.append("<li>").append(inline(ol.groupValues[1])).append("</li>")
                continue
            }
            flushList(); flushQuote()
            html.append("<p>").append(inline(line)).append("</p>")
        }
        flushList()
        flushQuote()
        if (inCode) html.append("<pre><code>").append(escape(codeBuf.toString())).append("</code></pre>")
        return html.toString()
    }
}
