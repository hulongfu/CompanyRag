package com.company.rag.rag.router;

/**
 * 意图类型枚举
 * 定义用户查询的意图分类
 */
public enum IntentType {
    /**
     * 文档检索意图
     */
    DOCUMENT,

    /**
     * 数据库查询意图
     */
    DATABASE,

    /**
     * 代码查询意图
     */
    CODE,

    /**
     * 普通聊天意图
     */
    CHAT
}
