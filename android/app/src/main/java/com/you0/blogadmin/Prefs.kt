package com.you0.blogadmin

import android.content.Context

object Prefs {
    private const val NAME = "blog_admin"

    fun load(ctx: Context): RepoConfig {
        val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return RepoConfig(
            token = sp.getString("token", "") ?: "",
            owner = sp.getString("owner", "You0") ?: "You0",
            repo = sp.getString("repo", "my-blog") ?: "my-blog",
            branch = sp.getString("branch", "main") ?: "main",
            postsDir = sp.getString("postsDir", "source/_posts") ?: "source/_posts",
            author = sp.getString("author", "You0") ?: "You0",
        )
    }

    fun save(ctx: Context, c: RepoConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("token", c.token)
            .putString("owner", c.owner)
            .putString("repo", c.repo)
            .putString("branch", c.branch)
            .putString("postsDir", c.postsDir)
            .putString("author", c.author)
            .apply()
    }
}
