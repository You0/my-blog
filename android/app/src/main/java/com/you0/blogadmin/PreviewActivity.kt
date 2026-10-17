package com.you0.blogadmin

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button

// 已发布文章的只读预览页：返回键 / 「返回」回列表，「编辑」回 MainActivity 进入编辑器
class PreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val html = intent.getStringExtra("html") ?: ""
        val baseUrl = intent.getStringExtra("baseUrl") ?: "https://you0.github.io/"

        val web = findViewById<WebView>(R.id.web_view)
        (web.parent as View).clipToOutline = true
        web.settings.javaScriptEnabled = false
        web.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_edit).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }
}
