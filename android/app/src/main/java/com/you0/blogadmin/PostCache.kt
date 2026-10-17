package com.you0.blogadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 已发布列表的本地缓存：打开先显示缓存，后台再拉仓库刷新，避免每次都干等。
object PostCache {

    private fun cacheFile(ctx: Context, cfg: RepoConfig): File =
        File(ctx.filesDir, "posts_${cfg.owner}_${cfg.repo}_${cfg.branch}_${cfg.postsDir.replace('/', '_')}.json")

    fun save(ctx: Context, cfg: RepoConfig, files: List<Github.PostFile>) {
        val arr = JSONArray()
        for (f in files) {
            val o = JSONObject()
            o.put("path", f.path)
            o.put("name", f.name)
            o.put("sha", f.sha)
            o.put("date", f.date)
            o.put("title", f.title)
            o.put("categories", JSONArray(f.categories))
            o.put("tags", JSONArray(f.tags))
            o.put("images", JSONArray(f.images))
            arr.put(o)
        }
        try {
            cacheFile(ctx, cfg).writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context, cfg: RepoConfig): List<Github.PostFile>? {
        val f = cacheFile(ctx, cfg)
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Github.PostFile(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    sha = o.optString("sha"),
                    date = o.optString("date"),
                    title = o.optString("title"),
                    categories = jsonList(o.optJSONArray("categories")),
                    tags = jsonList(o.optJSONArray("tags")),
                    images = jsonList(o.optJSONArray("images")),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}
