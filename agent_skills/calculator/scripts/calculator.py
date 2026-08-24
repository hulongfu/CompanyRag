#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
计算器脚本 - 支持加减乘除四则运算

用法：
    python calculator.py <expression>

示例：
    python calculator.py "50 + 50"
    python calculator.py "100 * 25"
    python calculator.py "80 / 4"
    python calculator.py "10 - 3"
"""

import sys
import re


def calculate(expression: str) -> str:
    """
    计算数学表达式
    
    Args:
        expression: 数学表达式字符串，如 "50 + 50"
        
    Returns:
        计算结果字符串
        
    Raises:
        ValueError: 当表达式无效时
    """
    # 移除空格
    expression = expression.strip()
    
    # 只允许数字、小数点和基本运算符
    if not re.match(r'^[\d\s\+\-\*\/\.\(\)]+$', expression):
        raise ValueError(f"无效的表达式：{expression}")
    
    try:
        # 使用 eval 计算（在受控环境中）
        # 注意：在生产环境中应该使用更安全的解析方式
        result = eval(expression)
        return str(result)
    except ZeroDivisionError:
        raise ValueError("错误：除数不能为零")
    except Exception as e:
        raise ValueError(f"计算错误：{str(e)}")


def main():
    if len(sys.argv) < 2:
        print("错误：请提供数学表达式")
        print("用法：python calculator.py <expression>")
        print("示例：python calculator.py \"50 + 50\"")
        sys.exit(1)
    
    # 合并所有参数作为表达式
    expression = " ".join(sys.argv[1:])
    
    try:
        result = calculate(expression)
        print(result)
    except ValueError as e:
        print(str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
