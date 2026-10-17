package com.you0.blogadmin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var cfg: RepoConfig

    private lateinit var panelWrite: View
    private lateinit var panelDrafts: View
    private lateinit var panelPublished: View
    private lateinit var panelSettings: View

    private lateinit var navWrite: TextView
    private lateinit var navDrafts: TextView
    private lateinit var navPublished: TextView
    private lateinit var navSettings: TextView

    private lateinit var paperCard: View
    private lateinit var etTitle: EditText
    private lateinit var etTags: EditText
    private lateinit var etCategories: EditText
    private lateinit var etDate: EditText
    private lateinit var richEditor: WebView
    private lateinit var webPreview: WebView
    private lateinit var btnPreview: Button
    private lateinit var tvCount: TextView

    private lateinit var lvDrafts: ListView
    private lateinit var lvPosts: ListView

    private var previewOn = false
    private var drafts = emptyList<DraftStore.Draft>()
    private var posts = emptyList<Github.PostFile>()

    private var currentPath: String? = null
    private var currentSha: String? = null
    private var currentDraftId: String? = null
    private var currentAuthor: String = ""

    // 预览后点「编辑」时，直接复用已拉取的内容，不再重新请求
    private var pendingEditPath: String? = null
    private var pendingEditSha: String? = null
    private var pendingEditPost: PostData? = null

    private val REQ_PICK_IMAGE = 1001
    private val REQ_PREVIEW = 1002

    private val imageCache = HashMap<String, Bitmap>()
    private val imagePool = Executors.newFixedThreadPool(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = Prefs.load(this)
        currentAuthor = cfg.author
        bindViews()
        bindEvents()
        resetEditor()
        if (cfg.token.isBlank()) {
            showPanel("settings")
            fillSettings()
            toast("请先在设置里填写 GitHub Token")
        } else {
            showPanel("write")
        }
    }

    private fun bindViews() {
        panelWrite = findViewById(R.id.panel_write)
        panelDrafts = findViewById(R.id.panel_drafts)
        panelPublished = findViewById(R.id.panel_published)
        panelSettings = findViewById(R.id.panel_settings)

        navWrite = findViewById(R.id.nav_write)
        navDrafts = findViewById(R.id.nav_drafts)
        navPublished = findViewById(R.id.nav_published)
        navSettings = findViewById(R.id.nav_settings)

        paperCard = findViewById(R.id.paper_card)
        etTitle = findViewById(R.id.et_title)
        etTags = findViewById(R.id.et_tags)
        etCategories = findViewById(R.id.et_categories)
        etDate = findViewById(R.id.et_date)
        richEditor = findViewById(R.id.rich_editor)
        webPreview = findViewById(R.id.web_preview)
        btnPreview = findViewById(R.id.btn_preview)
        tvCount = findViewById(R.id.tv_count)

        lvDrafts = findViewById(R.id.lv_drafts)
        lvPosts = findViewById(R.id.lv_posts)

        richEditor.settings.javaScriptEnabled = true
        richEditor.settings.domStorageEnabled = true
        richEditor.isFocusableInTouchMode = true
        // 触摸正文时让 WebView 原生层先拿到焦点，contenteditable 才能弹出软键盘
        richEditor.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_UP) {
                if (!v.hasFocus()) {
                    v.requestFocus()
                    v.requestFocusFromTouch()
                }
            }
            false
        }
        richEditor.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
        }
        richEditor.addJavascriptInterface(object {
            @JavascriptInterface
            fun onCount(n: Int) {
                runOnUiThread { tvCount.text = "$n 字 · 自由记录" }
            }
        }, "ZhiJian")

        webPreview.settings.javaScriptEnabled = false
    }

    private fun bindEvents() {
        navWrite.setOnClickListener { showPanel("write") }
        navDrafts.setOnClickListener { showPanel("drafts"); loadDrafts() }
        navPublished.setOnClickListener { showPanel("published"); loadPosts() }
        navSettings.setOnClickListener { showPanel("settings"); fillSettings() }

        findViewById<Button>(R.id.btn_publish).setOnClickListener { publish() }
        findViewById<Button>(R.id.btn_new).setOnClickListener { resetEditor(); showPanel("write") }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadPosts() }
        btnPreview.setOnClickListener { togglePreview() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveDraft() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btn_save_settings).setOnClickListener { saveSettings() }

        findViewById<Button>(R.id.btn_bold).setOnClickListener { execJs("bold") }
        findViewById<Button>(R.id.btn_italic).setOnClickListener { execJs("italic") }
        findViewById<Button>(R.id.btn_strike).setOnClickListener { execJs("strikeThrough") }
        findViewById<Button>(R.id.btn_h2).setOnClickListener { execJs("formatBlock", "h2") }
        findViewById<Button>(R.id.btn_ul).setOnClickListener { execJs("insertUnorderedList") }
        findViewById<Button>(R.id.btn_ol).setOnClickListener { execJs("insertOrderedList") }
        findViewById<Button>(R.id.btn_quote).setOnClickListener { execJs("formatBlock", "blockquote") }
        findViewById<Button>(R.id.btn_code).setOnClickListener { execJs("formatBlock", "pre") }
        findViewById<Button>(R.id.btn_link).setOnClickListener { promptLink() }
        findViewById<Button>(R.id.btn_image).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.btn_undo).setOnClickListener { execJs("undo") }
        findViewById<Button>(R.id.btn_redo).setOnClickListener { execJs("redo") }

        lvDrafts.setOnItemClickListener { _, _, pos, _ ->
            val d = drafts[pos]
            currentDraftId = d.id
            currentPath = null
            currentSha = null
            currentAuthor = cfg.author
            etTitle.setText(d.title)
            etTags.setText(d.tags)
            etCategories.setText(d.categories)
            etDate.setText(d.date)
            loadEditor(Markdown.render(d.body))
            showPanel("write")
        }
        lvDrafts.setOnItemLongClickListener { _, _, pos, _ ->
            val d = drafts[pos]
            AlertDialog.Builder(this)
                .setTitle("删除草稿")
                .setMessage("确定删除「${d.title}」吗？")
                .setPositiveButton("删除") { _, _ -> DraftStore.delete(this, d.id); loadDrafts() }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        lvPosts.setOnItemClickListener { _, _, pos, _ -> previewPost(posts[pos]) }
        lvPosts.setOnItemLongClickListener { _, _, pos, _ ->
            val f = posts[pos]
            AlertDialog.Builder(this)
                .setTitle("删除文章")
                .setMessage("确定删除「${if (f.title.isNotBlank()) f.title else f.name}」吗？会从博客里移除。")
                .setPositiveButton("删除") { _, _ -> deletePost(f) }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    private fun showPanel(name: String) {
        // 切回「写文章」时如果还停在只读预览，先回到编辑器，避免看到的是预览而非编辑框
        if (name == "write" && previewOn) setPreview(false)
        panelWrite.visibility = if (name == "write") View.VISIBLE else View.GONE
        panelDrafts.visibility = if (name == "drafts") View.VISIBLE else View.GONE
        panelPublished.visibility = if (name == "published") View.VISIBLE else View.GONE
        panelSettings.visibility = if (name == "settings") View.VISIBLE else View.GONE
        setNav(navWrite, name == "write")
        setNav(navDrafts, name == "drafts")
        setNav(navPublished, name == "published")
        setNav(navSettings, name == "settings")
    }

    private fun setNav(v: TextView, active: Boolean) {
        v.setTextColor(getColor(if (active) R.color.nav_active_text else R.color.nav_text))
        v.setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        v.setBackgroundResource(if (active) R.drawable.bg_nav_active else 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun baseUrl(): String = "https://${cfg.owner.lowercase()}.github.io/"

    private fun loadEditor(bodyHtml: String) {
        val page = readAsset("editor.html").replace("{{CONTENT}}", bodyHtml)
        richEditor.loadDataWithBaseURL(baseUrl(), page, "text/html", "UTF-8", null)
        // 页面加载完成后再请求焦点，确保 WebView 原生焦点与 contenteditable 同步
        richEditor.post { richEditor.requestFocus() }
    }

    private fun jsResult(v: String?): String =
        try {
            val o = JSONTokener(v ?: "").nextValue()
            if (o == JSONObject.NULL) "" else o.toString()
        } catch (_: Exception) { "" }

    private fun execJs(cmd: String, value: String? = null) {
        val expr = if (value == null) "exec('$cmd')"
        else "exec('$cmd', ${JSONObject.quote(value)})"
        richEditor.evaluateJavascript(expr, null)
    }

    // 取出编辑器内容 → Markdown（并还原图片相对路径）
    private fun getMarkdown(cb: (String) -> Unit) {
        richEditor.evaluateJavascript("htmlToMarkdown()") { v ->
            val md = jsResult(v)
            val rawPrefix = "https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/source/images/"
            cb(md.replace(rawPrefix, "/images/"))
        }
    }

    private fun resetEditor() {
        etTitle.setText("")
        etTags.setText("")
        etCategories.setText("")
        etDate.setText(nowString())
        loadEditor("")
        currentPath = null
        currentSha = null
        currentDraftId = null
        currentAuthor = cfg.author
        if (previewOn) togglePreview()
    }

    private fun togglePreview() {
        setPreview(!previewOn)
    }

    private fun setPreview(on: Boolean) {
        previewOn = on
        paperCard.visibility = if (on) View.GONE else View.VISIBLE
        webPreview.visibility = if (on) View.VISIBLE else View.GONE
        btnPreview.text = if (on) "编辑" else "预览"
        if (on) renderPreview()
    }

    private fun renderPreview() {
        getMarkdown { md ->
            val body = Markdown.render(md)
            val html = """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  body{font-family:sans-serif;padding:16px;color:#50564b;background:#fffefa;line-height:1.9;word-wrap:break-word;}
                  h1,h2,h3,h4{color:#292d28;line-height:1.4;}
                  h2{border-bottom:1px solid #e4e5dc;padding-bottom:.3em;}
                  code{background:#f0f2ea;padding:2px 5px;border-radius:4px;color:#47523e;}
                  pre{background:#354b39;color:#f4f3ef;padding:12px;border-radius:8px;overflow-x:auto;}
                  pre code{background:none;color:inherit;padding:0;}
                  blockquote{border-left:4px solid #789479;margin:.6em 0;padding:2px 12px;color:#697563;background:#f0f2ea;}
                  img{max-width:100%;border-radius:10px;} a{color:#789479;}
                </style></head><body>$body</body></html>
            """.trimIndent()
            webPreview.loadDataWithBaseURL(baseUrl(), html, "text/html", "UTF-8", null)
        }
    }

    private fun readPostData(body: String): PostData = PostData(
        title = etTitle.text.toString().trim(),
        tags = Post.splitList(etTags.text.toString()),
        categories = Post.splitList(etCategories.text.toString()),
        date = etDate.text.toString().trim(),
        author = currentAuthor,
        body = body,
    )

    private fun saveDraft() {
        getMarkdown { body ->
            val p = readPostData(body)
            val id = currentDraftId ?: ("d" + System.currentTimeMillis())
            DraftStore.save(
                this,
                DraftStore.Draft(
                    id = id,
                    title = p.title.ifBlank { "未命名" },
                    tags = etTags.text.toString(),
                    categories = etCategories.text.toString(),
                    date = p.date,
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            currentDraftId = id
            toast("已保存草稿")
        }
    }

    private fun loadDrafts() {
        drafts = DraftStore.list(this)
        lvDrafts.adapter = if (drafts.isEmpty()) {
            CardAdapter(listOf("还没有草稿"), listOf(""), listOf(""), listOf("开始记录第一个念头吧。"))
        } else {
            CardAdapter(
                drafts.map { it.title },
                drafts.map { "" },
                drafts.map { "" },
                drafts.map { (if (it.date.isBlank()) "未保存日期" else it.date) + " · 点击继续编辑" },
            )
        }
    }

    private fun loadPosts() {
        // 先展示本地缓存，避免每次打开都干等仓库
        val cached = PostCache.load(this, cfg)
        if (cached != null) {
            posts = cached
            renderPosts(cached)
        } else {
            posts = emptyList()
            lvPosts.adapter = CardAdapter(listOf("正在加载…"), listOf(""), listOf(""), listOf(""))
            toast("加载中…")
        }
        // 后台拉取最新，成功后刷新并更新缓存（只重读 sha 变了的文章）
        io(
            task = { Github.listPosts(cfg, cached ?: emptyList()) },
            done = { files ->
                posts = files
                PostCache.save(this, cfg, files)
                renderPosts(files)
            },
            fail = { if (cached == null) toast(it) }
        )
    }

    private fun renderPosts(files: List<Github.PostFile>) {
        lvPosts.adapter = if (files.isEmpty()) {
            CardAdapter(listOf("还没有文章"), listOf(""), listOf(""), listOf("开始写下第一个故事吧。"))
        } else {
            CardAdapter(
                files.map { if (it.title.isNotBlank()) it.title else it.name.removeSuffix(".md").substringAfterLast('/') },
                files.map { slashDate(it.date) },
                files.map { it.categories.firstOrNull() ?: "" },
                files.map { (it.categories.firstOrNull() ?: "文章") + "　·　点击阅读 ↗" },
                files.map { it.images },
                onImageClick = { pos -> if (pos in posts.indices) previewPost(posts[pos]) },
            )
        }
    }

    private fun slashDate(d: String): String {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(d) ?: return ""
        return "${m.groupValues[1]} / ${m.groupValues[2]} / ${m.groupValues[3]}"
    }

    // 点开已发布文章 → 打开独立预览 Activity；点「编辑」再进编辑器
    private fun previewPost(file: Github.PostFile) {
        toast("加载中…")
        io(
            task = { Github.getFile(cfg, file.path) },
            done = { fc ->
                val p = Post.parse(fc.text)
                pendingEditPath = file.path
                pendingEditSha = fc.sha
                pendingEditPost = p
                val intent = Intent(this, PreviewActivity::class.java).apply {
                    putExtra("html", postPreviewHtml(p))
                    putExtra("baseUrl", baseUrl())
                }
                startActivityForResult(intent, REQ_PREVIEW)
            },
            fail = { toast(it) }
        )
    }

    private fun postPreviewHtml(p: PostData): String {
        val body = Markdown.render(p.body)
        val meta = listOfNotNull(
            if (p.date.isNotBlank()) p.date else null,
            if (p.tags.isNotEmpty()) p.tags.joinToString(" · ") else null,
            if (p.categories.isNotEmpty()) p.categories.joinToString(" · ") else null,
        ).joinToString("　·　")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:sans-serif;padding:24px 22px 48px;color:#50564b;background:#fffefa;line-height:1.9;word-wrap:break-word;}
              h1{font-size:24px;color:#292d28;line-height:1.4;margin:0 0 10px;}
              .meta{font-size:12px;color:#92968a;letter-spacing:.5px;margin-bottom:22px;padding-bottom:16px;border-bottom:1px solid #eff0e8;}
              h2,h3,h4{color:#292d28;line-height:1.4;}
              h2{border-bottom:1px solid #e4e5dc;padding-bottom:.3em;}
              code{background:#f0f2ea;padding:2px 5px;border-radius:4px;color:#47523e;}
              pre{background:#354b39;color:#f4f3ef;padding:12px;border-radius:8px;overflow-x:auto;}
              pre code{background:none;color:inherit;padding:0;}
              blockquote{border-left:4px solid #789479;margin:.6em 0;padding:2px 12px;color:#697563;background:#f0f2ea;}
              img{max-width:100%;border-radius:10px;} a{color:#789479;}
            </style></head><body>
            <h1>${esc(p.title.ifBlank { "未命名" })}</h1>
            ${if (meta.isNotBlank()) "<div class=\"meta\">${esc(meta)}</div>" else ""}
            $body
            </body></html>
        """.trimIndent()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun deletePost(file: Github.PostFile) {
        toast("删除中…")
        io(
            task = { Github.deleteFile(cfg, file.path, file.sha, "delete: ${file.name}") },
            done = { toast("已删除"); loadPosts() },
            fail = { toast(it) }
        )
    }

    private fun publish() {
        getMarkdown { body ->
            val p = readPostData(body)
            if (p.title.isBlank()) { toast("请填写标题"); return@getMarkdown }
            val content = Post.build(p)

            var path = currentPath
            var sha = currentSha
            if (path == null) {
                val base = Post.sanitize(p.title).ifBlank { "untitled" }
                val datePrefix = Regex("""^\d{4}-\d{2}-\d{2}""").find(p.date)?.value
                val fileBase = if (datePrefix != null) "$datePrefix-$base" else base
                path = "${cfg.postsDir}/$fileBase.md"
                val exists = try { Github.getFile(cfg, path); true } catch (_: Exception) { false }
                if (exists) path = "${cfg.postsDir}/$fileBase-${System.currentTimeMillis()}.md"
            }
            val finalPath = path

            toast("发布中…")
            io(
                task = { Github.putFile(cfg, finalPath, content, sha, "publish: ${p.title}") },
                done = {
                    currentDraftId?.let { DraftStore.delete(this, it) }
                    toast("发布成功，约 1 分钟后博客自动更新")
                    resetEditor()
                    loadPosts()
                },
                fail = { toast(it) }
            )
        }
    }

    // ---- 图片 ----

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE)
        } catch (e: Exception) {
            toast("无法打开相册")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            insertPickedImage(data.data!!)
        }
        if (requestCode == REQ_PREVIEW && resultCode == RESULT_OK) {
            editPreviewedPost()
        }
    }

    // 预览页点「编辑」→ 把已拉取的内容填入编辑器并切入写文章
    private fun editPreviewedPost() {
        val p = pendingEditPost ?: return
        val path = pendingEditPath ?: return
        etTitle.setText(p.title)
        etTags.setText(p.tags.joinToString(", "))
        etCategories.setText(p.categories.joinToString(", "))
        etDate.setText(p.date)
        loadEditor(Markdown.render(p.body))
        currentPath = path
        currentSha = pendingEditSha
        currentDraftId = null
        currentAuthor = p.author.ifBlank { cfg.author }
        showPanel("write")
    }

    private fun insertPickedImage(uri: Uri) {
        toast("上传图片…")
        io(
            task = {
                val bytes = readAndCompress(uri)
                val slug = Post.sanitize(etTitle.text.toString()).ifBlank { "uploads" }
                val name = System.currentTimeMillis().toString() + ".jpg"
                val repoPath = "source/images/$slug/$name"
                Github.putImage(cfg, repoPath, bytes)
                "https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/$repoPath"
            },
            done = { url ->
                richEditor.evaluateJavascript("insertImage(${JSONObject.quote(url)})", null)
                toast("图片已插入")
            },
            fail = { toast(it) }
        )
    }

    private fun readAndCompress(uri: Uri): ByteArray {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法读取图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw Exception("图片格式不支持")
        var sample = 1
        val max = 1600
        while (w / sample > max || h / sample > max) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Exception("图片解码失败")
        val exif = android.media.ExifInterface(ByteArrayInputStream(raw))
        bmp = when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bmp, 90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bmp, 180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bmp, 270f)
            else -> bmp
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (!bmp.isRecycled) bmp.recycle()
        return out.toByteArray()
    }

    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val m = Matrix().apply { postRotate(deg) }
        val b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (b != src && !src.isRecycled) src.recycle()
        return b
    }

    private fun promptLink() {
        val input = EditText(this)
        input.hint = "https://…"
        input.setText("https://")
        AlertDialog.Builder(this)
            .setTitle("插入链接")
            .setView(input)
            .setPositiveButton("确定") { _, _ -> execJs("createLink", input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 设置 ----

    private fun fillSettings() {
        findViewById<EditText>(R.id.et_token).setText(cfg.token)
        findViewById<EditText>(R.id.et_owner).setText(cfg.owner)
        findViewById<EditText>(R.id.et_repo).setText(cfg.repo)
        findViewById<EditText>(R.id.et_branch).setText(cfg.branch)
        findViewById<EditText>(R.id.et_posts_dir).setText(cfg.postsDir)
        findViewById<EditText>(R.id.et_author).setText(cfg.author)
    }

    private fun readSettingsForm() {
        cfg.token = findViewById<EditText>(R.id.et_token).text.toString().trim()
        cfg.owner = findViewById<EditText>(R.id.et_owner).text.toString().trim().ifBlank { "You0" }
        cfg.repo = findViewById<EditText>(R.id.et_repo).text.toString().trim().ifBlank { "my-blog" }
        cfg.branch = findViewById<EditText>(R.id.et_branch).text.toString().trim().ifBlank { "main" }
        cfg.postsDir = findViewById<EditText>(R.id.et_posts_dir).text.toString().trim().ifBlank { "source/_posts" }
        cfg.author = findViewById<EditText>(R.id.et_author).text.toString().trim()
    }

    private fun saveSettings() {
        readSettingsForm()
        Prefs.save(this, cfg)
        currentAuthor = cfg.author
        toast("设置已保存")
    }

    private fun testConnection() {
        readSettingsForm()
        Prefs.save(this, cfg)
        toast("测试中…")
        io(
            task = { Github.testConnection(cfg) },
            done = { toast("连接成功：$it") },
            fail = { toast(it) }
        )
    }

    private fun <T> io(task: () -> T, done: (T) -> Unit, fail: (String) -> Unit) {
        Thread {
            try {
                val r = task()
                runOnUiThread { done(r) }
            } catch (e: Exception) {
                val msg = e.message ?: "出错了"
                runOnUiThread { fail(msg) }
            }
        }.start()
    }

    // 文章卡片适配器（已发布 / 草稿共用）
    private inner class CardAdapter(
        private val titles: List<String>,
        private val dates: List<String>,
        private val badges: List<String>,
        private val descs: List<String>,
        private val images: List<List<String>> = emptyList(),
        private val onImageClick: ((Int) -> Unit)? = null,
    ) : BaseAdapter() {
        override fun getCount() = titles.size
        override fun getItem(position: Int) = titles[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_article, parent, false)
            val d = dates[position]
            val b = badges[position]

            val dateTv = v.findViewById<TextView>(R.id.tv_date)
            dateTv.visibility = if (d.isNotBlank()) View.VISIBLE else View.GONE
            dateTv.text = d

            val badgeTv = v.findViewById<TextView>(R.id.tv_badge)
            badgeTv.visibility = if (b.isNotBlank()) View.VISIBLE else View.GONE
            badgeTv.text = b

            v.findViewById<TextView>(R.id.tv_title).text = titles[position]
            v.findViewById<TextView>(R.id.tv_desc).text = descs[position]

            // 图片：一行可左右滑动的小图
            val scroll = v.findViewById<HorizontalScrollView>(R.id.scroll_imgs)
            val row = v.findViewById<LinearLayout>(R.id.img_row)
            row.removeAllViews()
            val urls = if (position < images.size) images[position] else emptyList()
            if (urls.isNotEmpty()) {
                scroll.visibility = View.VISIBLE
                for (u in urls) {
                    val iv = ImageView(this@MainActivity)
                    val lp = LinearLayout.LayoutParams(dp(96), dp(96))
                    lp.marginEnd = dp(8)
                    iv.layoutParams = lp
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundResource(R.drawable.bg_thumb)
                    iv.clipToOutline = true
                    iv.isClickable = true
                    iv.isFocusable = false
                    if (onImageClick != null) iv.setOnClickListener { onImageClick(position) }
                    row.addView(iv)
                    loadImage(iv, resolveImage(u))
                }
            } else {
                scroll.visibility = View.GONE
            }
            return v
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // 相对路径（/images/…）或 raw.githubusercontent 链接 → 可加载的绝对 URL
    private fun resolveImage(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return baseUrl().trimEnd('/') + "/" + url.trimStart('/')
    }

    // 异步加载缩略图：内存缓存 → 磁盘缓存 → 网络下载（下载后落盘），视图复用防串位
    private fun loadImage(view: ImageView, url: String) {
        view.tag = url
        view.setImageDrawable(null)
        imageCache[url]?.let {
            view.setImageBitmap(it)
            return
        }
        imagePool.execute {
            var bmp = imageCache[url] ?: readDiskImage(url)
            if (bmp == null) {
                bmp = downloadBitmap(url)
                if (bmp != null) writeDiskImage(url, bmp)
            }
            if (bmp != null) synchronized(imageCache) { imageCache[url] = bmp }
            val result = bmp
            runOnUiThread {
                if (view.tag == url) {
                    if (result != null) view.setImageBitmap(result) else view.visibility = View.GONE
                }
            }
        }
    }

    private fun imageFile(url: String): File {
        val key = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "imgs"), key)
    }

    private fun readDiskImage(url: String): Bitmap? {
        val f = imageFile(url)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun writeDiskImage(url: String, bmp: Bitmap) {
        try {
            val f = imageFile(url)
            f.parentFile?.mkdirs()
            f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        } catch (_: Exception) {
        }
    }

    private fun downloadBitmap(url: String): Bitmap? =
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
}
