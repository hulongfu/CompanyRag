package com.company.rag.mcp.client;

import lombok.Getter;

/**
 * MCP 调用异常，保留远端返回的 JSON-RPC 错误码。
 * 用于区分"参数错误(-32602)"与"服务器/网络错误"，供自愈逻辑决策。
 */
@Getter
public class McpToolException extends RuntimeException {

    /** JSON-RPC 标准错误码：无效参数 */
    public static final int CODE_INVALID_PARAMS = -32602;

    private final int errorCode;

    public McpToolException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpToolException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 是否为参数错误（严格协议判定 + 消息关键词兜底） */
    public boolean isParamError() {
        if (errorCode == CODE_INVALID_PARAMS) {
            return true;
        }
        if (getMessage() == null) {
            return false;
        }
        String msg = getMessage().toLowerCase();
        return msg.contains("argument") || msg.contains("parameter")
                || msg.contains("schema") || msg.contains("invalid params");
    }
}