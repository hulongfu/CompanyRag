---
name: read-document
description: >-
  统一文档读取技能，支持读取PDF、Word、Excel、PowerPoint等多种文档格式。
  根据文件扩展名自动识别并选择相应的解析方法。
read_when:
  - User wants to read a document file (PDF, Word, Excel, PowerPoint, etc.)
  - User asks about document content
  - User needs to extract text or data from a document
  - User wants to analyze a file's content
---

# Document Reader Skill

## Usage

### For Deep Agent (Execute Scripts)

**Script Location:** `skills/read-document/scripts/read_document.py`

**Usage Pattern:**
```bash
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/documents/example.pdf'")
```

**Available Commands:**
- `--file "..."` - 文档文件路径
- `--sheet "..."` - Excel工作表名称（可选）
- `--page N` - PDF页码（可选）
- `--formats` - 列出支持的文件格式

**Example:**
```bash
# Read PDF file
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/docs/report.pdf'")

# Read specific PDF page
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/docs/report.pdf' --page 5")

# Read Word document
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/docs/contract.docx'")

# Read Excel file
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/data/sales.xlsx'")

# Read specific Excel sheet
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/data/sales.xlsx' --sheet 'Sheet1'")

# Read PowerPoint
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'D:/presentations/slides.pptx'")

# List supported formats
execute("D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file 'dummy' --formats")
```

## Supported Formats

This skill automatically detects and reads the following document types:

| Format | Extensions | Description | Required Library |
|--------|-----------|-------------|------------------|
| PDF | \`.pdf\` | PDF documents | PyMuPDF |
| Word | \`.docx\` | Microsoft Word documents | python-docx |
| Excel | \`.xlsx\`, \`.xls\` | Microsoft Excel spreadsheets | pandas + openpyxl |
| PowerPoint | \`.pptx\` | Microsoft PowerPoint presentations | python-pptx |
| Text | \`.txt\`, \`.md\`, \`.csv\`, \`.json\`, etc. | Plain text files | Built-in |

## Output Format

The script outputs JSON to stdout:

**Success Response (PDF):**
\`\`\`json
{
  "success": true,
  "file_type": "pdf",
  "total_pages": 10,
  "pages": [
    {"page_number": 1, "content": "..."},
    {"page_number": 2, "content": "..."}
  ],
  "full_text": "..."
}
\`\`\`

**Success Response (Word):**
\`\`\`json
{
  "success": true,
  "file_type": "docx",
  "paragraphs": ["段落1", "段落2"],
  "tables": [["表格数据"]],
  "full_text": "..."
}
\`\`\`

**Success Response (Excel):**
\`\`\`json
{
  "success": true,
  "file_type": "excel",
  "sheet_names": ["Sheet1", "Sheet2"],
  "sheets": {
    "Sheet1": {
      "data": [...],
      "columns": ["列1", "列2"],
      "shape": [10, 5]
    }
  }
}
\`\`\`

**Success Response (PowerPoint):**
\`\`\`json
{
  "success": true,
  "file_type": "pptx",
  "total_slides": 15,
  "slides": [
    {"slide_number": 1, "content": "..."},
    {"slide_number": 2, "content": "..."}
  ],
  "full_text": "..."
}
\`\`\`

**Success Response (Text):**
\`\`\`json
{
  "success": true,
  "file_type": "text",
  "encoding": "utf-8",
  "content": "...",
  "lines": ["行1", "行2"],
  "line_count": 100
}
\`\`\`

**Error Response:**
\`\`\`json
{
  "success": false,
  "error": "Error message"
}
\`\`\`

## Dependencies

Install required packages before using:
\`\`\`bash
pip install PyMuPDF python-docx pandas openpyxl python-pptx
\`\`\`

## Notes

- **Automatic Detection**: 根据文件扩展名自动识别文件类型
- **Encoding Support**: 文本文件自动尝试多种编码（UTF-8, GBK, GB2312, Latin1）
- **Error Handling**: 所有错误都会被捕获并返回结构化的JSON格式
- **Logging**: 详细日志输出到stderr，JSON结果输出到stdout
- **PDF Limitations**: 扫描版PDF需要OCR（默认不支持）；加密PDF需要先解密
- **Excel Limitations**: 超大Excel文件可能需要较长时间处理；复杂格式不会被保留
