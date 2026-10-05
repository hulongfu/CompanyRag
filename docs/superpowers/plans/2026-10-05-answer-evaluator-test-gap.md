# 补全 AnswerCorrectness / AnswerFaithfulness 评估器单测 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `AnswerCorrectnessEvaluator` 与 `AnswerFaithfulnessEvaluator` 两个薄封装评估器补齐直接单元测试，锁定其正常/边界/异常判定语义。

**Architecture:** 纯 JUnit5 + AssertJ（package-private 同包访问）测试，零 Spring 上下文、零外部依赖（无需 PG/Redis）。两个评估器均是该包内已有实现类的薄封装：correctness 量长度/空值/前缀，faithfulness 将 `FaithfulnessChecker` 三态结果映射为布尔（仅 FAITHFUL 通过，UNKNOWN/UNFAITHFUL 均 false，防幻觉优先）。

**Tech Stack:** JUnit 5（`spring-boot-starter-test` 传递引入的 JUnit Jupiter + AssertJ）、Java 17、Maven 多模块。

**设计文档：** `docs/superpowers/specs/2026-10-05-answer-evaluator-test-gap-design.md`（已提交 f02f432）

---

## 文件结构

- 新增（仅在测试目录，不改任何 `src/main`）：
  - `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluatorTest.java`
  - `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluatorTest.java`

两个文件职责独立：一个钉住 correctness 的 {@code answer.hasLength>=10 && 非空 && !startsWith("抱歉")}；一个钉住 faithfulness 的三态→布尔映射与防幻觉降级。

---

### Task 1: AnswerCorrectnessEvaluatorTest

**Files:**
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluatorTest.java`

- [ ] **Step 1: 编写待失败的测试**

被测判定（`AnswerCorrectnessEvaluator.java:22-24`）：
`answer != null && !answer.isBlank() && answer.length() >= 10 && !answer.startsWith("抱歉")`

创建文件 `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluatorTest.java`：

```java
package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AnswerCorrectnessEvaluator 正确性评估测试：钉住「非空、长度≥10、非"抱歉"开头」判定，
 * 覆盖正常通过、长度边界（9/10）与防御性入参（null/空白/空串/抱歉前缀）场景。
 */
class AnswerCorrectnessEvaluatorTest {

    private AnswerCorrectnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerCorrectnessEvaluator();
    }

    // ---------- 正常通过 ----------

    @Test
    void passes_whenAnswerHasSubstance_overMinLength() {
        // 长度远超 10 的中文实质回答 → 通过
        String answer = "Spring 的通知类型共有五种：Before、AfterReturning、AfterThrowing、After、Around。";
        assertTrue(evaluator.evaluate("question", null, answer));
    }

    // ---------- 边界 ----------

    @Test
    void passes_whenAnswerLengthExactlyTen() {
        // 阈值恰为 10 → 通过（>= 10）
        assertTrue(evaluator.evaluate("question", null, "一二三四五六七八九十"));
    }

    @Test
    void fails_whenAnswerLengthNine() {
        // 阈值下方：9 字符 → 不通过
        assertFalse(evaluator.evaluate("question", null, "一二三四五六七八九"));
    }

    @Test
    void passes_whenAnswerEndsWithApologyButLengthOk() {
        // 仅判定前缀开头，不以"抱歉"开头且长度足够 → 通过
        assertTrue(evaluator.evaluate("question", null, "这个报销流程可以，抱歉不适用。"));
    }

    // ---------- 防御性入参 ----------

    @Test
    void fails_whenAnswerIsNull() {
        assertFalse(evaluator.evaluate("question", null, null));
    }

    @Test
    void fails_whenAnswerIsBlank() {
        assertFalse(evaluator.evaluate("question", null, "     "));
    }

    @Test
    void fails_whenAnswerIsEmpty() {
        assertFalse(evaluator.evaluate("question", null, ""));
    }

    @Test
    void fails_whenAnswerStartsWithApology() {
        // "抱歉"前缀 → 不通过（兜底回答）
        assertFalse(evaluator.evaluate("question", null, "抱歉，我不知道该问题的答案。"));
    }

    @Test
    void fails_whenAnswerIsJustApology() {
        // 仅"抱歉"二字：前缀拦截（且长度 2 < 10 双重拦截）
        assertFalse(evaluator.evaluate("question", null, "抱歉"));
    }
}
```

> 说明：correctness 不读取 query / context，测试统一传固定值。

- [ ] **Step 2: 运行测试确认失败**

Run（该测试类当前不存在，会因编译失败而视为红）:
```
cd company-rag-rag && ../scripts/.. >/dev/null; cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest=AnswerCorrectnessEvaluatorTest
```
Expected: FAIL — 找不到 `AnswerCorrectnessEvaluatorTest`（compilation error: cannot find symbol）。

- [ ] **Step 3: 实现最小代码使测试通过**

无需主代码改动——被测类 `AnswerCorrectnessEvaluator` 已存在且逻辑正确。步骤 1 创建的测试即"实现"。

- [ ] **Step 4: 运行测试确认通过**

Run:
```
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest=AnswerCorrectnessEvaluatorTest
```
Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
cd /d/tmp/CompanyRag && git add company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluatorTest.java && git commit -m "test(eval): 补 AnswerCorrectnessEvaluator 单测（长度/空值/抱歉前缀边界）"
```

