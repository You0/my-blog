# 纸间 · 博客写作 Android App

原生安卓 App：在手机上写 Markdown 博客并发布到 GitHub，配合仓库里的 GitHub Actions 自动构建部署。

## 新版界面与交互

- 暖白与墨绿的统一视觉、折纸笔尖图标、卡片布局和四栏底部导航。
- 页面统一使用单行标题与主要操作，文章数量合并到列表栏，移除重复品牌横幅与宣传文案。
- 写作页支持独立文章信息面板、独立预览、专注模式、格式工具栏和阅读时长。
- 输入停止 1.2 秒后自动保存到本机；切换页面和进入后台时保存，重启后恢复当前草稿。
- 草稿支持标题与正文搜索；文章支持标题、分类与标签搜索，并展示数量与同步状态。
- 首次打开即可离线写作；发布时引导连接 GitHub，发布前确认目标仓库，失败后保留草稿。
- 工作台按仓库连接和写作偏好分组，支持访问令牌显隐与连接结果反馈。

## 功能
- 写文章：标题 / 标签 / 分类 / 日期 / 正文（Markdown）
- Markdown 预览（标题、加粗、代码块、列表、引用、图片、链接等）
- 本地草稿：保存 / 编辑 / 长按删除（存在手机本地，离线可用）
- 发布：通过 GitHub API 把文章写入 `source/_posts/`，触发 CI 自动构建部署
- 编辑已发布文章：拉取列表 → 修改 → 更新原文件
- 设置：Token / 仓库拥有者 / 仓库名 / 分支 / 文章目录 / 作者，支持「测试连接」

## 构建（在这台 Mac 上）

依赖：JDK 21、Gradle 8.14、Android SDK（platform `android-35` + build-tools `35.0.0`）。

```bash
cd android
export JAVA_HOME="$HOME/tools/jdk-21.0.12.1+1/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
~/tools/gradle-8.14.3/bin/gradle --no-daemon assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以用 Android Studio 打开 `android/` 目录直接 Build（会自动用 Gradle Wrapper）。

## 安装到手机

1. 把 `app-debug.apk` 传到手机（微信 / 网盘 / 数据线均可）。
2. 手机上打开该文件，按提示允许「安装未知来源应用」，点安装。
3. 首次打开 → 切到「我的」→ 填 GitHub Token（需 Contents 写权限）→ 点「测试连接」。
4. 写文章 → 点「发布」→ 约 1 分钟后博客自动更新。

## 验证

```bash
./gradlew assembleDebug lintDebug
```

界面交互在 Android 模拟器验证，包括自动保存、预览、专注模式、草稿搜索、进程重启恢复、文章同步和令牌显隐。真实 GitHub 发布需要配置自己的仓库和 Token。

## 说明

- 本 APK 是 debug 签名，仅供个人使用；如需正式签名可另配 keystore。
- Token 只存手机本地 SharedPreferences，不会上传到别处。
- 发布依赖仓库里的 GitHub Actions 工作流（`.github/workflows/deploy.yml`），请先按根目录
  `MOBILE_BLOG_SETUP.md` 配置好 CI 密钥。
