'use strict';

/* ================= 配置 ================= */
const CONFIG_KEY = 'blog-admin-config';
const DEFAULTS = {
  token: '',
  owner: 'You0',
  repo: 'my-blog',
  branch: 'main',
  postsDir: 'source/_posts',
  author: 'You0',
};

let cfg = loadConfig();

function loadConfig() {
  try {
    return Object.assign({}, DEFAULTS, JSON.parse(localStorage.getItem(CONFIG_KEY) || '{}'));
  } catch (_) {
    return Object.assign({}, DEFAULTS);
  }
}

function saveConfig() {
  localStorage.setItem(CONFIG_KEY, JSON.stringify(cfg));
}

/* ================= 工具 ================= */
const $ = (id) => document.getElementById(id);

let toastTimer = null;
function toast(msg, ok = true) {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast show' + (ok ? '' : ' err');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.className = 'toast'; }, 3000);
}

function pad(n) { return String(n).padStart(2, '0'); }

function nowString() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function splitList(s) {
  return String(s || '').split(/[,，]/).map((x) => x.trim()).filter(Boolean);
}

function sanitizeFilename(t) {
  return String(t).replace(/[\\/:*?"<>|]/g, '-').replace(/\s+/g, ' ').trim();
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function encodePath(p) {
  return p.split('/').map(encodeURIComponent).join('/');
}

function utf8ToBase64(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return btoa(bin);
}

function base64ToUtf8(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/* ================= GitHub API ================= */
async function api(path, { method = 'GET', body } = {}) {
  const headers = {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (cfg.token) headers.Authorization = `Bearer ${cfg.token}`;
  const opts = { method, headers };
  if (body !== undefined) {
    opts.body = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(`https://api.github.com${path}`, opts);
  if (!res.ok) {
    let msg = `GitHub API ${res.status}`;
    try {
      const j = await res.json();
      if (j.message) msg += ': ' + j.message;
    } catch (_) { /* ignore */ }
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  return res.json();
}

async function listPosts() {
  const branch = cfg.branch || 'main';
  const data = await api(`/repos/${cfg.owner}/${cfg.repo}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
  const prefix = String(cfg.postsDir || 'source/_posts').replace(/\/+$/, '') + '/';
  const files = (data.tree || [])
    .filter((t) => t.type === 'blob' && t.path.startsWith(prefix) && t.path.endsWith('.md'))
    .map((t) => ({ path: t.path, name: t.path.slice(prefix.length), sha: t.sha }));
  return files.sort((a, b) => a.path.localeCompare(b.path));
}

async function getFile(path) {
  const d = await api(`/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}`);
  return { sha: d.sha, text: base64ToUtf8(d.content) };
}

async function tryGetFile(path) {
  try { return await getFile(path); } catch (_) { return null; }
}

async function putFile(path, content, sha, message) {
  const body = { message, content: utf8ToBase64(content), branch: cfg.branch || 'main' };
  if (sha) body.sha = sha;
  return api(`/repos/${cfg.owner}/${cfg.repo}/contents/${encodePath(path)}`, { method: 'PUT', body });
}

async function testConnection() {
  const d = await api(`/repos/${cfg.owner}/${cfg.repo}`);
  return d.full_name;
}

/* ================= IndexedDB 草稿 ================= */
function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('blog-admin', 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains('drafts')) {
        db.createObjectStore('drafts', { keyPath: 'id' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function idbReq(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function getAllDrafts() {
  const db = await openDB();
  const tx = db.transaction('drafts', 'readonly');
  const all = await idbReq(tx.objectStore('drafts').getAll());
  return all || [];
}

async function putDraft(d) {
  const db = await openDB();
  const tx = db.transaction('drafts', 'readwrite');
  await idbReq(tx.objectStore('drafts').put(d));
}

async function removeDraft(id) {
  const db = await openDB();
  const tx = db.transaction('drafts', 'readwrite');
  await idbReq(tx.objectStore('drafts').delete(id));
}

/* ================= 文章 front-matter ================= */
function buildFrontMatter(p) {
  const lines = ['---'];
  lines.push(`title: ${p.title || ''}`);
  lines.push(`date: ${p.date || nowString()}`);
  if (p.author) lines.push(`author: ${p.author}`);
  const tags = p.tags || [];
  lines.push('tags:');
  tags.forEach((t) => lines.push(`  - ${t}`));
  const cats = p.categories || [];
  lines.push('categories:');
  if (cats.length) cats.forEach((c) => lines.push(`  - ${c}`));
  else lines.push('  []');
  lines.push('---', '', p.body || '');
  return lines.join('\n');
}

function parseFrontMatter(text) {
  const out = { title: '', tags: [], categories: [], date: '', author: '', body: text };
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!m) return out;
  const fm = m[1];
  out.body = m[2];
  let curList = null;
  for (const raw of fm.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    if (/^-/.test(line)) {
      if (curList) out[curList].push(line.replace(/^-\s*/, ''));
      continue;
    }
    const kv = /^([A-Za-z_]+):\s*(.*)$/.exec(line);
    if (kv) {
      const key = kv[1];
      let val = kv[2].trim();
      if (key === 'tags' || key === 'categories') {
        if (val && val !== '[]') {
          if (val.startsWith('[')) {
            out[key] = val.slice(1, -1).split(',').map((s) => s.trim()).filter(Boolean);
            curList = null;
          } else {
            out[key] = val ? [val] : [];
            curList = key;
          }
        } else {
          out[key] = [];
          curList = null;
        }
      } else {
        out[key] = val;
        curList = null;
      }
    }
  }
  return out;
}

/* ================= Markdown 预览 ================= */
function mdEscape(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function inlineMd(s) {
  let t = mdEscape(s);
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, '<img src="$2" alt="$1">');
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/__([^_]+)__/g, '<strong>$1</strong>');
  t = t.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  t = t.replace(/~~([^~]+)~~/g, '<del>$1</del>');
  return t;
}

function renderMarkdown(src) {
  const lines = String(src || '').replace(/\r\n?/g, '\n').split('\n');
  const html = [];
  let inCode = false;
  let codeBuf = [];
  let codeLang = '';
  let inList = null;
  let inQuote = false;

  const flushList = () => { if (inList) { html.push(`</${inList}>`); inList = null; } };
  const flushQuote = () => { if (inQuote) { html.push('</blockquote>'); inQuote = false; } };

  for (const line of lines) {
    if (inCode) {
      if (/^```/.test(line)) {
        html.push(`<pre><code>${mdEscape(codeBuf.join('\n'))}</code></pre>`);
        inCode = false; codeBuf = []; codeLang = '';
      } else {
        codeBuf.push(line);
      }
      continue;
    }
    if (/^```/.test(line)) { flushList(); flushQuote(); inCode = true; codeLang = line.slice(3).trim(); continue; }
    if (/^\s*$/.test(line)) { flushList(); flushQuote(); continue; }

    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) { flushList(); flushQuote(); const n = h[1].length; html.push(`<h${n}>${inlineMd(h[2])}</h${n}>`); continue; }

    if (/^\s*([-*_])\s*(\1\s*){2,}$/.test(line)) { flushList(); flushQuote(); html.push('<hr>'); continue; }

    const q = /^>\s?(.*)$/.exec(line);
    if (q) { flushList(); if (!inQuote) { html.push('<blockquote>'); inQuote = true; } html.push(inlineMd(q[1])); continue; }

    const ul = /^\s*[-*+]\s+(.*)$/.exec(line);
    if (ul) { flushQuote(); if (inList !== 'ul') { flushList(); html.push('<ul>'); inList = 'ul'; } html.push(`<li>${inlineMd(ul[1])}</li>`); continue; }

    const ol = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    if (ol) { flushQuote(); if (inList !== 'ol') { flushList(); html.push('<ol>'); inList = 'ol'; } html.push(`<li>${inlineMd(ol[1])}</li>`); continue; }

    flushList(); flushQuote();
    html.push(`<p>${inlineMd(line)}</p>`);
  }
  flushList(); flushQuote();
  if (inCode) html.push(`<pre><code>${mdEscape(codeBuf.join('\n'))}</code></pre>`);
  return html.join('\n');
}

/* ================= 编辑器状态 ================= */
// current: { mode: 'new'|'edit'|'draft', draftId, path, sha, author }
let current = { mode: 'new', author: cfg.author };
let _drafts = [];
let _files = [];

function readEditor() {
  return {
    title: $('title').value.trim(),
    tags: splitList($('tags').value),
    categories: splitList($('categories').value),
    date: $('date').value.trim(),
    body: $('body').value,
  };
}

function fillEditor(p) {
  $('title').value = p.title || '';
  $('tags').value = (p.tags || []).join(', ');
  $('categories').value = (p.categories || []).join(', ');
  $('date').value = p.date || nowString();
  $('body').value = p.body || '';
}

function newPost() {
  fillEditor({});
  current = { mode: 'new', author: cfg.author };
  switchView('editor');
  updatePreview();
  $('title').focus();
}

function loadDraft(d) {
  fillEditor(d);
  current = { mode: 'draft', draftId: d.id, author: cfg.author };
  switchView('editor');
  updatePreview();
}

async function loadPost(file) {
  try {
    const { sha, text } = await getFile(file.path);
    const p = parseFrontMatter(text);
    fillEditor(p);
    current = { mode: 'edit', path: file.path, sha, author: p.author || cfg.author };
    switchView('editor');
    updatePreview();
    toast('已加载：' + file.name);
  } catch (e) {
    toast(e.message, false);
  }
}

async function saveDraft() {
  const ed = readEditor();
  const d = {
    id: current.mode === 'draft' && current.draftId ? current.draftId : 'd' + Date.now(),
    title: ed.title || '未命名',
    tags: ed.tags,
    categories: ed.categories,
    date: ed.date,
    body: ed.body,
    updatedAt: Date.now(),
  };
  await putDraft(d);
  current.mode = 'draft';
  current.draftId = d.id;
  current.path = null;
  current.sha = null;
  toast('已保存草稿');
}

async function publish() {
  const ed = readEditor();
  if (!ed.title) { toast('请填写标题', false); return; }
  const content = buildFrontMatter({
    title: ed.title,
    date: ed.date || nowString(),
    author: current.author || cfg.author,
    tags: ed.tags,
    categories: ed.categories,
    body: ed.body,
  });

  let path = current.path;
  let sha = current.sha;
  if (!path) {
    const base = sanitizeFilename(ed.title) || 'untitled';
    path = `${cfg.postsDir}/${base}.md`;
    const existing = await tryGetFile(path);
    if (existing) path = `${cfg.postsDir}/${base}-${Date.now()}.md`;
  }

  $('btn-publish').disabled = true;
  try {
    await putFile(path, content, sha, `publish: ${ed.title}`);
    if (current.draftId) await removeDraft(current.draftId);
    toast('发布成功，约 1 分钟后博客自动更新');
    current = { mode: 'new', author: cfg.author };
    fillEditor({});
    loadPosts(); // 后台刷新列表
  } catch (e) {
    toast(e.message, false);
  } finally {
    $('btn-publish').disabled = false;
  }
}

/* ================= 视图切换 ================= */
function switchView(name) {
  document.querySelectorAll('.view').forEach((v) => {
    v.classList.toggle('active', v.id === 'view-' + name);
  });
  document.querySelectorAll('.tab').forEach((t) => {
    t.classList.toggle('active', t.dataset.view === name);
  });
}

/* ================= 渲染 ================= */
async function renderDrafts() {
  _drafts = await getAllDrafts();
  _drafts.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  const el = $('draft-list');
  if (!_drafts.length) { el.innerHTML = '<div class="empty">还没有草稿</div>'; return; }
  el.innerHTML = _drafts.map((d, i) => `
    <div class="card">
      <div class="card-main">
        <div class="card-title">${escapeHtml(d.title)}</div>
        <div class="card-meta">${escapeHtml(d.date || '')} · ${escapeHtml((d.tags || []).join(', '))}</div>
      </div>
      <div class="card-actions">
        <button data-act="load" data-idx="${i}">编辑</button>
        <button data-act="del" data-idx="${i}" class="danger">删除</button>
      </div>
    </div>`).join('');
}

async function loadPosts() {
  const el = $('post-list');
  el.innerHTML = '<div class="empty">加载中…</div>';
  try {
    _files = await listPosts();
    renderPosts();
  } catch (e) {
    el.innerHTML = `<div class="empty">加载失败：${escapeHtml(e.message)}</div>`;
  }
}

function renderPosts() {
  const el = $('post-list');
  if (!_files.length) { el.innerHTML = '<div class="empty">没有找到文章</div>'; return; }
  el.innerHTML = _files.map((f, i) => `
    <div class="card">
      <div class="card-main"><div class="card-title">${escapeHtml(f.name)}</div></div>
      <div class="card-actions"><button data-act="open" data-idx="${i}">编辑</button></div>
    </div>`).join('');
}

function fillSettingsForm() {
  $('set-token').value = cfg.token;
  $('set-owner').value = cfg.owner;
  $('set-repo').value = cfg.repo;
  $('set-branch').value = cfg.branch;
  $('set-posts').value = cfg.postsDir;
  $('set-author').value = cfg.author;
}

/* ================= 预览切换 ================= */
let previewOn = false;
function updatePreview() {
  $('preview').innerHTML = renderMarkdown($('body').value);
}

/* ================= 事件绑定 ================= */
document.querySelectorAll('.tab').forEach((t) => {
  t.addEventListener('click', () => {
    const v = t.dataset.view;
    if (v === 'drafts') renderDrafts();
    if (v === 'posts') loadPosts();
    if (v === 'settings') fillSettingsForm();
    switchView(v);
  });
});

$('btn-preview').addEventListener('click', () => {
  previewOn = !previewOn;
  $('body').classList.toggle('hidden', previewOn);
  $('preview').classList.toggle('hidden', !previewOn);
  $('btn-preview').textContent = previewOn ? '编辑' : '预览';
  if (previewOn) updatePreview();
});

$('body').addEventListener('input', () => { if (previewOn) updatePreview(); });

$('btn-save').addEventListener('click', saveDraft);
$('btn-publish').addEventListener('click', publish);
$('btn-refresh').addEventListener('click', loadPosts);

$('draft-list').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const d = _drafts[+btn.dataset.idx];
  if (!d) return;
  if (btn.dataset.act === 'load') loadDraft(d);
  else if (btn.dataset.act === 'del') { removeDraft(d.id).then(renderDrafts); }
});

$('post-list').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const f = _files[+btn.dataset.idx];
  if (f && btn.dataset.act === 'open') loadPost(f);
});

$('btn-save-settings').addEventListener('click', () => {
  cfg.token = $('set-token').value.trim();
  cfg.owner = $('set-owner').value.trim() || 'You0';
  cfg.repo = $('set-repo').value.trim() || 'my-blog';
  cfg.branch = $('set-branch').value.trim() || 'main';
  cfg.postsDir = $('set-posts').value.trim() || 'source/_posts';
  cfg.author = $('set-author').value.trim();
  saveConfig();
  current.author = cfg.author;
  toast('设置已保存');
});

$('btn-test').addEventListener('click', async () => {
  cfg.token = $('set-token').value.trim();
  cfg.owner = $('set-owner').value.trim() || 'You0';
  cfg.repo = $('set-repo').value.trim() || 'my-blog';
  cfg.branch = $('set-branch').value.trim() || 'main';
  cfg.postsDir = $('set-posts').value.trim() || 'source/_posts';
  cfg.author = $('set-author').value.trim();
  saveConfig();
  $('btn-test').disabled = true;
  try {
    const full = await testConnection();
    toast('连接成功：' + full);
  } catch (e) {
    toast(e.message, false);
  } finally {
    $('btn-test').disabled = false;
  }
});

/* ================= 初始化 ================= */
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => { /* 忽略，仍可正常使用 */ });
}

function init() {
  fillSettingsForm();
  newPost();
  if (!cfg.token) {
    switchView('settings');
    toast('请先在「设置」里填写 GitHub Token', false);
  }
}

init();
