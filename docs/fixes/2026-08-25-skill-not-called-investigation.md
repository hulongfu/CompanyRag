# Skill 调用问题调查与修复

## 问题描述
Agent 现在只能调用工具（包括本地工具与外部 MCP 服务器中的工具），无法调用技能。

## 调查过程（Systematic Debugging Phase 1）

### 1. 检查配置
✅ `application-dev.yml` 中有 Skill Registry 配置：
```yaml
spring:
  ai:
    alibaba:
      agent:
        skill:
          registry:
            file-system:
              enabled: true
              paths:
                - ./agent_skills
```

✅ `pom.xml` 中有依赖：
```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    <version>1.1.2.0</version>
</dependency>
```

✅ `./agent_skills/calculator/SKILL.md` 存在

### 2. 检查 ReactAgent 配置
❌ **问题所在**：`AgentConfig.java` 中的 ReactAgent builder 只配置了：
- `name("rag-agent")`
- `model(chatModel)`
- `toolCallbackProviders(toolCallbackProvider)`

**没有配置 SkillsAgentHook！**

### 3. Spring AI Alibaba Skills API 分析

通过反编译 JAR 包发现：
- `SkillsAgentHook` 类在 `com.alibaba.cloud.ai.graph.agent.hook.skills` 包中
- `FileSystemSkillRegistry` 类在 `com.alibaba.cloud.ai.graph.skills.registry.filesystem` 包中
- `SkillsAgentHook.Builder` 需要 `skillRegistry(SkillRegistry)` 参数，而不是 `skillsDirectory(Path)`

### 4. 根因

**ReactAgent 没有配置 SkillsAgentHook**，导致 Agent 无法发现和调用技能。

设计文档中提到的 `FileSystemSkillRegistry` 和 `SkillsAgentHook` 确实存在于 Spring AI Alibaba 1.1.2.0 版本中，但需要手动配置，而不是通过 Spring Boot 自动配置。

## 修复方案

修改 `AgentConfig.java`：

1. 创建 `FileSystemSkillRegistry`，扫描 `./agent_skills` 目录
2. 创建 `SkillsAgentHook`，注入 `skillRegistry`
3. 将 `skillsHook` 添加到 ReactAgent builder 的 `hooks()` 列表中

## 修复后的代码

```java
@Bean
public ReactAgent reactAgent(ChatModel chatModel, ToolCallbackProvider toolCallbackProvider) {
    // 创建 FileSystemSkillRegistry
    FileSystemSkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
            .userSkillsDirectory("./agent_skills")
            .build();
    
    // 创建 SkillsAgentHook
    SkillsAgentHook skillsHook = SkillsAgentHook.builder()
            .skillRegistry(skillRegistry)
            .build();
    
    return ReactAgent.builder()
            .name("rag-agent")
            .model(chatModel)
            .toolCallbackProviders(toolCallbackProvider)
            .hooks(List.of(skillsHook))  // 添加 SkillsAgentHook
            .enableLogging(true)
            .build();
}
```

## 验证

✅ 编译成功
⏳ 需要运行时测试验证 Skill 调用是否正常工作

## 下一步

1. 启动应用，检查日志中是否显示 Skills 目录扫描成功
2. 测试发送"计算 100 * 25"请求，验证 Agent 是否能调用 calculator Skill
3. 检查日志中是否有 SkillsAgentHook 的执行记录
