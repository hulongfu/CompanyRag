---
name: browser-search
description: 打开百度搜索并可抓取、返回搜索结果。当用户要求"百度搜索"、"用浏览器搜一下"、"帮我查一下 XX"、"在浏览器里搜索"、"帮我搜一下 XX 并告诉我结果"时使用本技能；它会打开百度搜索页，并抓取/返回搜索结果的标题、链接和摘要供智能体阅读。若无头/服务器环境或用户只要链接而无需打开页面，用 --fetch-only 或 --no-open 模式。
---

# browser-search

在浏览器中打开百度搜索页，并抓取/返回搜索结果。用户给出关键词，本技能调用 `scripts/browser_search.py`：

1. 生成百度搜索结果页 URL（`https://www.baidu.com/s?wd=<关键词>`）
2. 打开系统默认浏览器（默认模式）
3. 抓取并解析搜索结果（标题、链接、摘要），以 JSON 一并返回给智能体

这样智能体不仅能打开页面，还能直接"看到"搜索结果内容并反馈给用户。

## 何时使用

- 用户说"帮我用浏览器搜一下 XX"、"打开百度查 XX"、"在浏览器里搜 XX" → 默认模式（打开浏览器 + 返回结果）
- 用户在无头/服务器环境，或只需要结果不需要弹浏览器 → `--fetch-only` 模式（只抓取返回结果）
- 用户说"给我个百度搜索链接"、"只要 URL" → `--no-open` 模式（只输出链接，不抓取）
- 用户要的是"读取某个结果页面正文" → 用链接配合 web 检索工具正文抓取

## 使用方法

运行脚本（关键词用单引号包裹，避免空格和特殊字符问题）：

```bash
python <skill-path>/scripts/browser_search.py '查询关键词'
```

可选参数：

- `--fetch-only`：只抓取并返回搜索结果，不打开浏览器。适用于无头/服务器环境，或用户只要结果不要开页面。
- `--no-open`：只输出搜索 URL，不打开浏览器也不抓取。适用于用户只要链接时。
- `--top N`：返回前 N 条结果（默认 8）。

## 输出说明

脚本向 stdout 输出单行 JSON（`ensure_ascii=True`，中文转义为 `\uXXXX`，任何控制台编码下都不会乱码）。成功时 `success` 为 `true`，并带：

- `url`：百度搜索结果页链接
- `opened`：是否打开了浏览器
- `results`：搜索结果数组，每项含 `title`（标题）、`link`（跳转链接）、`abstract`（摘要）
- `message`：中文说明

**默认模式（打开浏览器 + 返回结果）：**

```json
{"success": true, "url": "https://www.baidu.com/s?wd=xxx", "opened": true,
 "results": [{"title": "…", "link": "http://…", "abstract": "…"}],
 "message": "已在浏览器中打开，并抓取到搜索结果（共 N 条），关键词: xxx"}
```

**仅抓取（`--fetch-only`）：**

```json
{"success": true, "url": "…", "opened": false,
 "results": [{"title": "…", "link": "…", "abstract": "…"}],
 "message": "已抓取到搜索结果（共 N 条），关键词: xxx"}
```

**仅 URL（`--no-open`）：**

```json
{"success": true, "url": "https://www.baidu.com/s?wd=xxx", "opened": false, "results": [],
 "message": "已生成 URL，未打开浏览器也未抓取结果"}
```

失败时 `success` 为 `false`，并带 `error` 字段，退出码为 1。

## 失败处理

- 报错"抓取搜索结果失败"：多为百度触发了风控校验（请求过于频繁或环境异常）。改用 `--no-open` 把返回的 `url` 直接给用户，让用户自己点开；或稍后重试。
- 报错"未找到可用浏览器"：当前是无头/服务器环境，没有图形界面。改用 `--fetch-only` 重新运行直接拿结果，结果已在 JSON 的 `results` 字段里。
- 报错"打开浏览器失败"：把 `error` 信息告诉用户，但 `results` 里通常仍有搜索结果，可直接反馈给用户。

## 示例

**示例 1：** 用户："帮我用浏览器搜一下 2026 年大模型最新进展，并告诉我有哪些进展"

```bash
python <skill-path>/scripts/browser_search.py '2026 年大模型最新进展'
```

执行后读取返回 JSON 的 `results`，把各条标题、摘要反馈给用户，并告知浏览器已打开。

**示例 2：** 用户（服务器/无头环境）："帮我查一下 Python 装饰器的用法"

```bash
python <skill-path>/scripts/browser_search.py 'Python 装饰器' --fetch-only
```

执行后读取 `results`，直接把搜索结果内容反馈给用户。

**示例 3：** 用户："给我个百度搜索链接，查 Python 装饰器"

```bash
python <skill-path>/scripts/browser_search.py 'Python 装饰器' --no-open
```

执行后把 `url` 反馈给用户（不打开浏览器）。