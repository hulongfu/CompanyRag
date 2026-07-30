package com.company.rag.rag.router;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 模式规则类
 * 用于定义意图匹配的正则表达式规则
 */
@Data
@Builder
public class PatternRule {
    
    /**
     * 意图类型
     */
    private IntentType intent;
    
    /**
     * 正则表达式模式列表
     */
    private List<String> patterns;
    
    /**
     * 置信度（0.0 - 1.0）
     */
    private Double confidence;
    
    /**
     * 编译后的正则表达式模式（用于高效匹配）
     */
    private List<Pattern> compiledPatterns;
    
    /**
     * 构建并编译规则
     * 将字符串模式编译为 Pattern 对象，设置 CASE_INSENSITIVE 标志
     * 
     * @return 编译后的规则
     */
    public PatternRule buildAndCompile() {
        if (this.patterns != null && !this.patterns.isEmpty()) {
            this.compiledPatterns = this.patterns.stream()
                .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                .toList();
        }
        return this;
    }
    
    /**
     * 检查查询是否匹配此规则
     * 只要有一个模式匹配即返回 true
     * 
     * @param query 用户查询
     * @return 是否匹配
     */
    public boolean matches(String query) {
        if (compiledPatterns == null || compiledPatterns.isEmpty()) {
            return false;
        }
        
        return compiledPatterns.stream()
            .anyMatch(pattern -> pattern.matcher(query).find());
    }
}
