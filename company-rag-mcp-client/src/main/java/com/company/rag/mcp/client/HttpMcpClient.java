package com.company.rag.mcp.client;

import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.company.rag.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Spring WebClient 的 MCP 客户端实现
 * 使用 HTTP + JSON-RPC 2.0 协议与外部 MCP Server 通信
 */
@Slf4j
public class HttpMcpClient implements McpClient {
    
    private final String clientId;
    private final String serverUrl;
    private final int timeout;
    private final Map<String, String> headers;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    
    public HttpMcpClient(String clientId, String serverUrl, int timeout, Map<String, String> headers) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
        this.timeout = timeout;
        this.headers = headers != null ? headers : Collections.emptyMap();
        
        this.webClient = WebClient.builder()
                .baseUrl(serverUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1MB
                .build();
        
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String getClientId() {
        return clientId;
    }
    
    @Override
    public void connect() {
        if (connected.compareAndSet(false, true)) {
            log.info("MCP Client [{}] 正在连接到服务器：{}", clientId, serverUrl);
            // 发送 initialize 请求
            sendInitializeRequest();
            // 发送 notifications/initialized 通知
            sendInitializedNotification();
            log.info("MCP Client [{}] 连接成功", clientId);
        }
    }
    
    @Override
    public void disconnect() {
        if (connected.compareAndSet(true, false)) {
            log.info("MCP Client [{}] 已断开连接", clientId);
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected.get();
    }
    
    @Override
    public List<McpToolDefinition> listTools() {
        try {
            // 构建 tools/list 请求
            JsonRpcRequest request = new JsonRpcRequest();
            request.setJsonrpc("2.0");
            request.setMethod("tools/list");
            request.setId(generateRequestId());
            request.setParams(null);
            
            JsonRpcResponse response = sendRequest(request);
            
            if (response.getError() != null) {
                throw new RuntimeException("获取工具列表失败：" + response.getError().getMessage());
            }
            
            return convertToToolDefinitions(response.getResult());
        } catch (Exception e) {
            log.error("MCP Client [{}] 获取工具列表失败", clientId, e);
            throw new RuntimeException("获取工具列表失败：" + e.getMessage(), e);
        }
    }
    
    @Override
    public Object callTool(String toolName, Map<String, Object> params) {
        try {
            // 构建 tools/call 请求
            JsonRpcRequest request = new JsonRpcRequest();
            request.setJsonrpc("2.0");
            request.setMethod("tools/call");
            request.setId(generateRequestId());
            
            // 创建 JsonRpcParams
            JsonRpcRequest.JsonRpcParams jsonRpcParams = new JsonRpcRequest.JsonRpcParams();
            jsonRpcParams.setName(toolName);
            jsonRpcParams.setArguments(params != null ? params : Map.of());
            request.setParams(jsonRpcParams);
            
            JsonRpcResponse response = sendRequest(request);
            
            if (response.getError() != null) {
                throw new RuntimeException("调用工具 " + toolName + " 失败：" + response.getError().getMessage());
            }
            
            return response.getResult();
        } catch (Exception e) {
            log.error("MCP Client [{}] 调用工具 {} 失败", clientId, toolName, e);
            throw new RuntimeException("调用工具 " + toolName + " 失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 生成请求 ID
     */
    private String generateRequestId() {
        return "req-" + System.currentTimeMillis() + "-" + clientId;
    }
    
    /**
     * 发送 initialize 请求
     */
    private void sendInitializeRequest() {
        try {
            JsonRpcRequest request = new JsonRpcRequest();
            request.setJsonrpc("2.0");
            request.setMethod("initialize");
            request.setId(generateRequestId());
            request.setParams(Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientId, "version", "1.0.0")
            ));
            
            JsonRpcResponse response = sendRequest(request);
            if (response.getError() != null) {
                log.warn("MCP Client [{}] initialize 失败：{}", clientId, response.getError().getMessage());
            }
        } catch (Exception e) {
            log.warn("MCP Client [{}] initialize 请求失败", clientId, e);
        }
    }
    
    /**
     * 发送 notifications/initialized 通知（不带 id）
     */
    private void sendInitializedNotification() {
        try {
            JsonRpcRequest request = new JsonRpcRequest();
            request.setJsonrpc("2.0");
            request.setMethod("notifications/initialized");
            request.setId(null); // 通知不带 id
            request.setParams(null);
            
            // 发送通知（不等待响应）
            String requestBody = objectMapper.writeValueAsString(request);
            webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("MCP Client [{}] initialized 通知失败", clientId, e);
        }
    }
    
    /**
     * 发送 JSON-RPC 请求
     */
    private JsonRpcResponse sendRequest(JsonRpcRequest request) {
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            log.debug("MCP Client [{}] 发送请求：{}", clientId, requestBody);
            
            String responseBody = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            log.debug("MCP Client [{}] 接收响应：{}", clientId, responseBody);
            
            return objectMapper.readValue(responseBody, JsonRpcResponse.class);
        } catch (WebClientResponseException e) {
            log.error("MCP Client [{}] HTTP 请求失败：{} - {}", clientId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("HTTP 请求失败：" + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            log.error("MCP Client [{}] JSON 处理失败", clientId, e);
            throw new RuntimeException("JSON 处理失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 将结果转换为工具定义列表
     */
    @SuppressWarnings("unchecked")
    private List<McpToolDefinition> convertToToolDefinitions(Object result) {
        if (result == null) {
            return Collections.emptyList();
        }
        
        try {
            // MCP 服务器返回格式：{"tools": [...]}
            // 先提取 tools 字段
            if (result instanceof Map) {
                Map<?, ?> resultMap = (Map<?, ?>) result;
                Object toolsObj = resultMap.get("tools");
                if (toolsObj instanceof List) {
                    List<?> toolsList = (List<?>) toolsObj;
                    List<McpToolDefinition> toolDefinitions = new java.util.ArrayList<>();
                    for (Object tool : toolsList) {
                        if (tool instanceof Map) {
                            // 将 Map 转换为 JSON 字符串，再反序列化为 McpToolDefinition
                            String toolJson = objectMapper.writeValueAsString(tool);
                            McpToolDefinition definition = objectMapper.readValue(toolJson, McpToolDefinition.class);
                            toolDefinitions.add(definition);
                        }
                    }
                    return toolDefinitions;
                }
            }
            
            // 如果结果已经是 McpToolDefinition 列表，直接返回（向后兼容）
            if (result instanceof List) {
                List<?> list = (List<?>) result;
                if (!list.isEmpty() && list.get(0) instanceof McpToolDefinition) {
                    return (List<McpToolDefinition>) list;
                }
            }
            
            // 否则从 JSON 转换
            String json = objectMapper.writeValueAsString(result);
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, McpToolDefinition.class));
        } catch (Exception e) {
            log.error("转换工具定义列表失败", e);
            throw new RuntimeException("转换工具定义列表失败：" + e.getMessage(), e);
        }
    }
}
