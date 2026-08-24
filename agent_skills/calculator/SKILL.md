---
name: calculator
description: 计算器技能，支持加减乘除四则运算
read_when:
  - User wants to calculate mathematical expressions
  - User asks for arithmetic operations
  - User needs to perform basic math calculations
---

# Calculator Skill

## Usage

当用户请求计算时，使用以下命令：
```bash
python scripts/calculator.py [expression]
```

## Examples

**User:** "What is 50 + 50?"

**Agent Thought:**
- I need to calculate 50 + 50
- I will use the calculator skill
- Command: `python scripts/calculator.py 50 + 50`

**Tool Call:**
- Name: `execute`
- Args: `{"command": "python scripts/calculator.py 50 + 50"}`

**Result:** "100"

**User:** "计算 100 * 25"

**Agent Thought:**
- 用户需要计算 100 * 25
- 使用 calculator 技能
- Command: `python scripts/calculator.py 100 * 25`

**Tool Call:**
- Name: `execute`
- Args: `{"command": "python scripts/calculator.py 100 * 25"}`

**Result:** "2500"
