# 手机写博客 & 自动发布 使用说明

这套方案让你在手机上写博客、点「发布」就自动部署，无需再开电脑跑 `hexo deploy`。

手机端是一个**原生安卓 App**（`android/`，可直接装 APK），配合 GitHub Actions 自动构建。

## 工作原理

```
安卓 App ──写 Markdown──▶ 写入 GitHub 仓库 my-blog/source/_posts/
                              │  (push 触发)
                              ▼
                    GitHub Actions 自动 hexo generate
                              │
                              ▼
                  部署到 You0.github.io（你的博客）
```

- `android/`：原生安卓 App（APK），写文章、Markdown 预览、本地草稿、发布、编辑已发布文章。
- `.github/workflows/deploy.yml`：仓库 `main` 分支有推送时，自动构建博客并部署到 `You0.github.io`。
- `admin/` + `.github/workflows/deploy-admin.yml`：早期做的 PWA 版，**现在用不上**，留着只是备用，可整目录删除。

## 一次性配置（按顺序做）

### 1. 创建 GitHub Token（PAT）

去 GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token：

- **Scope** 勾选 `repo`（最简单，一劳永逸）。
- 复制生成的 token（形如 `ghp_...`），**只显示一次，先存好**。

> 也可以创建 Fine-grained token：给 `You0/my-blog` 和 `You0/You0.github.io` 两个仓库都授予 `Contents: Read and write`。

### 2. 给仓库加 CI 密钥

打开 `You0/my-blog` 仓库 → **Settings → Secrets and variables → Actions → New repository secret**：

- Name 填 `GH_PAT`
- Secret 填上一步的 token

> 注意：这个 token 要用两处 —— 一处是 App 里（写文章用），一处是这个 CI 密钥（部署用），**同一把即可**。

### 3. 推送代码

把新增的文件提交并推送到 `main`：

```bash
git add .github android scripts MOBILE_BLOG_SETUP.md
git commit -m "feat: 安卓写作 App + GitHub Actions 自动部署"
git push
```

推送后，`deploy.yml` 会自动构建一次博客（验证 CI 是否正常），去仓库 **Actions** 页看结果，跑绿即成功。

## 在手机上用

1. 把 APK 传到手机安装。APK 已生成在：
   - `~/Downloads/blog-writing-v1.0.apk`（方便发送）
   - `android/app/build/outputs/apk/debug/app-debug.apk`（构建产物）
2. 手机上打开该文件，按提示允许「安装未知来源应用」→ 安装。
3. 打开 App → 切到「设置」→ 填 GitHub Token → 点「测试连接」，显示连接成功即可。
4. 写文章 → 点「发布」→ 约 1 分钟后博客自动更新。「草稿」保存在手机本地，可离线写。

> 重新打包请看 `android/README.md`。

## 说明

- 你原来的本地 `yarn deploy` 依然可用，两者不冲突；以后想怎么发都行。
- Token 只存在手机 App 本地（SharedPreferences），不会上传到别处；泄露可随时在 GitHub 撤销。
- 若文章标题同名，App 会自动加时间戳后缀避免覆盖；编辑已发布文章会更新原文件。
- APK 是 debug 签名，仅供个人使用；如需正式签名可在 `android/` 里配 keystore 重打。
