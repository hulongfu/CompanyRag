---
name: web-search
description: >-
  网络搜索技能，支持Tavily和DuckDuckGo搜索引擎。
  优先使用Tavily API（需要API密钥），如果不可用则自动降级到免费的DuckDuckGo搜索。
read_when:
  - User wants to search the web
  - User asks about current information or news
  - User needs to find information online
  - User wants to research a topic
---

# Web Search Skill

## Usage

### For Deep Agent (Execute Scripts)

**Script Location:** `scripts/search_tool.py` (in skill directory)

**Usage Pattern:**
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py '搜索关键词' [options]")
```

## Search Engines

### 1. Tavily Search (推荐)
- **特点**: 高质量搜索结果，专为AI应用优化
- **要求**: 需要API密钥
- **获取密钥**: https://tavily.com/
- **设置方式**: 
  - 环境变量: `export TAVILY_API_KEY="your-api-key"`
  - 命令行参数: `--api-key "your-api-key"`

### 2. DuckDuckGo Search (备用)
- **特点**: 免费，无需API密钥
- **限制**: 结果质量略低，可能被限流
- **用途**: Tavily不可用时的降级方案

## Examples

### 1. Basic search (使用 Tavily)
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py 'Python 教程'")
```

### 2. Specify number of results
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py 'AI 发展趋势' --num-results 10")
```

### 3. Use DuckDuckGo directly
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py '最新新闻' --engine duckduckgo")
```

### 4. With Tavily API key
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py '机器学习' --api-key 'tvly-dev-xxx'")
```

### 5. Text output format
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/search_tool.py 'Python 最佳实践' --output text")
```

## Options

- `query` - 搜索关键词（必需）
- `--num-results` - 返回结果数量（默认: 5）
- `--api-key` - Tavily API密钥（可选）
- `--engine` - 首选搜索引擎 (tavily, duckduckgo，默认: tavily)
- `--output` - 输出格式 (json, text，默认: json)

## Output Format

**JSON格式:**
```json
{
  "success": true,
  "engine": "tavily",
  "query": "搜索关键词",
  "total_results": 5,
  "results": [
    {
      "rank": 1,
      "title": "标题",
      "content": "内容摘要",
      "url": "https://example.com"
    }
  ]
}
```

**文本格式:**
```
🔍 搜索 '关键词' 的结果 (引擎: tavily):

1. 标题
   内容摘要
   链接: https://example.com
```

## Fallback Mechanism

搜索流程：
1. 尝试使用Tavily搜索
2. 如果Tavily失败（API密钥无效、网络错误等）
3. 自动降级到DuckDuckGo搜索
4. 返回搜索结果

## Dependencies

**必需依赖:**
```bash
pip install requests beautifulsoup4
```

**Tavily依赖（可选）:**
```bash
pip install langchain-community tavily-python
```

## Environment Variables

- `TAVILY_API_KEY` - Tavily API密钥

## Notes

- **优先级**: 默认优先使用Tavily，失败则降级到DuckDuckGo
- **API密钥**: Tavily需要API密钥，可在 https://tavily.com/ 免费获取
- **降级策略**: 自动降级确保搜索功能始终可用
- **结果质量**: Tavily结果质量更高，专为AI应用优化
- **免费方案**: DuckDuckGo完全免费，无需注册

## Troubleshooting

**如果Tavily搜索失败:**
- 检查API密钥是否正确
- 检查网络连接
- 查看是否超出API调用限制
- 系统会自动降级到DuckDuckGo

**如果DuckDuckGo搜索失败:**
- 检查网络连接
- 可能被限流，稍后重试
- 安装依赖: `pip install requests beautifulsoup4`

**如果提示缺少依赖:**
- Tavily: `pip install langchain-community tavily-python`
- DuckDuckGo: `pip install requests beautifulsoup4`
