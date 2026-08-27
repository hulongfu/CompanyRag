# Read Document Skill - Usage Examples

## Quick Start

### 1. Read a PDF file
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "document.pdf"
```

### 2. Read a Word document
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "report.docx"
```

### 3. Read an Excel file
```bash
# Read all sheets
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "data.xlsx"

# Read specific sheet
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "data.xlsx" --sheet "Sheet1"
```

### 4. Read a PowerPoint presentation
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "presentation.pptx"
```

### 5. Read a text file
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "notes.txt"
```

## Advanced Usage

### Read specific PDF page
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "document.pdf" --page 5
```

### List supported formats
```bash
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "dummy" --formats
```

## Integration with Deep Agent

When using this skill with Deep Agent, use the `execute` tool:

```python
# Example: Read a PDF file
result = execute('D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/read-document/scripts/read_document.py --file "report.pdf"')

# Parse the JSON result
import json
data = json.loads(result)

if data['success']:
    print(f"File type: {data['file_type']}")
    print(f"Content: {data['full_text']}")
else:
    print(f"Error: {data['error']}")
```

## Error Handling

The skill always returns a JSON object with a `success` field:

- If `success` is `true`, the document was read successfully
- If `success` is `false`, check the `error` field for details

## Dependencies

Make sure to install the required libraries:

```bash
pip install PyMuPDF python-docx pandas openpyxl python-pptx
```
