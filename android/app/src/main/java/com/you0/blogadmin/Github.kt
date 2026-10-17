package com.you0.blogadmin

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class RepoConfig(
    var token: String = "",
    var owner: String = "You0",
    var repo: String = "my-blog",
    var branch: String = "main",
    var postsDir: String = "source/_posts",
    var author: String = "You0",
)

object Github {

    private fun request(cfg: RepoConfig, path: String, method: String = "GET", body: JSONObject? = null): String {
        val conn = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (cfg.token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optString("message") } catch (_: Exception) { text }
            throw Exception("GitHub API $code: $msg")
        }
        return text
    }

    private fun encodePath(p: String): String =
        p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    data class PostFile(
        val path: String,
        val name: String,
        val sha: String,
        val date: String = "",
        val title: String = "",
        val categories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val images: List<String> = emptyList(),
    )

    fun listPosts(cfg: RepoConfig, previous: List<PostFile> = emptyList()): List<PostFile> {
        val json = JSONObject(request(cfg, "/repos/${cfg.owner}/${cfg.repo}/git/trees/${cfg.branch}?recursive=1"))
        val prefix = cfg.postsDir.trimEnd('/') + "/"
        val tree = json.optJSONArray("tree") ?: return emptyList()
        val out = mutableListOf<PostFile>()
        for (i in 0 until tree.length()) {
            val t = tree.optJSONObject(i)
            val path = t.optString("path")
            val type = t.optString("type")
            if (type == "blob" && path.startsWith(prefix) && path.endsWith(".md")) {
                out.add(PostFile(path, path.removePrefix(prefix), t.optString("sha")))
            }
        }
        // 用本地缓存给 metaCache 预热：sha 没变的文章直接复用，不再逐篇读正文
        for (prev in previous) {
            metaCache[prev.path] = prev.sha to Meta(prev.date, prev.title, prev.categories, prev.tags, prev.images)
        }
        return fetchDates(cfg, out).sortedWith(
            compareByDescending<PostFile> { it.date }
                .thenBy { it.path }
        )
    }

    private data class Meta(
        val date: String = "",
        val title: String = "",
        val categories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val images: List<String> = emptyList(),
    )

    private val metaCache = ConcurrentHashMap<String, Pair<String, Meta>>()

    // 并行读取每篇的 front-matter（date/title/分类/标签/首图）；按 path+sha 缓存，未变化的文章不重复拉取
    private fun fetchDates(cfg: RepoConfig, files: List<PostFile>): List<PostFile> {
        if (files.isEmpty()) return files
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = files.map { f ->
                pool.submit<PostFile> {
                    val cached = metaCache[f.path]
                    val meta = if (cached != null && cached.first == f.sha) cached.second
                    else {
                        val m = try {
                            val p = Post.parse(getFile(cfg, f.path).text)
                            Meta(p.date, p.title, p.categories, p.tags, allImages(p.body))
                        } catch (_: Exception) {
                            Meta()
                        }
                        metaCache[f.path] = f.sha to m
                        m
                    }
                    f.copy(
                        date = meta.date,
                        title = meta.title,
                        categories = meta.categories,
                        tags = meta.tags,
                        images = meta.images,
                    )
                }
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    // 取正文里全部图片的地址（相对路径原样返回，由调用方解析为完整 URL）
    private fun allImages(body: String): List<String> =
        Regex("!\\[[^\\]]*]\\(([^)\\s]+)\\)").findAll(body).map { it.groupValues[1] }.toList()

    data class FileContent(val sha: String, val text: String)

    fun getFile(cfg: RepoConfig, path: String): FileContent {
        val json = JSONObject(request(cfg, "/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}"))
        val b64 = json.optString("content", "").replace("\n", "")
        val text = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        return FileContent(json.optString("sha"), text)
    }

    fun putFile(cfg: RepoConfig, path: String, content: String, sha: String?, message: String) {
        val body = JSONObject()
        body.put("message", message)
        body.put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        body.put("branch", cfg.branch)
        if (sha != null) body.put("sha", sha)
        request(cfg, "/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}", "PUT", body)
    }

    fun putImage(cfg: RepoConfig, path: String, bytes: ByteArray) {
        val body = JSONObject()
        body.put("message", "upload image: $path")
        body.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        body.put("branch", cfg.branch)
        request(cfg, "/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}", "PUT", body)
    }

    fun deleteFile(cfg: RepoConfig, path: String, sha: String, message: String) {
        val body = JSONObject()
        body.put("message", message)
        body.put("sha", sha)
        body.put("branch", cfg.branch)
        request(cfg, "/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}", "DELETE", body)
    }

    fun testConnection(cfg: RepoConfig): String {
        val json = JSONObject(request(cfg, "/repos/${cfg.owner}/${cfg.repo}"))
        return json.optString("full_name")
    }
}
