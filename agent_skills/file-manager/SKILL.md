---
name: file-manager
description: >-
  统一文件管理技能，支持文件的读取、写入、创建、删除、移动、复制、列表、搜索等操作。
  提供完整的文件系统操作能力。
read_when:
  - User wants to read or write files
  - User needs to manage files or folders
  - User wants to search for files
  - User needs to organize or manipulate files
---

# File Manager Skill

## Usage

### For Deep Agent (Execute Scripts)

**Script Location:** `scripts/file_manager.py` (in skill directory)

**Usage Pattern:**
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py <operation> [options]")
```

## Supported Operations

| Operation | Description | Example |
|-----------|-------------|---------|
| `read` | 读取文件内容 | `read --file "data.txt"` |
| `write` | 写入文件内容 | `write --file "data.txt" --content "Hello"` |
| `write-large` | 写入大文件内容（自动判断大小，优化 LLM token 使用） | `write-large --file "api.md" --content "..."` |
| `create-folder` | 创建文件夹 | `create-folder --folder "project/src"` |
| `delete-file` | 删除文件 | `delete-file --file "old.txt"` |
| `delete-folder` | 删除文件夹 | `delete-folder --folder "temp"` |
| `move` | 移动/重命名 | `move --file "old.txt" --target "new.txt"` |
| `copy` | 复制文件 | `copy --file "source.txt" --target "backup.txt"` |
| `list` | 列出文件 | `list --folder "documents"` |
| `info` | 文件信息 | `info --file "report.pdf"` |
| `search` | 搜索文件 | `search --pattern "*.py"` |

## Examples

### 1. Read a file
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py read --file 'D:/documents/report.txt'")
```

### 2. Write to a file
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py write --file 'D:/output/result.txt' --content 'Hello World'")
```

### 2.5. Write large content to a file (optimized for LLM token usage)
```bash
# 当内容超过 10000 字符时，自动使用优化策略
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py write-large --file 'D:/output/large_api_doc.md' --content '这里是大量的 API 文档内容...'")

# 自定义大小阈值（5000 字符）
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py write-large --file 'api.md' --content '...' --size-threshold 5000")

# 从文件读取内容（推荐方式，避免命令行参数传递大内容时的截断问题）
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py write-large --file 'D:/output/api_doc.md' --content-file 'D:/temp/api_content.txt'")
```

### 3. Create a folder
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py create-folder --folder 'project/src/components'")
```

### 4. List files in a folder
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py list --folder 'D:/project' --recursive")
```

### 5. Search for files
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py search --pattern '*.py' --base-folder 'D:/project'")
```

### 6. Copy a file
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py copy --file 'source.txt' --target 'backup.txt'")
```

### 7. Move/rename a file
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py move --file 'old_name.txt' --target 'new_name.txt'")
```

### 8. Delete a file
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py delete-file --file 'temp.txt' --force")
```

### 9. Get file information
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe scripts/file_manager.py info --file 'document.pdf'")
```

## Common Options

- `--file` - 文件路径
- `--folder` - 文件夹路径
- `--base-folder` - 基础文件夹 (默认："shared")
  - `shared` - 当前项目的 shared 目录
  - `desktop` - 桌面
  - `documents` - 文档目录
  - `downloads` - 下载目录
  - 或绝对路径
- `--content` - 文件内容（写入操作）
- `--content-file` - 内容文件路径（write-large 操作专用，从文件读取内容，避免命令行参数传递大内容时的截断问题）
- `--encoding` - 文件编码（默认：utf-8）
- `--target` - 目标路径（移动/复制操作）
- `--size-threshold` - 大文件大小阈值（字符数，仅用于 write-large 操作，默认：10000）
- `--pattern` - 文件匹配模式（默认: *）
- `--recursive` - 递归操作
- `--force` - 强制操作
- `--overwrite` - 覆盖现有文件（默认: true）

## Output Format

All operations return JSON to stdout:

**Success Response:**
```json
{
  "success": true,
  "message": "操作成功",
  "file_path": "完整路径",
  ...
}
```

**Error Response:**
```json
{
  "success": false,
  "error": "错误信息"
}
```

## Base Folder Options

The `--base-folder` parameter supports special keywords:

| Keyword | Description | Example Path |
|---------|-------------|--------------|
| `shared` | 项目shared目录 | `D:/project/shared` |
| `desktop` | 用户桌面 | `C:/Users/xxx/Desktop` |
| `documents` | 用户文档 | `C:/Users/xxx/Documents` |
| `downloads` | 用户下载 | `C:/Users/xxx/Downloads` |
| 绝对路径 | 直接使用 | `D:/custom/path` |

## Notes

- **Safety**: 所有操作都有错误处理和日志记录
- **Encoding**: 自动尝试UTF-8和GBK编码
- **Path Resolution**: 支持相对路径和绝对路径
- **Recursive Operations**: 列表和删除支持递归
- **Force Delete**: 强制删除会修改文件权限
- **Auto Create Parents**: 创建文件夹时自动创建父目录

## Security Considerations

- 删除操作不可逆，请谨慎使用
- 建议在操作前先使用 `info` 命令确认文件信息
- 使用 `list` 命令预览文件夹内容
- 重要文件建议先使用 `copy` 创建备份
