/*
 * 本文件已迁移并激活至公司级真库集成测试：
 *   company-rag-bootstrap/src/test/java/com/company/rag/bootstrap/MultiRetrieveIntegrationIT.java
 *
 * 原 rag 模块版本为 @Disabled 模板（rag 模块无完整 Spring Boot 上下文，
 * 无法加载 PG / Redis / 外网 embedding）。现由 bootstrap 模块的
 * MultiRetrieveIntegrationIT（@IntegrationTest + -Dit.pg=true 守卫）承接，
 * 供 scripts/run-it.sh --all 一键回归。此占位文件保留以说明迁移去向，避免追溯歧义。
 */
package com.company.rag.rag.retriever;
