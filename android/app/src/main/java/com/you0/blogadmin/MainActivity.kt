package com.you0.blogadmin

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.content.res.ColorStateList
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.text.method.PasswordTransformationMethod
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
import android.widget.ImageButton
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
import java.util.Calendar
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

    private var activePanel = "write"
    private var focused = false
    private var keyboardVisible = false
    private var editingBody = false
    private var editorLoading = true
    private var dirty = false
    private var latestMarkdown = ""
    private var publishing = false
    private var loadingPosts = false
    private var visibleDrafts = emptyList<DraftStore.Draft>()
    private var visiblePosts = emptyList<Github.PostFile>()
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { if (dirty) saveDraft(false) }
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
        val resumeId = getSharedPreferences("writing_session", MODE_PRIVATE).getString("draft", null)
        resetEditor()
        // Writing works offline; connecting a repository is only needed for publishing.
        DraftStore.list(this).firstOrNull { it.id == resumeId }?.let { openDraft(it) }
        showPanel("write")
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
        (paperCard.parent as View).clipToOutline = true
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
                editingBody = true
                if (!v.hasFocus()) {
                    v.requestFocus()
                    v.requestFocusFromTouch()
                }
            }
            false
        }
        richEditor.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                editorLoading = false
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
        }
        richEditor.addJavascriptInterface(object {
            @JavascriptInterface
            fun onCount(n: Int) {
                runOnUiThread { tvCount.text = "$n 字 · 预计阅读 ${maxOf(1, (n + 399) / 400)} 分钟" }
            }
            @JavascriptInterface
            fun onContent(markdown: String, count: Int) {
                runOnUiThread {
                    val rawPrefix = "https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/source/images/"
                    latestMarkdown = markdown.replace(rawPrefix, "/images/")
                    tvCount.text = "$count 字 · 预计阅读 ${maxOf(1, (count + 399) / 400)} 分钟"
                    if (!editorLoading) markDirty()
                }
            }
        }, "ZhiJian")

        webPreview.settings.javaScriptEnabled = false
        val root = findViewById<View>(R.id.app_root)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val frame = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(frame)
            val visible = root.rootView.height - frame.bottom > dp(180)
            if (keyboardVisible != visible) {
                keyboardVisible = visible
                updateChrome()
            }
        }
    }

    private fun bindEvents() {
        listOf(etTitle, etTags, etCategories, etDate).forEach { field ->
            field.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) editingBody = false }
            field.addTextChangedListener(watcher { if (!editorLoading) markDirty() })
        }
        findViewById<EditText>(R.id.search_drafts).addTextChangedListener(watcher { renderDrafts() })
        findViewById<EditText>(R.id.search_posts).addTextChangedListener(watcher { renderPosts(posts) })
        listOf(R.id.search_drafts, R.id.search_posts).forEach { id ->
            findViewById<EditText>(id).setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        etDate.keyListener = null
        etDate.isFocusable = false
        etDate.contentDescription = "选择文章发布日期与时间"
        etDate.setOnClickListener { pickDate() }
        findViewById<View>(R.id.btn_quick_new).setOnClickListener { newArticle() }
        findViewById<View>(R.id.btn_edit_mode).setOnClickListener { setPreview(false) }
        findViewById<View>(R.id.btn_metadata).setOnClickListener { showMetadata() }
        findViewById<View>(R.id.btn_focus).setOnClickListener { setFocused(!focused) }
        findViewById<View>(R.id.empty_drafts_action).setOnClickListener { newArticle() }
        findViewById<View>(R.id.empty_published_action).setOnClickListener {
            if (cfg.token.isBlank()) { showPanel("settings"); fillSettings() } else newArticle()
        }
        findViewById<Button>(R.id.btn_token_visibility).setOnClickListener { v ->
            val field = findViewById<EditText>(R.id.et_token)
            val showing = field.transformationMethod == null
            field.transformationMethod = if (showing) PasswordTransformationMethod.getInstance() else null
            (v as Button).text = if (showing) "显示" else "隐藏"
            field.setSelection(field.text.length)
        }
        navWrite.setOnClickListener { showPanel("write") }
        navDrafts.setOnClickListener { showPanel("drafts"); loadDrafts() }
        navPublished.setOnClickListener { showPanel("published"); loadPosts() }
        navSettings.setOnClickListener { showPanel("settings"); fillSettings() }

        findViewById<Button>(R.id.btn_publish).setOnClickListener { confirmPublish() }
        findViewById<Button>(R.id.btn_new).setOnClickListener { newArticle() }
        findViewById<View>(R.id.btn_refresh).setOnClickListener { loadPosts() }
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
            visibleDrafts.getOrNull(pos)?.let { d ->
                if (dirty) saveDraft(false)
                openDraft(d)
                showPanel("write")
            }
        }
        lvDrafts.setOnItemLongClickListener { _, _, pos, _ ->
            visibleDrafts.getOrNull(pos)?.let { d ->
                AlertDialog.Builder(this)
                    .setTitle("删除草稿？")
                    .setMessage("「${d.title}」将从这台设备中删除。")
                    .setPositiveButton("删除") { _, _ ->
                        DraftStore.delete(this, d.id)
                        if (currentDraftId == d.id) resetEditor()
                        loadDrafts()
                    }
                    .setNegativeButton("保留", null).show()
            }
            true
        }
        lvPosts.setOnItemClickListener { _, _, pos, _ -> visiblePosts.getOrNull(pos)?.let { previewPost(it) } }
        lvPosts.setOnItemLongClickListener { _, _, pos, _ ->
            val f = visiblePosts.getOrNull(pos) ?: return@setOnItemLongClickListener true
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
        if (publishing && name != activePanel) { toast("文章正在发布，请稍候"); return }
        if (name != "write" && dirty) saveDraft(false)
        if (focused && name != "write") setFocused(false)
        if (name != "write") hideKeyboard()
        activePanel = name
        findViewById<TextView>(R.id.page_title).text = when (name) {
            "drafts" -> "草稿"
            "published" -> "文章"
            "settings" -> "设置"
            else -> "写作"
        }
        mapOf(R.id.btn_quick_new to "write", R.id.btn_publish to "write", R.id.btn_new to "drafts", R.id.btn_refresh to "published", R.id.btn_save_settings to "settings").forEach { (id, panel) ->
            findViewById<View>(id).visibility = if (name == panel) View.VISIBLE else View.GONE
        }
        updateChrome()
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
        listOf(panelWrite, panelDrafts, panelPublished, panelSettings).firstOrNull { it.visibility == View.VISIBLE }?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(180).start()
        }
    }

    private fun setNav(v: TextView, active: Boolean) {
        v.setTextColor(getColor(if (active) R.color.nav_active_text else R.color.nav_text))
        v.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        v.compoundDrawableTintList = ColorStateList.valueOf(getColor(if (active) R.color.primary else R.color.nav_text))
        v.isSelected = active
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun baseUrl(): String = "https://${cfg.owner.lowercase()}.github.io/"

    private fun loadEditor(bodyHtml: String, markdown: String = "") {
        editorLoading = true
        latestMarkdown = markdown
        dirty = false
        handler.removeCallbacks(autosave)
        findViewById<TextView>(R.id.tv_save_status).text = if (currentDraftId == null) "本地草稿" else "已保存到本机"
        val page = readAsset("editor.html").replace("{{CONTENT}}", bodyHtml)
        richEditor.loadDataWithBaseURL(baseUrl(), page, "text/html", "UTF-8", null)

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
        editorLoading = true
        currentPath = null
        currentSha = null
        currentDraftId = null
        etTitle.setText("")
        etTags.setText("")
        etCategories.setText("")
        etDate.setText(nowString())
        loadEditor("")
        currentPath = null
        currentSha = null
        currentDraftId = null
        currentAuthor = cfg.author
        if (previewOn) setPreview(false)
        findViewById<View>(R.id.metadata_fields).visibility = View.GONE
        findViewById<Button>(R.id.btn_metadata).text = "＋ 添加标签、分类与日期"
        getSharedPreferences("writing_session", MODE_PRIVATE).edit().remove("draft").apply()
    }

    private fun togglePreview() {
        setPreview(true)
    }

    private fun setPreview(on: Boolean) {
        previewOn = on
        paperCard.visibility = if (on) View.GONE else View.VISIBLE
        webPreview.visibility = if (on) View.VISIBLE else View.GONE
        hideKeyboard()
        val editMode = findViewById<Button>(R.id.btn_edit_mode)
        editMode.setBackgroundResource(if (on) android.R.color.transparent else R.drawable.bg_btn_secondary)
        btnPreview.setBackgroundResource(if (on) R.drawable.bg_btn_secondary else android.R.color.transparent)
        btnPreview.setTextColor(getColor(if (on) R.color.primary else R.color.text_secondary))
        editMode.setTextColor(getColor(if (on) R.color.text_secondary else R.color.primary))
        if (on) renderPreview()
    }

    private fun renderPreview() {
        getMarkdown { md -> webPreview.loadDataWithBaseURL(baseUrl(), postPreviewHtml(readPostData(md)), "text/html", "UTF-8", null) }
    }

    private fun readPostData(body: String): PostData = PostData(
        title = etTitle.text.toString().trim(),
        tags = Post.splitList(etTags.text.toString()),
        categories = Post.splitList(etCategories.text.toString()),
        date = etDate.text.toString().trim(),
        author = currentAuthor,
        body = body,
    )

    private fun watcher(changed: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun markDirty() {
        dirty = true
        findViewById<TextView>(R.id.tv_save_status).text = "正在保存…"
        handler.removeCallbacks(autosave)
        handler.postDelayed(autosave, 1200)
    }

    private fun saveDraft(notify: Boolean = true) {
        handler.removeCallbacks(autosave)
        if (currentDraftId == null && latestMarkdown.isBlank() && etTitle.text.isBlank() && etTags.text.isBlank() && etCategories.text.isBlank()) {
            dirty = false
            findViewById<TextView>(R.id.tv_save_status).text = "本地草稿"
            if (notify) toast("写下标题或正文，再保存你的灵感")
            return
        }
        val id = currentDraftId ?: ("d" + System.currentTimeMillis())
        DraftStore.save(this, DraftStore.Draft(
            id = id, title = etTitle.text.toString().trim().ifBlank { "未命名文章" },
            tags = etTags.text.toString(), categories = etCategories.text.toString(),
            date = etDate.text.toString(), body = latestMarkdown, updatedAt = System.currentTimeMillis(),
            path = currentPath, sha = currentSha, author = currentAuthor,
        ))
        currentDraftId = id
        dirty = false
        getSharedPreferences("writing_session", MODE_PRIVATE).edit().putString("draft", id).apply()
        findViewById<TextView>(R.id.tv_save_status).text = "已保存到本机"
        if (notify) toast("草稿已保存")
    }

    private fun openDraft(d: DraftStore.Draft) {
        editorLoading = true
        currentDraftId = d.id
        getSharedPreferences("writing_session", MODE_PRIVATE).edit().putString("draft", d.id).apply()
        currentPath = d.path
        currentSha = d.sha
        currentAuthor = d.author.ifBlank { cfg.author }
        etTitle.setText(d.title)
        etTags.setText(d.tags)
        etCategories.setText(d.categories)
        etDate.setText(d.date)
        loadEditor(Markdown.render(d.body), d.body)
        if (previewOn) setPreview(false)
    }

    private fun showMetadata() {
        hideKeyboard()
        val form = layoutInflater.inflate(R.layout.dialog_article_info, null)
        val tags = form.findViewById<EditText>(R.id.meta_tags)
        val categories = form.findViewById<EditText>(R.id.meta_categories)
        val date = form.findViewById<EditText>(R.id.meta_date)
        tags.setText(etTags.text)
        categories.setText(etCategories.text)
        date.setText(etDate.text)
        date.keyListener = null
        date.setOnClickListener { pickDate(date) }
        val dialog = AlertDialog.Builder(this).setTitle("文章信息").setView(form)
            .setPositiveButton("完成") { _, _ ->
                etTags.setText(tags.text)
                etCategories.setText(categories.text)
                etDate.setText(date.text)
                val summary = listOf(tags.text.toString(), categories.text.toString()).filter { it.isNotBlank() }.joinToString(" · ")
                findViewById<Button>(R.id.btn_metadata).text = summary.ifBlank { "＋ 添加标签、分类与日期" }
            }
            .setNegativeButton("取消", null).create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun pickDate(target: EditText = etDate) {
        val calendar = Calendar.getInstance()
        try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
                .parse(target.text.toString())?.let { calendar.time = it }
        } catch (_: Exception) { }
        DatePickerDialog(this, { _, year, month, day ->
            calendar.set(year, month, day)
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                target.setText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(calendar.time))
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun newArticle() {
        if (publishing) { toast("正在发布，请稍候"); return }
        if (dirty) saveDraft(false)
        resetEditor()
        showPanel("write")
        toast("新的一页，开始记录吧")
    }

    private fun setFocused(on: Boolean) {
        focused = on
        updateChrome()
        findViewById<View>(R.id.btn_focus).apply {
            contentDescription = if (on) "退出专注模式" else "进入专注模式"
            alpha = if (on) 0.5f else 1f
        }
    }

    private fun updateChrome() {
        val restoreBody = keyboardVisible && editingBody && activePanel == "write" && !previewOn
        listOf(R.id.brand_header, R.id.bottom_navigation).forEach {
            findViewById<View>(it).visibility = if (focused || keyboardVisible) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.editor_intro).visibility = if (restoreBody) View.GONE else View.VISIBLE
        // A WebView may drop its native input connection when its ancestors resize.
        if (restoreBody) richEditor.post {
            richEditor.requestFocus()
            richEditor.evaluateJavascript("focusEditor()", null)
        }
    }

    private fun hideKeyboard() {
        editingBody = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etTitle.windowToken, 0)
        currentFocus?.clearFocus()
    }

    override fun onPause() {
        if (dirty) saveDraft(false)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            focused -> setFocused(false)
            previewOn && activePanel == "write" -> setPreview(false)
            activePanel != "write" -> showPanel("write")
            else -> super.onBackPressed()
        }
    }

    private fun loadDrafts() {
        drafts = DraftStore.list(this)
        renderDrafts()
    }

    private fun renderDrafts() {
        val query = findViewById<EditText>(R.id.search_drafts).text.toString().trim()
        visibleDrafts = drafts.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        findViewById<TextView>(R.id.tv_draft_total).text = "${drafts.size}  篇草稿"
        lvDrafts.adapter = CardAdapter(
            visibleDrafts.map { it.title },
            visibleDrafts.map { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(it.updatedAt)) },
            visibleDrafts.map { "草稿" },
            visibleDrafts.map { it.body.replace(Regex("[#*>`\\[\\]]"), "").replace("\n", " ").take(80).ifBlank { "还没有正文 · 点击继续写作" } },
        )
        findViewById<View>(R.id.empty_drafts).visibility = if (visibleDrafts.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.empty_drafts_title).text = if (query.isBlank()) "让想法有个落脚点" else "没有找到相关草稿"
        findViewById<TextView>(R.id.empty_drafts_desc).text = if (query.isBlank()) "写下片刻灵感，草稿会保存在这台设备。" else "试试其他关键词，或开始新的故事。"
    }

    private fun loadPosts() {
        if (loadingPosts) return
        if (cfg.token.isBlank()) {
            posts = emptyList()
            renderPosts(posts)
            findViewById<TextView>(R.id.empty_published_title).text = "连接你的发布空间"
            findViewById<TextView>(R.id.empty_published_desc).text = "在工作台连接 GitHub，即可同步和发布文章。"
            findViewById<Button>(R.id.empty_published_action).text = "连接 GitHub"
            return
        }
        val cached = PostCache.load(this, cfg)
        posts = cached ?: emptyList()
        renderPosts(posts)
        loadingPosts = true
        val refresh = findViewById<ImageButton>(R.id.btn_refresh)
        val status = findViewById<TextView>(R.id.tv_posts_status)
        refresh.isEnabled = false
        refresh.contentDescription = "正在同步文章"
        refresh.alpha = 0.45f
        status.visibility = View.VISIBLE
        status.text = if (cached == null) "正在从仓库获取文章…" else "已显示本地内容，正在同步最新文章…"
        val requestConfig = cfg.copy()
        io(
            task = { Github.listPosts(requestConfig, cached ?: emptyList()) },
            done = { files ->
                loadingPosts = false
                refresh.isEnabled = true
                refresh.contentDescription = "刷新文章"
                refresh.alpha = 1f
                if (cfg == requestConfig) {
                    posts = files
                    PostCache.save(this, requestConfig, files)
                    renderPosts(files)
                    status.visibility = View.GONE
                }
            },
            fail = {
                loadingPosts = false
                refresh.isEnabled = true
                refresh.contentDescription = "重新同步文章"
                refresh.alpha = 1f
                status.text = if (cached == null) "同步失败，点击重试 · $it" else "同步失败，当前显示离线内容 · $it"
                if (posts.isEmpty()) {
                    findViewById<TextView>(R.id.empty_published_title).text = "暂时无法同步文章"
                    findViewById<TextView>(R.id.empty_published_desc).text = "检查网络和仓库设置，然后点击右上角重试。"
                }
            }
        )
    }

    private fun renderPosts(files: List<Github.PostFile>) {
        val query = findViewById<EditText>(R.id.search_posts).text.toString().trim()
        visiblePosts = files.filter { query.isBlank() || (listOf(it.title, it.name) + it.categories + it.tags).any { value -> value.contains(query, true) } }
        findViewById<TextView>(R.id.tv_post_total).text = "${files.size}  篇已发布"
        lvPosts.adapter = CardAdapter(
            visiblePosts.map { it.title.ifBlank { it.name.removeSuffix(".md").substringAfterLast('/') } },
            visiblePosts.map { slashDate(it.date) },
            visiblePosts.map { it.categories.firstOrNull() ?: "随笔" },
            visiblePosts.map { it.tags.take(3).joinToString("  ·  ").ifBlank { "已发布 · 点击阅读全文" } },
            visiblePosts.map { it.images },
            onImageClick = { pos -> visiblePosts.getOrNull(pos)?.let { previewPost(it) } },
        )
        findViewById<View>(R.id.empty_published).visibility = if (visiblePosts.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.empty_published_title).text = if (query.isBlank()) "第一篇故事，从这里开始" else "没有找到相关文章"
        findViewById<TextView>(R.id.empty_published_desc).text = if (query.isBlank()) "把日常与想法，分享给更大的世界。" else "试试其他标题、分类或标签。"
        findViewById<Button>(R.id.empty_published_action).text = if (cfg.token.isBlank()) "连接 GitHub" else "开始写作"
        if (query.isBlank() && cfg.token.isBlank()) {
            findViewById<TextView>(R.id.empty_published_title).text = "连接你的发布空间"
            findViewById<TextView>(R.id.empty_published_desc).text = "在工作台连接 GitHub，即可同步和发布文章。"
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
              body{font-family:sans-serif;padding:28px 24px 64px;color:#45534d;background:#ffffff;line-height:1.9;word-wrap:break-word;}
              h1{font-size:28px;color:#202d29;line-height:1.4;margin:0 0 10px;}
              .meta{font-size:12px;color:#718072;letter-spacing:.5px;margin-bottom:22px;padding-bottom:16px;border-bottom:1px solid #edf0ea;}
              h2,h3,h4{color:#202d29;line-height:1.4;}
              h2{border-bottom:1px solid #e1e8e1;padding-bottom:.3em;}
              code{background:#eff3ed;padding:2px 5px;border-radius:4px;color:#344d40;}
              pre{background:#183f34;color:#f6f7f3;padding:12px;border-radius:8px;overflow-x:auto;}
              pre code{background:none;color:inherit;padding:0;}
              blockquote{border-left:4px solid #235c4b;margin:.6em 0;padding:2px 12px;color:#607164;background:#eff3ed;}
              img{max-width:100%;border-radius:10px;} a{color:#235c4b;}
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

    private fun confirmPublish() {
        if (publishing) return
        if (cfg.token.isBlank()) {
            if (dirty) saveDraft(false)
            showPanel("settings")
            fillSettings()
            toast("连接 GitHub 后即可发布，草稿已为你保留")
            return
        }
        if (etTitle.text.isBlank()) { etTitle.error = "给文章起一个标题吧"; etTitle.requestFocus(); return }
        if (latestMarkdown.isBlank()) { toast("正文还是空白，先写点内容吧"); return }
        if (dirty) saveDraft(false)
        AlertDialog.Builder(this)
            .setTitle(if (currentPath == null) "发布这篇文章？" else "更新这篇文章？")
            .setMessage("「${etTitle.text}」\n\n将发布到 ${cfg.owner}/${cfg.repo} · ${cfg.branch}。")
            .setPositiveButton(if (currentPath == null) "确认发布" else "确认更新") { _, _ -> publish() }
            .setNegativeButton("再看看", null).show()
    }

    private fun publish() {
        if (publishing) return
        getMarkdown { body ->
            val p = readPostData(body)
            if (p.title.isBlank()) { toast("请填写标题"); return@getMarkdown }
            val content = Post.build(p)

            val existingPath = currentPath
            val existingSha = currentSha
            val draftId = currentDraftId
            val requestConfig = cfg.copy()
            publishing = true
            setDocumentEnabled(false)
            val publishButton = findViewById<Button>(R.id.btn_publish)
            publishButton.isEnabled = false
            publishButton.text = "发布中…"
            io(
                task = {
                    var path = existingPath
                    if (path == null) {
                        val base = Post.sanitize(p.title).ifBlank { "untitled" }
                        val prefix = Regex("""^\d{4}-\d{2}-\d{2}""").find(p.date)?.value
                        val fileBase = if (prefix != null) "$prefix-$base" else base
                        path = "${requestConfig.postsDir}/$fileBase.md"
                        // Only a real 404 means the filename is available.
                        try {
                            Github.getFile(requestConfig, path)
                            path = "${requestConfig.postsDir}/$fileBase-${System.currentTimeMillis()}.md"
                        } catch (e: Exception) {
                            if (e.message?.startsWith("GitHub API 404:") != true) throw e
                        }
                    }
                    Github.putFile(requestConfig, path, content, existingSha, "publish: ${p.title}")
                },
                done = {
                    publishing = false
                    setDocumentEnabled(true)
                    publishButton.isEnabled = true
                    publishButton.text = "发布"
                    draftId?.let { DraftStore.delete(this, it) }
                    // Keep edits made while the request was in flight as a new local draft.
                    if (readPostData(latestMarkdown) == p) resetEditor()
                    else {
                        currentDraftId = null
                        currentPath = null
                        currentSha = null
                        saveDraft(false)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("文章已发布")
                        .setMessage("文字已送达仓库，博客通常会在约 1 分钟后更新。")
                        .setPositiveButton("查看文章库") { _, _ -> showPanel("published"); loadPosts() }
                        .setNegativeButton("继续写作", null).show()
                },
                fail = {
                    publishing = false
                    setDocumentEnabled(true)
                    publishButton.isEnabled = true
                    publishButton.text = "重新发布"
                    AlertDialog.Builder(this).setTitle("发布未完成")
                        .setMessage("草稿仍保留在本机。\n\n$it")
                        .setPositiveButton("知道了", null).show()
                }
            )
        }
    }

    private fun setDocumentEnabled(enabled: Boolean) {
        listOf(etTitle, etTags, etCategories, etDate).forEach { it.isEnabled = enabled }
        richEditor.evaluateJavascript("el().contentEditable = '$enabled'", null)
        val toolbar = findViewById<HorizontalScrollView>(R.id.format_toolbar).getChildAt(0) as ViewGroup
        for (i in 0 until toolbar.childCount) toolbar.getChildAt(i).isEnabled = enabled
    }

    // ---- 图片 ----

    private fun pickImage() {
        if (cfg.token.isBlank()) { toast("请先在工作台连接 GitHub，再上传图片"); return }
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
        if (dirty) saveDraft(false)
        editorLoading = true
        val path = pendingEditPath ?: return
        etTitle.setText(p.title)
        etTags.setText(p.tags.joinToString(", "))
        etCategories.setText(p.categories.joinToString(", "))
        etDate.setText(p.date)
        loadEditor(Markdown.render(p.body), p.body)
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
        findViewById<TextView>(R.id.tv_repo_status).visibility = View.GONE
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
        val test = findViewById<Button>(R.id.btn_test)
        test.isEnabled = false
        test.text = "正在连接…"
        findViewById<TextView>(R.id.tv_repo_status).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_repo_status).text = "正在验证仓库访问权限…"
        val requestConfig = cfg.copy()
        io(
            task = { Github.testConnection(requestConfig) },
            done = {
                test.isEnabled = true
                test.text = "重新测试"
                findViewById<TextView>(R.id.tv_repo_status).text = "连接成功 · $it"
                toast("连接成功")
            },
            fail = {
                test.isEnabled = true
                test.text = "重试连接"
                findViewById<TextView>(R.id.tv_repo_status).text = "连接失败 · $it"
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        imagePool.shutdownNow()
        richEditor.removeJavascriptInterface("ZhiJian")
        super.onDestroy()
    }

    private fun <T> io(task: () -> T, done: (T) -> Unit, fail: (String) -> Unit) {
        Thread {
            try {
                val r = task()
                runOnUiThread { if (!isDestroyed) done(r) }
            } catch (e: Exception) {
                val msg = e.message ?: "出错了"
                runOnUiThread { if (!isDestroyed) fail(msg) }
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

            v.contentDescription = "${titles[position]}，${descs[position]}。长按管理"
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
