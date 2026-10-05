# 补全 AnswerCorrectness / AnswerFaithfulness 评估器单测 — 设计文档

> 状态：待审阅（draft）
> 日期：2026-10-05
> 背景：近期"答案质量闭环"迭代（answer-quality-loop）新增了两个薄封装评估器
> `AnswerCorrectnessEvaluator` 与 `AnswerFaithfulnessEvaluator`，但二者目前没有专属单测，
> 在 `AnswerEvaluationServiceTest` 中仅被 mock。本设计为其补齐正常/边界/异常三类直接单测。

---

## 1. 背景与问题

### 1.1 现状证据

| 实现类 | 现有测试 |
|--------|---------|
| `AnswerRelevancyEvaluator` | `AnswerRelevancyEvaluatorTest`（7 用例，直接单测） |
| `FaithfulnessChecker` | `FaithfulnessCheckerTest`（7 用例，直接单测） |
| `AnswerCorrectnessEvaluator` | **无专属测试**，仅在 `AnswerEvaluationServiceTest` 被 mock |
| `AnswerFaithfulnessEvaluator` | **无专属测试**，仅在 `AnswerEvaluationServiceTest` 被 mock |

`AnswerEvaluationServiceTest` 中两个评估器均为 `mock(...)`（第 37-38 行），
即评估器自身的判定逻辑没有任何直接覆盖。

### 1.2 两个评估器的判定逻辑（已从源码核实）

**AnswerCorrectnessEvaluator（`MIN_ANSWER_LENGTH = 10`）**
- 通过条件：`answer != null && !answer.isBlank() && answer.length() >= 10 && !answer.startsWith("抱歉")`

**AnswerFaithfulnessEvaluator（委托 `FaithfulnessChecker`）**
- 三态 → 布尔映射：仅 `FAITHFUL` 判 true；`UNKNOWN` / `UNFAITHFUL` 均判 false（防幻觉优先，布尔接口无法表达三态）
- 关键风险点：默认 `context == null` 时 `checker.check` 返回 `UNKNOWN`，必须映射为 **false**

### 1.3 缺口定性

- 长度下限、`抱歉` 前缀、空/空白/null 回答、三态映射这几条**独立判定语义**未被钉住；
- 这两个类是防幻觉（faithfulness）与实质内容（correctness）质量的直接载体，回归风险存在于薄封装逻辑本身。

---

## 2. 方案范围（方案 A，已确认）

新增两个纯 JUnit 单测文件，放在被测类的同包下，风格与既有 `AnswerRelevancyEvaluatorTest` 一致：

```
company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/
├── AnswerCorrectnessEvaluatorTest.java    （新增）
└── AnswerFaithfulnessEvaluatorTest.java   （新增）
```

- 零 Spring 上下文（两个评估器构造无需依赖注入 Spring Bean），本地可直接 `mvn test` 运行；
- 不依赖 PG / Redis，不触碰 CI（符合用户"暂不搭 CI"约定）；
- 不修改任何主代码（`src/main`），纯新增测试。

---

## 3. 测试用例设计

### 3.1 AnswerCorrectnessEvaluatorTest

用 `new AnswerCorrectnessEvaluator()` 直接实例化，`@BeforeEach` 建立实例。

**正常通过**
1. 长度 ≥10 的非 `抱歉`中文回答 → true

**边界（关键）**
2. 长度恰好 = 10 → true
3. 长度恰好 = 9 → false（阈值下方）
4. 长度恰好 = 10 且以"抱歉"结尾 → true（仅关注前缀，token 未命中）

**异常/防御**
5. `answer == null` → false
6. 空白串 `"   "` → false（isBlank 拦截）
7. 空串 `""` → false
8. 以"抱歉"开头 → false
9. 仅"抱歉"两字 → false（前缀 + 长度双重拦截）

> 说明：query / context 参数对本评估器无影响，固定传 `null`。

### 3.2 AnswerFaithfulnessEvaluatorTest

用真实 `FaithfulnessChecker` 注入构造 `AnswerFaithfulnessEvaluator`（保持与生产装配一致，
同时让 `FaithfulnessCheckerTest` 已覆盖的底层判定在薄封装层得到回归验证）。

**正常通过（FAITHFUL → true）**
1. 回答扎根于带 `citations=` 的正文，且二元组覆盖 ≥ 0.15 → true

**三态映射（防幻觉核心）**
2. `context == null`（checker 返回 UNKNOWN）→ **false**
3. `context` 为空白 → **false**
4. `context` 无 `citations=` 声明 → **false**
5. 回答未扎根于正文（checker 返回 UNFAITHFUL）→ **false**
6. 回答为空白 / `抱歉`开头（UNFAITHFUL）→ **false**

> 以上用例直接锁定"仅 FAITHFUL 通过、UNKNOWN/UNFAITHFUL 均 false"的布尔映射，
> 防止未来改回宽松启发式（如"上下文含 citations= 即忠实"）造成防幻觉回归。

---

## 4. 验证方式

在 `company-rag-rag` 模块内做最小范围验证（仅跑新增/相关测试类）：

```bash
mvn -pl company-rag-rag test -Dtest=AnswerCorrectnessEvaluatorTest,AnswerFaithfulnessEvaluatorTest,AnswerRelevancyEvaluatorTest,FaithfulnessCheckerTest
```

验收判据：
- 上述 4 个测试类 0 失败、0 错误；
- `src/main` 无任何改动；`git status` 仅新增 2 个测试文件（`data/` 为既有未跟踪目录，与本任务无关）。

---

## 5. 风险与注意事项

- **风险点（涉及防幻觉语义）**：`AnswerFaithfulnessEvaluator` 的 UNKNOWN→false 映射是安全护栏。
  测试用例 2/3/4 明确钉住该行为，防止后续误改为宽松通过。
- 本任务为纯测试补全，无数据库/权限/敏感信息改动面。

---

## 6. 不做的事（YAGNI）

- 不搭建 CI/CD、不引入覆盖率门禁（已由用户明确延后）。
- 不改主代码、不重构 `AnswerEvaluationService`、不新增评估维度。
- 不修改 `FaithfulnessChecker`（其底层逻辑已被 `FaithfulnessCheckerTest` 保护）。
