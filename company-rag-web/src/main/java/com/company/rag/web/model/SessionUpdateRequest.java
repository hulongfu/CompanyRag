package com.company.rag.web.model;

import lombok.Data;
import java.util.List;

/**
 * 更新会话请求
 */
@Data
public class SessionUpdateRequest {
    private String title;
    private List<String> tags;
}