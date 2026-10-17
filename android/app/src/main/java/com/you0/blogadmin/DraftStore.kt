package com.you0.blogadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DraftStore {

    data class Draft(
        val id: String,
        val title: String,
        val tags: String,
        val categories: String,
        val date: String,
        val body: String,
        val updatedAt: Long,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "drafts.json")

    fun list(ctx: Context): List<Draft> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = mutableListOf<Draft>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Draft(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    tags = o.optString("tags"),
                    categories = o.optString("categories"),
                    date = o.optString("date"),
                    body = o.optString("body"),
                    updatedAt = o.optLong("updatedAt"),
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun save(ctx: Context, d: Draft) {
        val f = file(ctx)
        val arr = if (f.exists()) JSONArray(f.readText()) else JSONArray()
        var idx = -1
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") == d.id) { idx = i; break }
        }
        val o = JSONObject().apply {
            put("id", d.id)
            put("title", d.title)
            put("tags", d.tags)
            put("categories", d.categories)
            put("date", d.date)
            put("body", d.body)
            put("updatedAt", d.updatedAt)
        }
        if (idx >= 0) arr.put(idx, o) else arr.put(o)
        f.writeText(arr.toString())
    }

    fun delete(ctx: Context, id: String) {
        val f = file(ctx)
        if (!f.exists()) return
        val arr = JSONArray(f.readText())
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) keep.put(o)
        }
        f.writeText(keep.toString())
    }
}
