package com.company.rag.document.splitter;

/**
 * 切分策略枚举
 */
public enum SplitStrategy {
    FIXED_SIZE,          // 固定大小切分
    RECURSIVE,           // 递归字符切分
    SLIDING_WINDOW,      // 滑动窗口切分
    SEMANTIC_CHUNK,      // 语义边界切分（RSE风格）
    ADAPTIVE             // 自适应（根据内容类型自动选择）
}
