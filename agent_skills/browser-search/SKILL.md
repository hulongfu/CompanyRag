---
name: browser-search
description: 在浏览器中打开百度搜索页面。当用户要求"百度搜索"、"用浏览器搜一下"、"打开百度查 XX"、"在浏览器里搜索"、"帮我搜一下（并打开页面）"时使用本技能；用户只要搜索链接而不打开浏览器时，用 --no-open 模式。注意：本技能只负责打开百度搜索页，不抓取网页内容；若用户需要读取/总结搜索结果内容，应配合 web 检索类工具使用。
---

# browser-search

在浏览器中打开百度搜索页面。用户给出关键词，本技能调用 `scripts/browser_search.py` 生成百度搜索结果页 URL（`https://www.baidu.com/s?wd=<关键词>`），并用系统默认浏览器打开。

## 何时使用

- 用户说"帮我用浏览器搜一下 XX"、"打开百度查 XX"、"在浏览器里搜 XX" → 正常模式（打开浏览器）
- 用户说"给我个百度搜索链接"、"只要 URL" → `--no-open` 模式（只输出链接）
- 用户要的是"读取/总结搜索结果内容"而不是打开页面 → 不要用本技能，改用 web 检索工具

## 使用方法

运行脚本（关键词用单引号包裹，避免空格和特殊字符问题）：

```bash
python <skill-path>/scripts/browser_search.py '查询关键词'
```

可选参数：

- `--no-open`：只输出搜索 URL，不打开浏览器。适用于无头/服务器环境，或用户只要链接时。

## 输出说明

脚本向 stdout 输出单行 JSON（`ensure_ascii=True`，中文转义为 `\uXXXX`，任何控制台编码下都不会乱码）。

成功打开浏览器：

```json
{"success": true, "url": "https://www.baidu.com/s?wd=xxx", "opened": true, "message": "已在浏览器中打开百度搜索结果页，关键词: xxx"}
```

只生成 URL（`--no-open`）：

```json
{"success": true, "url": "https://www.baidu.com/s?wd=xxx", "opened": false, "message": "已生成 URL，未打开浏览器"}
```

失败时 `success` 为 `false`，并带 `error` 字段，退出码为 1。

## 失败处理

- 报错"未找到可用浏览器"：当前是无头/服务器环境，没有图形界面。改用 `--no-open` 重新运行，把返回的 `url` 直接给用户，让用户自己点开。
- 报错"打开浏览器失败"：把 `error` 信息告诉用户，并附上 `url` 作为备选。

## 示例

**示例 1：** 用户："帮我用浏览器搜一下 2026 年大模型最新进展"

```bash
python <skill-path>/scripts/browser_search.py '2026 年大模型最新进展'
```

执行后把 `url` 和 `message` 反馈给用户，告知浏览器已打开。

**示例 2：** 用户："给我个百度搜索链接，查 Python 装饰器"

```bash
python <skill-path>/scripts/browser_search.py 'Python 装饰器' --no-open
```

执行后把 `url` 反馈给用户（不打开浏览器）。
