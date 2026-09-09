# 归档目录说明

本目录存放从仓库根目录收纳的一次性历史修复遗留物，仅供追溯备查，**不参与代码构建**。

| 文件 | 性质 |
|------|------|
| cleanup-env-from-history.sh | 历史 Git 敏感信息清理脚本（GitHub 过滤旧版） |
| cleanup-env-simple.sh | 历史 Git 敏感信息清理脚本（简化版，main 分支） |
| secret-replacements.txt | 密钥替换表达式文件（已清空，仅供说明清理过程） |
| api-key-replacements.txt | API Key 替换表达式遗留（已清空） |
| temp-replacements.txt | 临时替换表达式遗留（已清空） |
| 修复完成报告.md | 2026-07 构建/依赖等修复的过程报告 |
| 最终修复说明.md | Spring AI DashScope 兼容协议的最终修复说明 |
| SESSION_HISTORY_FIX.md | 会话记录丢失问题的修复记录 |
| GenPass.java | 生成 BCrypt 密码的一次性命令行工具 |

> 归档不删除 git 历史，以上文件仍在历史提交中可随时找回。