---

### Task 2: AnswerFaithfulnessEvaluatorTest

**Files:**
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluatorTest.java`

- [ ] **Step 1: 编写待失败的测试**

被测判定（`AnswerFaithfulnessEvaluator.java:29-32`）：`checker.check(answer, context) == FAITHFUL`，即仅 `FAITHFUL` 返回 true；`UNKNOWN`/`UNFAITHFUL` 均返回 false。

底层 `FaithfulnessChecker` 语义（已由 `FaithfulnessCheckerTest` 覆盖）：
- 无 context / 空白 → `UNKNOWN`（`FaithfulnessChecker.java:37-39`）
- 回答 null/空白/`抱歉`开头 → `UNFAITHFUL`（`:40-42`）
- 无 `citations=` 声明 → `UNFAITHFUL`（`:44-46`）
- 二元组正文覆盖 < 0.15 → `UNFAITHFUL`（`:53-63`）
- 覆盖 ≥ 0.15 → `FAITHFUL`

用**真实** `FaithfulnessChecker` 注入（与生产装配一致），使薄封装层的三态→布尔映射与底层判定在整合层面一起被钉住。

创建文件 `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluatorTest.java`：

```java
package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AnswerFaithfulnessEvaluator 忠实度评估测试。
 *
 * <p>核心语义：将 {@link FaithfulnessChecker} 三态判定映射为布尔，仅 FAITHFUL 判通过；
 * UNKNOWN / UNFAITHFUL 一律判不通过（防幻觉优先，布尔接口无法表达三态）。
 * 本测试用真实 checker 注入，与生产装配一致，锁定「UNKNOWN→false」的安全护栏，
 * 防止未来回归为「上下文含 citations= 即忠实」的宽松启发式。
 */
class AnswerFaithfulnessEvaluatorTest {

