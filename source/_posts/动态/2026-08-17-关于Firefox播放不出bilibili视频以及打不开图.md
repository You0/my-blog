---
title: 关于Firefox播放不出bilibili视频以及打不开图片fix
date: 2026-08-17 23:20:41
categories: 动态
tags: [B站, 专栏]
---
打不开图片：关掉跟踪性内容保护。会限制跨域的图片引用。

![关于Firefox播放不出bilibili视频以及打不开图片fix](/images/动态/1237558924762152996/1.png)

播放不了视频：

在 Firefox 地址栏输入 about:config，搜索以下配置。如果显示为“已修改”，点击右侧重置按钮恢复默认值：

- network.http.sendRefererHeader
- network.http.referer.XOriginPolicy
- network.http.referer.XOriginTrimmingPolicy
- network.http.referer.trimmingPolicy

还有如果设置过严格校验跨域什么的配置也都还原下就行了。