    private AnswerFaithfulnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerFaithfulnessEvaluator(new FaithfulnessChecker());
    }

    // ---------- 正常：忠实 ----------

    @Test
    void passes_whenAnswerGroundedInContext() {
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 申请测试环境需要联系运维并提交工单";
        assertTrue(evaluator.evaluate("怎么申请测试环境", context, "申请测试环境需要联系运维"));
    }

    // ---------- 三态映射：UNKNOWN / UNFAITHFUL 一律不通过 ----------

    @Test
    void fails_whenContextIsNull() {
        // checker 返回 UNKNOWN（上下文缺失）→ 防幻觉优先判不通过
        assertFalse(evaluator.evaluate("question", null, "任意回答"));
    }

    @Test
    void fails_whenContextIsBlank() {
        // 空白上下文 → UNKNOWN → false
        assertFalse(evaluator.evaluate("question", "   ", "任意回答"));
    }

    @Test
    void fails_whenContextHasNoCitations() {
        // 普通对话上下文无 citations= 声明 → UNFAITHFUL → false（不能判定有据）
        String context = "普通对话内容，没有任何引用来源";
        assertFalse(evaluator.evaluate("question", context, "普通对话回答"));
    }

    @Test
    void fails_whenAnswerNotGroundedInBody() {
        // 有 citations 声明但回答与正文无关 → UNFAITHFUL → false（编造）
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 项目采用 Spring Boot 3.4 与 PGVector 构建";
        assertFalse(evaluator.evaluate("question", context, "今天天气晴朗适合出游"));
    }

    @Test
    void fails_whenAnswerIsBlank() {
        String context = "citations=a.md#0\n[来源:a.md] 相关内容";
        assertFalse(evaluator.evaluate("question", context, "   "));
    }

    @Test
    void fails_whenAnswerStartsWithApology() {
        String context = "citations=a.md#0\n[来源:a.md] 相关知识内容";
        assertFalse(evaluator.evaluate("question", context, "抱歉，暂时无法回答该问题"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest=AnswerFaithfulnessEvaluatorTest
```
Expected: FAIL — 找不到 `AnswerFaithfulnessEvaluatorTest`（compilation error: cannot find symbol）。

- [ ] **Step 3: 实现最小代码使测试通过**

无需主代码改动——被测类 `AnswerFaithfulnessEvaluator` 已存在且逻辑正确。步骤 1 创建的测试即"实现"。

- [ ] **Step 4: 运行测试确认通过**

Run:
```
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest=AnswerFaithfulnessEvaluatorTest
```
Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
cd /d/tmp/CompanyRag && git add company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluatorTest.java && git commit -m "test(eval): 补 AnswerFaithfulnessEvaluator 单测（三态→布尔映射防幻觉）"
```

---

### Task 3: 与既有评估器测试一同做收口回归验证

**Files:**
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluatorTest.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluatorTest.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerRelevancyEvaluatorTest.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/FaithfulnessCheckerTest.java`

- [ ] **Step 1: 只跑该 package 的评估器测试类做收口回归**

Run（仅 4 个相关测试类，不跑全模块/全仓库套件）:
```
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest='Answer*EvaluatorTest,FaithfulnessCheckerTest'
```
Expected: `Tests run: 30`（correctness 9 + faithfulness 7 + relevancy 7 + checker 7），Failures/Errors/Skipped 均为 0，BUILD SUCCESS。

- [ ] **Step 2: 确认改动范围干净**

Run:
```
cd /d/tmp/CompanyRag && git status -s
```
Expected: 仅显示 `?? data/`（既有未跟踪目录，与本任务无关）；本次新增的 2 个测试文件已被提交，`src/main` 无任何改动。

- [ ] **Step 3: 提交（如无新增改动则跳过）**

若 Step 1/2 没有产生新的未提交改动，则本步骤无需操作；否则按前两步异常修正后提交。

---

## 自审

**1. 规范覆盖检查**
- correctness 判定（非空/长度≥10/抱歉前缀）→ Task 1 用例 1-9。
- faithfulness 三态→布尔、UNKNOWN→false 防幻觉 → Task 2 用例 1-7。
- 零主代码改动、无 CI、无 DB/Redis → 文件结构 & 验证命令满足。
- 与既有测试风格一致（同包、真实实例化）→ Task 1/2 代码遵循 `AnswerRelevancyEvaluatorTest` 模式。

**2. 占位符扫描**：无 TBD/TODO/"待补"/省略号代码。每个 step 均有完整代码与精确命令。

**3. 类型/语义一致性**：两个测试均构造同类构造器（无参 / `new FaithfulnessChecker()`），方法签名 `evaluate(String,String,String)` 与 `dimensionName()` 相关断言一致；边界值 9/10、thre的 check 返回 Verdict 枚举映射与 `FaithfulnessChecker` 源码逐一对应。

**4. 验证范围**：仅跑 `company-rag-rag` 模块内 4 个测试类（含本任务新增 2 个），不跑全模块/全仓库套件，符合"最窄相关验证"约束。
