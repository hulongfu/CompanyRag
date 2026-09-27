# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计 v5.11

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅（v5.11 整合 1红+3黄项）
> 版本变更：
> - v5.11（第14轮审阅）：①🔴**§3.6 表格「现状」列是 v5.9 旧文，与 v5.10 自己的 §6 指令打架**：那一行仍写「配置只出现在 application-dev.yml:80，application.yml 基段/prod/test 均无」，而未写 v5.10 已在 §6 要求的「基段必须含 enabled:true」——实现者读到 §3.6 会得到「基段不该有 rag.eval」的结论，与 §6 相反的指引。**改「现状」列为目标状态**：基段 `rag.eval` 含 `enabled:true`+`rule-version:v1.0`；并写明按 Spring **property 级合并**（非 map 覆盖），dev/prod/test 未书写的 rule-version 会从基段继承，**三 profile 均拿到 v1.0、启动不崩**（顺带核实编码现状：application.yml rag 段 :121-138 仅 agent/memory、dev :79-82 有 rag.eval、prod:66 / test:47 无 eval 子节点）；**同主题的 §4:385 风险表「现状」列「`rag.eval` 只在 application-dev.yml」一并改标"v5.9 及以前"**（对策列已一致，防现状列再造矛盾）。②🟡**§5:331「未计入 migratedCount」无观测点**：migratedCount 是 migrateRagSessionFeedbackColumn:46 的**方法内局部变量**，lambda 跑完即弃、唯一出口是 :91 那句 log.info——断言只能打日志文本。**钉死：以 ListAppender 挂该 runner Logger 捕获 :91 输出断言，不得改成字段/返回值**。③🟡**§3.1:68 结构两句措辞打架**：「CREATE INDEX 落 if/else 之外」 vs「:82-83 只能在 else 分支内」指向两种落法。**统一为一句**：「CREATE INDEX 位于循环体内、if/else 语句之后（等价于 else 块最后一句）」，未迁移 else 走 ALTER→CREATE INDEX、已迁移 if(仅 skippedCount++) 同落一个 CREATE INDEX。④🟡**唯一 DDL 源方法名全文两个名字**：`buildEvalRegressionReportSql`（:180/:186/:365）vs `EvalRegressionReportDdl.build`（:183/:184/:347）。**统一为 `EvalRegressionReportDdl.build(schemaName)`**（类已存在）。
> - v1→v2：整合首轮 R1-R3 / M1-M7。
> - v2→v3：XML→@Select、b 降观察项改 ignoreTable、dataset_fingerprint、F1 退化修正、显式租户、同源校验。
> - v3→v4：schema 由服务端推导防 IDOR、rule_version、空样本不落快照、doEvaluate 三维、三维一致率公式、online-enabled 前置。
> - v4→v5：整合四轮审阅——①schema 改取 `TenantContext.getSchema()`（**引用既有 JwtAuthenticationFilter 服务端反查机制**，废止错误的 `tenant_`+tenantId 推导）；②doEvaluate 新增指定 record `EvalDecision`（布尔维度），落库侧沿用 `dimensionScores(Map<String,Double>)`；③SchemaMigrationConfig 明确新增同范式 runner + init.sql 位置约束；④rule_version 改为配置项；⑤30s 锁语义钉死；⑥limit 缺省/透传；⑦指纹改 Java 侧计算；⑧补 persisted_pass_agree；⑨s.tenant_id 断言；⑩/history 分页+二级排序；⑪多项细节。
> - v5→v5.1（五轮审阅）：①阻断项——迁移语义**收敛为一条路线**（init.sql 业务表模板段 :82-181 是块注释**从不执行**；建表一律由 runner 执行，init.sql 仅注释存档，避免 `<schema>` DDL 在 psql 阶段建到 public 并撞 :146 sequence 授权）；②黄项1——补 `EvalDecision → AnswerEvalResult` 唯一还原规则（pass=三维与、score=均值、dimensionScores 全名键 1.0/0.0）；③黄项2——新增 `EvalProperties @ConfigurationProperties("rag.eval")` 统一绑定（废 `model.maxDatasetLimit`，复用既有 200 硬编码规则）；④黄项3——**本期单实例假设**，进程内 ConcurrentHashMap 锁，多副本升级 Redisson RLock；⑤绿项——feedback SMALLINT DEFAULT 0 无 NULL、200 样本返回体/内存截断口径、快照 WITH CHECK RLS best-effort 说明。
> - v5.2（第5轮审阅）：①**阻断项——LIMIT 冻结**——`DISTINCT ON` 内层输出必按 `session_row_id` 升序，直接 `LIMIT n` 取最老样本、数据集首跑即冻结；改外层 `ORDER BY t.create_time DESC` 再 `LIMIT`，取最新 n 会话。②黄——迁移语义澄清"**DDL 单一定义、两处引用**"（废"唯一执行入口"歧义；`buildEvalRegressionReportSql` 唯一源，createTenantSchema 与 runner 共用；补**列清单一致性断言**，因 answer_eval_result 已双份漂移且 grant 口径不一）。③黄——`/dataset` `/regression` `/history` **共用 schema 校验**（`/history` 走自定义 @Select、表不在 ignoreTable、schema 空行为未定义）。④黄——**0 样本响应语义钉死**（HTTP 200 + 提示字段，不落快照）。⑤黄——`/regression` 缺省 limit **= 50 同 /dataset**，缺省后原样透传。⑥绿——DTO 补 `evalId` 防 SELECT 列静默丢弃；`EvalDecision.pass == allPass` 改测试断言而非各自推导；快照表 DDL 本期仅 SELECT/INSERT、保留策略补 DELETE 授权写清；标题/状态统一 v5.2。
> - v5.3（第6轮审阅）：①黄——**0 样本响应字段名/code 修正**：`R` 仅 code/msg/data，`R.ok` 里 code=200、msg="success"；改 `R.ok(null).setMsg("无匹配样本，未落快照")`，响应 `{"code":200,"msg":...,"data":null}`；**code=200 是区分空结果/未落快照的唯一依据**（原写 code=0/message= 三处皆错）。②黄——**"列清单一致性断言"为空断言**：DDL 已收单一常量、两路径必然相同，`information_schema.columns` 比对只得并集；改"两处 DDL 片段逐字符相同"哨兵（当前自动成立，仅防未来分叉）。③黄——**feedback 引用源修正**：真实定义在 `SchemaMigrationConfig.migrateRagSessionFeedbackColumn` 的 `ALTER ... feedback SMALLINT NOT NULL DEFAULT 0`（:70），勿引 init.sql:140（块注释内、无 NOT NULL）。④未钉死——**tenantId 单源钉死为 `TenantContext.getTenantId()`**（null→400），与 schema 同源（JWT filter 同一 token claims 双写，:81,86），不取 `@RequestHeader X-Tenant-Id`（既有四接口用 header、可能分叉）。⑤未钉死——`EvalProperties` **不加 @ConditionalOnProperty**，对齐 ApprovalProperties/RerankConfigProperties 先例（两者均无该注解），enabled 门控留在 EvalController/AnswerEvaluationService。⑥绿——新增第 6 份 runner 建议抽公共 `forAllTenantSchemas` helper（不重构既有 5 份）；`/dataset` 显式 limit≤0 回落 50；`/history` 分页响应结构钉死（records/total/size/current）。
> - v5.4（第7轮审阅）：①**阻断项——「唯一 DDL 源」模块归属**：原只写 `buildEvalRegressionReportSql` 未定模块，而 tenant 仅依赖 common、bootstrap 经 `bootstrap→web→tenant` 才达 tenant——放 tenant 虽技术可过（CompanyRagApplication:143 已跨边用 TenantService）但依赖传递脆弱；**钉死为 common 模块 `EvalRegressionReportDdl.public static String build(schemaName)`**（tenant/rag 直接依赖、bootstrap 走 web 可达，两条路径同源）。②**阻断项——EvalProperties 缺注册注解**：全项目无 @ConfigurationPropertiesScan/@EnableConfigurationProperties，属性类仅靠 @Component/@ComponentScan("com.company.rag")():32) 注册；只写前缀注解 → 不绑定 → rule_version=null → 撞 NOT NULL → 落快照 500；**补 `@Component`（对齐 ApprovalProperties）**。③黄——**/history 的 total 无实现路径**：TenantMyBatisPlusConfig 仅 TenantSchemaInterceptor+TenantLineInnerInterceptor、无 PaginationInnerInterceptor，自定义 @Select 也不走 MP 分页 → total 恒 0、LIMIT 不注入返回全量；**钉死手写 LIMIT/OFFSET + 独立 count @Select，不加全局分页插件**（避改共享插件）。④黄——**rag.eval.* 只在 application-dev.yml**：application.yml:121 的 rag 段无 eval 子节点，prod/test 无；**挂 application.yml 基段 + 字段 @DefaultValue + rule-version 启动断言非空**。⑤黄——**feedback 只进 runner、不进 buildCreateTableSql**：TenantServiceImpl:209-221 的 rag_session 无 feedback，新租户建 schema 不跑 runner → updateFeedback 与 s.feedback 报 column does not exist；**建表补 `feedback SMALLINT NOT NULL DEFAULT 0`（仅影响新建租户）**。⑥黄——**「表级仅 SELECT,INSERT」与代码不符**：createTenantSchema 步骤6 有 blanket GRANT...ON ALL TABLES + ALTER DEFAULT PRIVILEGES，新表（步骤2 早于步骤6）自动被授权含 DELETE；runner 路径亦被 ALTER DEFAULT PRIVILEGES 覆盖——**改 DDL 的 SELECT/INSERT 为冗余加固、§7 纠偏「已具 DELETE，保留策略无须补」**。⑦绿——§3.4 表格行 page 缺省「对标/results 50」与「缺省 1/50」冲突 → 统一 1/50；行170「成立,仅」半角逗号 → 全角。
> - v5.5（第8轮审阅）：①**阻断项——`@DefaultValue` 本项目不可用**：实测 `@Target({PARAMETER, RECORD_COMPONENT})` 不含 FIELD，字段上写编译不过；**改字段初始值（对齐 ApprovalProperties:20-26）**；`rule-version` **不能**给初始值，保持 null 让 `@PostConstruct` 拦截漏配。②**阻断项——§3.6 前置条件按键写错**：真正决定 `/api/eval/dataset|regression|history` 是否存在的是 **`rag.eval.enabled`**（EvalController:30 / AnswerEvaluationService:28 / 三 evaluator 类级 `@ConditionalOnProperty(name="rag.eval.enabled")`，无 matchIfMissing），只配在 application-dev.yml:80 → prod/test 下**全 404**；`online-enabled` 只管 ChatController:56 在线抽样。**§6 落地补 `enabled: true` 基段**。③黄——**feedback 补列漏索引防御**。④黄——**persisted_pass_agree 来源钉死 = dataset 返回 `autoPass(=e.pass)`**，不按 eval_id 回查。⑤绿——标题 v5.3→v5.5；ChatController 标 `company-rag-web`。
> - v5.6（第9轮审阅）：①**阻断项——存量租户拿不到 feedback 索引**：`CREATE INDEX idx_%s_session_feedback` 在 `columnExists→continue` 分支内（SchemaMigrationConfig:62-80）→ 存量列已存在 → continue → 索引永远建不上；**移出 continue 分支**（断言改「存量+新建均含索引」）。②**阻断项——索引落错方法 = 建租户运行时炸**：`buildCreateTableSql`(23 实参) 与 `buildCreateIndexSql`(22 实参) 独立算占位符——**钉死落 `buildCreateIndexSql` 从 22 补到 24 个 schemaName 实参**，严禁写进 buildCreateTableSql（否则 %s 数≠实参 → MissingFormatArgumentException → 建租户整段失败）。③黄——`EvalRegressionReportDdl` **职责收敛**：只管 `eval_regression_report`，feedback 列走 buildCreateTableSql、索引走 buildCreateIndexSql。④黄——**/regression 越界 ≤200 未钉**：补表格 + §5 用例 + §6 控制器，与 /dataset 统一。⑤黄——**字段名 `lockTimeoutMs` 与键 `regression-lock-timeout-ms` relaxed 不匹配** → 改 `regressionLockTimeoutMs` + §5 显式覆盖断言。
> - v5.7（第10轮审阅）：①**阻断项——runner 修复措辞「无条件执行」会做反**：字面把 CREATE INDEX 提到循环体最前 → 未迁移租户先 CREATE INDEX 报 `column "feedback" does not exist` → 跳进 per-schema catch{log.error;}(:81-85) → 列也没加、索引也没建、runner 照常跑下一 schema、应用照常启动——恰是 v5.6 要救的那批 schema，静默失败没人知道。**措辞改死：「CREATE INDEX 必须位于 if/else（列已存在则跳过 / 否则先 ALTER ADD COLUMN）之后，明令不得早于 ALTER」**——即仅把 CREATE INDEX 从 continue 分支移出、保持执行顺序在 ALTER 块之后；未迁移租户先 ALTER 补列再建索引，已迁移租户列在则跳过 ALTER 但仍建索引。**理由**：CREATE INDEX 依赖 feedback 列存在（IF NOT EXISTS 只防已存在，不防列缺失）；且新建 fail 会被 catch 吞、不阻断启动，Silent。②黄——**锁落点自相矛盾**：§3.4(:262) 写「Service 层按 tenantId 键锁」，§6 web(:336) 把「单实例 tenant 键锁 + tryLock 30s→409」挂在 EvalController。**钉死 Service 层**：锁与 tryLock 在 `AnswerEvaluationService.regression()`（Service 持有锁 + ConcurrentHashMap），Controller 仅转发 409；§6 web 去掉锁描述、§5 并发用例对齐 Service 层（不再写 Controller 加锁）。③黄——**新端点更严但既有接口是真缺口**：既有 /result|results|stats（EvalController:55/66/81）是 `@PreAuthorize("isAuthenticated()")`，viewer（DocumentController:51 有 VIEWER 角色）可读 query/answer 原文——与新端点 ADMIN/USER 不一致；§7 M5 的「防 viewer 读原文」对既有 /results 不成立。**本期 M5 只收新端点 /dataset|regression|history（统一 ADMIN/USER），并在 §7 M5 明写「既有 /result|results|stats 仍 isAuthenticated()、viewer 可读，不在本期范围，列为后续加固」**——YAGNI：不改既有接口行为。④黄——**autoPass 一词两义**：四格本体（:198）的「autoPass」指**重跑 pass**（evaluateNoCache 结果），而 persisted_pass_agree（:196/:211）的 persisted_pass_i 又是**落库 pass**（DTO autoPass=e.pass）——同一名字两个值，passRate(:202) 也没说清。**改名消除歧义**：落库 pass 字段 `autoPass→persistedPass`（DTO/SQL `e.pass AS persisted_pass`），四格明写「re_run_pass vs humanLabel」，passRate 明确「re_run_pass 占比」。
> - v5.8（第11轮审阅）：①🔴**删「复用既有硬编码规则」错误论据**——§3.2.2 曾引 `listResults`:190 作复用字据，但其源码 `(limit<=0||limit>200) ? 50 : limit` 语义是**回落 50 而非收敛 200**（传 500 得 50），与「越界收敛 ≤200」自相矛盾；改写明**有意偏离既有规则、新增实现**，§6 web 行注明实现者勿去 `listResults` 找参照。②🟡**§3.3 钉死执行顺序「先判空、后抢锁」**——`dataset()` 返回 0 样本→立即返回明确响应、**不抢锁**；仅样本非空才 `tryLock`（防 0 样本白占租户锁最长 30s 致并发后续全 409）。③🟡**§3.4 补 `/history` pageSize 上限**——对齐 `datasetLimitMax(200)`：越界 `>200` 收敛 200、≤0 回落 50（原只写≤0 回落 50、无上限，防 `pageSize=100000` 一次捞全表）。④🟢**行号漂移修正 + `autoScore→persistedScore` 统一**——`buildCreateIndexSql` 的 `.formatted` 实参范围 `:336-343`→`:336-342`；落库 score 字段 `autoScore→persistedScore`（SQL `e.score AS persisted_score`），与重跑均分 `avgScore`/DDL `avg_score` 区分（§3.3/§3.2.3 DDL `avg_score` 为**重跑均分**预留，二者语义不同，命名二分贯彻到底，并在 §3.2.2 DTO 注释处补一句显式区分）。
> - v5.10（第13轮审阅）：①🔴**`enabled` 门控自相矛盾——同键 §3.4 说不放、§3.6/§6 说要放**：§3.4 旧文（v5.3/v5.5 沿来）「enabled 装配开关仅在 EvalController:30 / AnswerEvaluationService 的 `@ConditionalOnProperty` 上，不放在 Properties 类」与 v5.9 新增的「EvalProperties 增加 `enabled` 字段 + `@PostConstruct` 在 `enabled=true` 时断言 rule-version」冲突——照 §3.4 落地则无 `enabled` 字段、`if (enabled && ...)` 无从判断、只能写回无条件断言 → 应用永远起不来。**§3.4 改为「`enabled` 两处并存」**：装配门控在 EvalController/Service/三 evaluator 类级 `@ConditionalOnProperty`（决定 Bean 是否装配、404 与否的**唯一**来源）；`EvalProperties` 同时持有 `enabled` 字段（绑定同键、自身不加 `@ConditionalOnProperty`）仅供 `@PostConstruct` 决定是否执行 rule-version 断言。②🔴**§6 基段三合一必崩组合**：`enabled: true` 写死 + `rule-version` 留空由字段 null 承 + `@PostConstruct(enabled=true 时断言非空)` → **应用首次启动即崩**；且 grep 全项目 `rule-version`/`ruleVersion` 零出现、无人知道要配。**改：基段 `rule-version` 给具体值 `v1.0`（不能留空）**，`@PostConstruct` 断言保底（enabled=true 且空白才抛）——正常部署永远不抛，仅 prod 误删键才抛（符合防漏配）；改规则须同步增大版本号、prod 不同版本用 prod 配置覆盖。③🟡**删除 continue 后 `:82-83` 归属未说清**：`log.info("...成功添加 feedback 列")`（:82）与 `migratedCount++`（:83）现处 columnExists==false 路径；若按「if/else 共享尾」落法则已迁移（跳过、仅 skippedCount++）schema 被误计 migratedCount 且打印「成功添加」。**补：:82-83 只能留 else 分支内，无共享尾**。④🟡**`pageSizeMax` 是不存在的名字**：§3.4:256 误写 `> pageSizeMax`（全文件其它处只有 `historyPageMax`）→ 统一改 `historyPageMax`。⑤🟢**上轮绿项行号又漂 1**：§3.1:65 写 `.formatted` 实参 `:336-342`，实测 `:336` 是 `""".formatted(` 行、实参在 `:337-342`；buildCreateTableSql 同理 `:302-309`→`:303-309`。改紧贴 `.formatted(` 行号。
> - v5.9（第12轮审阅）：①🔴**runner 修复措辞仍做反——卡在 continue 本身**：此前通篇只说「把 CREATE INDEX 移出 continue 分支」，但**没有任何一处要求删 `:65` 的 `continue;`**。只要 continue 还在，存量租户（列已存在）在 :62 判真 → :64 skippedCount++ → :65 continue 直接跳下一个 schema——**ALTER 跳过、CREATE INDEX 也跳过，bug 原样保留**；此时 §5「存量（列在）重跑后索引已建」断言**必然失败且无人报警**（catch 吞异常:85-88、启动照常）。**措辞改死：把 `if (columnExists) { ...continue; }` 改写为 `if/else`、删除 `:65` 的 `continue;`**（skippedCount++ 保留在 if 分支），ALTER 移 else 分支，CREATE INDEX 落 if/else 之外；§3.1/§6/§7 五处统一改写。②🟡**`rag.eval.enabled` 开关失效**：EvalProperties 无 `@ConditionalOnProperty` + `ruleVersion` 的 `@PostConstruct` 无条件缺键抛错 + §6 钉死 enabled:true 基段 → 运维想 `enabled:false` 停用评估会**启动直接失败**（EvalProperties 仍装配、缺 rule-version 抛错）。**改：`EvalProperties` 增加 `enabled` 字段（绑定 `rag.eval.enabled`），`@PostConstruct` 断言改 `if (enabled && ruleVersion isBlank) throw`**——enabled=false 停用时放行不抛；装配门控仍在 EvalController/Service 的 `@ConditionalOnProperty` 上；§5 补两条用例（enabled=true 缺 version 抛 / false 不抛）。③🟡**`/history` 复用 `datasetLimitMax` 当每页上限语义错**：`datasetLimitMax` 是「单次评测样本上限」，被借成分页大小 → 运维放开数据集(200→1000)会**连带把每页放大到 1000**。**改独立键 `rag.eval.history-page-max`（字段 `historyPageMax`，默认 200）**，/history 上限不再借 datasetLimitMax。④🟡**§4 与 §3.3 顺序打架 + §5 缺锁未申请断言 + §7 catch 行号漂移**：§4:306 从「tenant 键锁」入口读起来像先抢锁（与§3.3「先判空、后抢锁」矛盾）→ §4 regression 行改写为「先判空、后抢锁、0样本不抢锁」；§5 补「0 样本时锁未被申请」断言钉死顺序；per-schema catch 行号 §7 原写 :81-85 **实际 :85-88（差 4 行）** → 全文统一改为 :85-88。

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答动态筛出评测样本；回归对当批样本生成**指纹**并以 `rule_version` 锁定规则，支持可复现归因。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall/负类召回/F1 + 三维一致率 + persisted_pass_agree），**落快照**（含指纹+规则版本）支持跨版本趋势与正确归因。

**约束：**
- **最小可行（MVP）**：数据集为动态视图 + 每批回归落**含指纹+规则版本的聚合快照**；硬门禁留可选开关占位（默认关）。
- **反馈路径零改动**：`updateFeedback`、`rag_session.feedback` 不动；反馈联动通过读取侧 join。
- **回归重跑不污染线上缓存**（R3）：走 `evaluateNoCache`（无 Redis 写）。
- **最小化全局插件影响**：不加改租户插件 append；用 `ignoreTable` + 手写租户断言。
- **前置依赖（两级，见 §3.6）**：`rag.eval.enabled`（装配端点，基段必有缺失即 404）+ `rag.eval.online-enabled` 默认 `false`（company-rag-web 的 ChatController:56，只管在线抽样）且仅 `result.isRagUsed()` 行落评估——生产需开启 `online-enabled` 才有在线样本沉淀。
- **支持续演进边界**：本期回归测的是**固定历史 toolContext** 下的判定规则，不覆盖检索链路/知识库更新变化。

## 2. 现状回顾（真实机制）

多租户隔离的真实主次顺序：
- **主防线 = Schema 物理隔离**：`TenantSchemaInterceptor` 每次查询前 `SET search_path TO <schema>, public`（:96）。
- **辅助防线 = RLS（best-effort）**：`SET app.tenant_id`（:98）；两表 RLS 用 `tenant_id = current_tenant_id()`；`TenantSchemaInterceptor:45`、`TenantMyBatisPlusConfig:24-25` 承认 best-effort / 连接池跨连接风险。
- **结论**：SQL 必须**显式携带租户断言**，不依赖 RLS。

Schema 与租户映射（四轮核实）：
- schema 按 **tenantCode** 生成：`TenantServiceImpl.normalizeSchemaName(tenantCode) = "tenant_" + tenantCode.toLowerCase()`（:54-55），**非 tenantId**。
- **服务端反查现成机制**：`JwtAuthenticationFilter:80-88` 通过 `tenantService.getById(currentTenantId).getSchemaName()` 把 schema 写入 `TenantContext.setSchema()`；:52-58 已校验 X-Tenant-Id 必须属于 JWT 的 tenantIds（"不接受客户端任意传入"）。
- `TenantSchemaInterceptor` 已在消费 `TenantContext.getSchema()` 设 search_path。**dataset/regression 应取同一值，避免 SQL 与 search_path 错位。**

MyBatis XML 事实：用 `mybatis-plus-spring-boot3-starter`，`mapperLocations` 默认 `classpath*:/mapper/**/*.xml`；本方案数据集 join 用 **@Select** 避免 namespace/路径歧义。`map-underscore-to-camel-case: true`（application.yml:145）→ DTO 隐式映射。

评估链路：
- `AnswerEvaluationService.evaluate()`：三维评估 + 无条件写 Redis（:68）。
- `evaluateAndPersist()`：写 Redis + 强制落库（tenantId=null 拒绝，:152-155 铁律）。
- 三个 `AnswerEvaluator` 各返回**单布尔** `boolean evaluate(query, context, answer)`；维度布尔存于 `passes`（LinkedHashMap<String,Boolean>），分数存于 `dimensionScores`（Map<String,Double>，键全名 relevancy/correctness/faithfulness）。

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

通过读取侧 join 按 `answer_eval_result.session_row_id = rag_session.id` 关联 `rag_session.feedback`：
- `feedback = 1` → humanLabel = 1；`feedback = -1` → humanLabel = -1；`feedback = 0` 或无关联 → **不纳入**。
- **无 NULL 风险（五轮确认）**：`rag_session.feedback SMALLINT NOT NULL DEFAULT 0`，真实定义在 `SchemaMigrationConfig.migrateRagSessionFeedbackColumn` 的 `ALTER TABLE ... ADD COLUMN feedback SMALLINT NOT NULL DEFAULT 0`（SchemaMigrationConfig:70）。**注意勿引 init.sql:140**——那行在 `/* 业务表模板 */` 块注释（:82-181）内、且无 NOT NULL，仅作参考不执行。join 结果必非 NULL，humanLabel(Short) 隐式非空前提成立。
- **新建租户补齐（七轮加列 + 九轮钉死列/索引分属两方法 + 修正存量索引 bug）**：`feedback` 列与 `idx_<schema>_session_feedback` 索引**分属两个独立方法、各算各的占位符**，不能塞错：
  - **列 → `buildCreateTableSql` 的 `rag_session` 定义**（TenantServiceImpl:209-221 现无 feedback）：补 `feedback SMALLINT NOT NULL DEFAULT 0`。原因：`createTenantSchema`（新租户建表）**不经过** `migrateRagSessionFeedbackColumn` runner，列只由 runner 的 `ADD COLUMN` 补 → 新建租户在下次启动前 `updateFeedback`/`/dataset` 的 `s.feedback` 报 `column "feedback" does not exist`（v5.4⑤）。**列改的是建表文本，须落在 `buildCreateTableSql`。**
  - **索引 → `buildCreateIndexSql`（TenantServiceImpl:317-344）**：和既有 `idx_%s_session_tenant...`（:323）同栈，加 `CREATE INDEX IF NOT EXISTS idx_%s_session_feedback ON %s.rag_session(feedback)`。此语句含 **2 个 `%s`**，`buildCreateIndexSql` 现 `.formatted` 有 22 个 schemaName 实参（**实参 :337-342；:336 是 `""".formatted(` 行**）→ 补该行后须**同步加 2 个实参到 24**。**严禁写进 `buildCreateTableSql`**（现 23 实参 **:303-309**；:302 是 `.formatted(` 行）：多 2 个 %s、实参不够 → `MissingFormatArgumentException` → `createTenantSchema`（`@Transactional`、无 try/catch）**整个建租户流程失败**（九轮阻断项，详解见上）。
  - **不改 `buildCreateIndexSql` 的 `.formatted` 结构**：整体仍是一块多语句文本 + 尾部统一 `.formatted(...)`，仅在文本尾加索引行、实参列表尾加 2 个 schemaName。索引行用 `IF NOT EXISTS`，幂等；新建 schema 走 createTenantSchema、存量 schema 走 runner（见下）均安全。
  - **存量租户索引（九轮修正 runner bug + 十轮/十二轮钉死执行顺序与结构）**：`migrateRagSessionFeedbackColumn` 现状把 `CREATE INDEX` 放在 `if (columnExists) continue` 分支**内**（SchemaMigrationConfig:62-80）——存量租户列已存在 → continue → **索引永远建不上**。改（**十二轮明确删除 continue 的硬约束，防「移出 continue 分支」字面落法做反**）：**把 `if (columnExists != null && columnExists) { ...continue; }` 改写为 `if/else`，删除 `:65` 的 `continue;`**（`skippedCount++` 保留在 if 分支内），`ALTER ADD COLUMN` 移到 else 分支，**CREATE INDEX 位于循环体内、`if/else` 语句之后（等价于 else 块的最后一句）**，故未迁移租户 else 分支走「ALTER → CREATE INDEX」、已迁移租户 if 分支 (仅 skippedCount++) 也落入同一 CREATE INDEX——**两分支正常建索引**。**理由（十二轮增补）**：只要 `continue;` 还站在 :65，对「列已存在」的存量租户，:62 判断为真 → :64 skippedCount++ → :65 continue 直接跳下一个 schema——**ALTER 跳过、CREATE INDEX 也跳过，bug 原样保留**；而 "移出 continue 分支" 的字面落法（只挪 CREATE INDEX 语句、不删 continue）无法修好那批恰恰要补索引的存量 schema；此时 §5「存量（列已存在）重跑后索引已建」断言**必然失败且无人报警**（catch 吞异常、启动照常成功，见 :85-88）。亦不可把 CREATE INDEX 提到循环体最前（v5.6 措辞「无条件执行」最易的落法）：未迁移租户先 `CREATE INDEX` 报 `column "feedback" does not exist` → 被 per-schema `catch{ log.error; }`（:85-88）吞掉 → **列也没补、索引也没建、runner 继续下一个、应用照常启动**——恰是九轮声称要救的那批 schema 静默失败。故最终结构必须为：**已迁移租户（列在）走 if 分支（仅 skippedCount++、不再 continue）、随后 CREATE INDEX 仍执行**；**未迁移租户走 else 分支先 ALTER 补列、随后的 CREATE INDEX 正常建**。这是对既有 runner 的缺陷修复（**涉及删除 :65 的 `continue;` 与 if → if/else 改写**），纳入 §6 与 §5。§5 断言「存量（列已存在）+ 新建 schema 均含索引」，并另断言「未迁移租户重跑后列与索引齐备」。**新旧计数与日志的归属（十三轮补）：`log.info("...成功添加 feedback 列")`（现 :82）与 `migratedCount++`（现 :83）只能在 `else` 分支（即列缺失、确实 ALTER 成功的路径）内，不得作为两分支共享的循环尾**——否则已迁移（列在、走 if 且仅 `skippedCount++`）的 schema 也会误打「成功添加」日志并被计入 `migratedCount`，与 `skippedCount` 语义矛盾（一个 schema 同时既"跳过"又"成功迁移"）。结构应为：if 分支 = 仅 `skippedCount++`；else 分支 = `ALTER` + `CREATE INDEX` + `log.info` + `migratedCount++`；无共享尾。
  - **断言（九轮改为存量+新建均查）**：新建 schema（createTenantSchema）经 `buildCreateTableSql`/`buildCreateIndexSql` 落列+索引；存量 schema 经 runner（列与索引分别幂等）补齐。**§5 schema 测试断言「存量 schema rag_session 含 feedback 列 + 同名索引」与「新建 schema 同」**。
  - **`EvalRegressionReportDdl` 职责收敛（九轮）**：只装 `eval_regression_report` 表 DDL（§3.2.3），**不承载 rag_session 的列/索引补丁**（那两处归 buildCreateTableSql / buildCreateIndexSql 管，见上）；删除 v5.5 的「EvalRegressionReportDdl（或 buildCreateTableSql）」二选一表述。

### 3.2 数据集抽取（@Select + TenantContext schema + 租户断言）

#### 3.2.1 租户隔离方案

- **schema 取 `TenantContext.getSchema()`（四轮阻断项1 修正）**：由服务端在 JWT 过滤器经 `tenantId→tenant→getSchemaName()` 反查写入（JwtAuthenticationFilter:80-88），**不接受客户端传入、客户端不可控**；null/blank → **400**；再过白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`。与运行时 `search_path` 同一值，杜绝"SQL 指向 A / 拦截器设 B"错位。
- **tenantId 取 `TenantContext.getTenantId()`（六轮钉死）**：三个新接口方法签名的 `Long tenantId` **一律来自 `TenantContext.getTenantId()`**（null → 400），与 schema 同源（JWT 过滤器由同一 token claims 同时写 TenantContext.getTenantId()/getSchema()，见 JwtAuthenticationFilter:81,86）。**不走 `@RequestHeader X-Tenant-Id`**（既有四个接口 EvalController:40/57/72/84 用 header 判空，但 header 与服务端推导可能分叉，新接口统一收敛到 context 单一来源，防 schema/tenant 指向不一致）。测试断言 tenantId null→400。
- **ignoreTable 豁免**：`TenantMyBatisPlusConfig.ignoreTable` 追加 `answer_eval_result`、`rag_session`（同租户 schema，零全局影响，已核实两表既有查询均带显式租户断言）。
- **@Select 手写租户断言**：`e.tenant_id = #{tenantId}` **且 `s.tenant_id = #{tenantId}`**（四轮细节：rag_session 进入 ignoreTable 后无插件过滤器，显式断言让不变量可读，防未来删 schema 前缀）。

#### 3.2.2 数据集抽取方法

`dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
- `schema = TenantContext.getSchema()`（null/blank→400，白名单）。
- **from/to 均非 null 校验**：缺任一 → 400。
- **limit 缺省 `50`，越界收敛 `≤ 200`；显式传 `<=0` 同样回落 `50`**——**这是新接口/新实现，勿照 `listResults`:190 的既有口径照抄**（既有 `listResults` 是 `limit<=0||limit>200 ? 50 : limit`，**传 500 会回落 50 而非收敛到 200——收敛到 200 是与它有意偏离，不是复用**）。**/dataset 的收敛逻辑为新增实现（§6 标注「新增，非 listResults 复用」）**：`0<limit<=200` 透传；`limit<=0` 回落 `EvalProperties.datasetLimitDefault(50)`；`limit>200` 收敛到 `datasetLimitMax(200)`。与 `/results` 行为（传 500→50）**不一致是已知、有意**，二者接口语义本就不同（/results 是历史分页泛查询，/dataset 是评测样本集有上限口径）。取值范围 `EvalProperties.datasetLimitDefault/max`（§3.4），默认 50/200。
- **返回体/内存截断口径**：200 条样本各携带检索上下文全文（每条可达数千 token）。默认逐条返回即可；若单租户样本量接近上限且返回体过大，页面可改走分页（本期仅 history 分页）。实现时以 `EvalProperties.datasetLimitMax` 为硬上限，不额外截断字段，避免破坏评测完整性。
- 输出 `List<LabelledEvalSample>`。

**@Select（`AnswerEvalResultMapper`）——外层时间倒序截断，避免 LIMIT 冻结（五轮阻断项）：**
```java
@Select(
  "SELECT * FROM ( " +
  "  SELECT DISTINCT ON (e.session_row_id) " +
  "   e.query, e.context, e.answer, e.pass AS persisted_pass, e.score AS persisted_score, " +
  "   s.feedback AS human_label, e.tenant_id AS tenant_id, " +
  "   e.session_row_id, e.id AS eval_id, e.create_time " +
  "  FROM ${schema}.answer_eval_result e " +
  "  JOIN ${schema}.rag_session s ON s.id = e.session_row_id " +
  "  WHERE e.tenant_id = #{tenantId} " +
  "    AND s.tenant_id = #{tenantId} " +
  "    AND e.session_row_id IS NOT NULL " +
  "    AND s.feedback <> 0 " +
  "    AND e.create_time BETWEEN #{from} AND #{to} " +
  "  ORDER BY e.session_row_id, e.id DESC" +
  ") t " +
  "ORDER BY t.create_time DESC " +
  "LIMIT #{limit}")
List<LabelledEvalSample> selectDataset(@Param("schema") String schema,
    @Param("tenantId") Long tenantId, @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to, @Param("limit") int limit);
```
- **修复原因（PG 规则）**：`DISTINCT ON` 的表达式必须匹配最左 `ORDER BY` 表达式，故内层输出必按 `session_row_id` **升序**——若直接 `LIMIT n` 会取到 `session_row_id`（即 `rag_session.id`，BIGSERIAL 单调递增）最小的老样本；因反馈永远新增，数据集首跑后即**冻结在最老样本**，与 §1「动态筛出」及既有 `idx_answer_eval_tenant_time (tenant_id, create_time DESC)`（TenantServiceImpl:326，为时间范围查询所建）意图相悖，且属"接口 200 / 指标正常 / 样本静默选错"的最难发现缺陷。
- **修复**：内层 `DISTINCT ON` 保持正确，外层 `ORDER BY t.create_time DESC` 后 `LIMIT`——先按 session 去重取每个会话最新评估，再按时间倒序截断为最新的 n 个会话。
- `${schema}` 仅存 `TenantContext.getSchema()` + 白名单；其余 `#{}` 绑定。
- DTO 列→属性依赖 `map-underscore-to-camel-case`（隐式映射，不写 @Results）。**注意映射字段与 SELECT 列一一对应（含 `evalId`），不能静默丢弃列**。

**`LabelledEvalSample`（含 tenantId + evalId）：**
```
query, context, answer, tenantId(Long),
persistedPass(Boolean), persistedScore(double), humanLabel(Short: 1/-1),
sessionRowId(Long), evalId(Long), createTime(LocalDateTime)
```
> `evalId` 与 SELECT 的 `e.id AS eval_id` 对应（否则该列被静默丢弃）；`createTime` 来自 `e.create_time`（外层排序用）。
> **命名二分（十一轮贯彻）**：`persistedPass`/`persistedScore` 专指**落库** `e.pass`/`e.score`；**重跑均分**一律在报告/快照侧用 `avgScore`/DDL `avg_score`（§3.3, §3.2.3）——DTO 的 `persistedScore` 与报告 `avgScore` 语义不同，勿混淆。

#### 3.2.3 回归报告快照表

新增每租户表 `eval_regression_report`（幂等 DDL，含 `DROP POLICY IF EXISTS`）：

```sql
CREATE TABLE IF NOT EXISTS <schema>.eval_regression_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    rule_version VARCHAR(64) NOT NULL,          -- rag.eval.rule-version 配置项（四轮）
    dataset_fingerprint VARCHAR(64) NOT NULL,   -- md5(排序后 session_row_id 拼接)（Java 侧计算）
    dataset_from TIMESTAMP,
    dataset_to TIMESTAMP,
    sample_count INT NOT NULL,
    pass_rate DOUBLE PRECISION NOT NULL,
    avg_score DOUBLE PRECISION NOT NULL,
    avg_relevancy DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_correctness DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_faithfulness DOUBLE PRECISION NOT NULL DEFAULT 0,
    persisted_pass_agree DOUBLE PRECISION NOT NULL DEFAULT 0,  -- 重跑 pass vs 落库 pass 一致率（四轮新增）
    tp INT NOT NULL DEFAULT 0,
    tn INT NOT NULL DEFAULT 0,
    fp INT NOT NULL DEFAULT 0,
    fn INT NOT NULL DEFAULT 0,
    accuracy DOUBLE PRECISION NOT NULL DEFAULT 0,
    precision DOUBLE PRECISION NOT NULL DEFAULT 0,
    recall DOUBLE PRECISION NOT NULL DEFAULT 0,
    f1 DOUBLE PRECISION NOT NULL DEFAULT 0,
    negative_recall DOUBLE PRECISION NOT NULL DEFAULT 0,
    relevancy_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
    correctness_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
    faithfulness_agree DOUBLE PRECISION NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_time
    ON <schema>.eval_regression_report (tenant_id, run_time DESC);
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_fp
    ON <schema>.eval_regression_report (tenant_id, dataset_fingerprint);
ALTER TABLE <schema>.eval_regression_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.eval_regression_report FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_eval_rep ON <schema>.eval_regression_report;
CREATE POLICY tenant_isolation_eval_rep ON <schema>.eval_regression_report
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT SELECT, INSERT ON <schema>.eval_regression_report TO company_rag_app;
GRANT USAGE, SELECT ON SEQUENCE <schema>.eval_regression_report_id_seq TO company_rag_app;
```
> `eval_regression_report_id_seq` 为 BIGSERIAL 默认 sequence 名（四轮细节，DDL 注明）。**授权口径（七轮纠偏）**：下方 `GRANT SELECT, INSERT` + sequence grant 只是**冗余加固**——真正让两路径都有 DML（含 DELETE）的是 `createTenantSchema` 里的 blanket `GRANT ... ON ALL TABLES IN SCHEMA`（TenantServiceImpl:149）与 `ALTER DEFAULT PRIVILEGES`（:151，runner 建表路径亦被覆盖）。存量/新建租户对该表均已具 DELETE 权限；未来保留策略无须补 DELETE，见 §7。

**快照 INSERT 显式租户**：insert 前显式 `setTenantId(tenantId)` + null 拒绝（对齐铁律）。**WITH CHECK 依赖说明（五轮补充）**：表 RLS 的 `WITH CHECK (tenant_id = current_tenant_id())` 与在线落库同前提——依赖会话级 `app.tenant_id`（RLS best-effort，见 §2）；因此显式 `setTenantId` 必须在 RUN 前设置且与 schema 同源，SQL 亦带显式 tenant 断言作主防线，RLS 仅兜底。

**迁移语义（五轮收敛为一条路线）**——建表 DDL **单一定义、两处引用**，init.sql 仅注释存档：

1. **DDL 单一定义 + 模块归属（七轮钉死 + 十四轮统一方法名）**：新增 `eval_regression_report` 的建表+索引+RLS+授权整段 DDL，定义为 `EvalRegressionReportDdl.build(schemaName)` 静态方法（**唯一来源，类名 `EvalRegressionReportDdl`、方法名 `build`，全文统一此名，不再用旧称 `buildEvalRegressionReportSql`**），供下述两条执行路径引用，**杜绝双份手写**。理由：既有 `answer_eval_result` 就在 `buildCreateTableSql`（TenantServiceImpl:236）与 `migrateAnswerEvalResultTable` runner（SchemaMigrationConfig:192）各写一份，且授权口径已漂移（runner 显式写 `GRANT SELECT,INSERT,UPDATE,DELETE` :216，buildCreateTableSql 未显式给表级 DML、仅依赖 blanket GRANT+ALTER DEFAULT PRIVILEGES 隐含覆盖）——本方案对新增表**不再复制该坏先例**，改单点定义。
   - **方法放 `company-rag-common`（七轮纠偏）**：作为 `public static String build(schemaName)` 放在**唯一**工具类 `com.company.rag.common.constant.EvalRegressionReportDdl`。原因：`buildCreateTableSql` 所在的 `TenantServiceImpl` 属 `company-rag-tenant`（pom 仅直接依赖 common）；而 runner 所在 `SchemaMigrationConfig` 属 `company-rag-bootstrap`。bootstrap 虽经 `bootstrap→web→tenant`（web/pom:17 直接依赖 tenant，且 `CompanyRagApplication:143` 已跨该边用 TenantService）**技术上也够到 tenant**，但依赖传递路径脆弱，且 tenant 不直接依赖 bootstrap、反向显式声明不可能。**放 common（tenant 与 rag 的直接依赖、经 web 对 bootstrap 可达）确保两条路径都拿到唯一 DDL 源**，不依赖传递依赖的可达性。
2. **两条执行路径引用同一 DDL**：
   - `TenantServiceImpl.createTenantSchema`（新租户）：在 `buildCreateTableSql` 段内调用 `EvalRegressionReportDdl.build(schemaName)` 建表。
   - `SchemaMigrationConfig`：新增 `migrateEvalRegressionReportTable` runner，同 `migrateAnswerEvalResultTable`（:175-186）范式遍历 `information_schema` 中 `tenant_%` schema 逐个幂等建表（**存量租户借此补表**，否则老租户 /regression → relation 不存在），内部调 `EvalRegressionReportDdl.build(schemaName)`。此时 `current_tenant_id()` 必已由 init.sql（:208）定义，无顺序问题。
   - **公共 helper（六轮）**：全项目同款"遍历 `tenant_%` schema 幂等建表"的 runner 已有 **5 份**（`migrateRagSessionFeedbackColumn` / `migrateRagSessionUserIdNotNull` / `migrateAnswerEvalResultTable` / `migrateToolApprovalTable` / `migrateDocumentPipelineStateTable`，SchemaMigrationConfig:32/108/175/235/298）。本方案新增的第 6 份建议抽**公共 helper**（如 `forAllTenantSchemas(Consumer<String>)` 统一 schema 查询+白名单+错误吞没），新 runner 只定义 DDL 片段。**不重构既有 5 份**（避免扩大改动），helper 仅供新表使用。
   - **一致性（五轮修正 + 十四轮改单点名）——改为"DDL 片段相同"断言，防空断言误导**）：既然已收敛为单一方法 `EvalRegressionReportDdl.build(schemaName)`，两条路径渲染出的 DDL 片段**必然逐字符相同**——无须再用 `information_schema.columns` 比对（那样只会得到并集、检测不出漂移，属空断言）。仿照 answer_eval_result 若未来某天在 buildCreateTableSql 与 runner 又各自展开同一表，则此条**哨兵断言**（比对两处 DDL 片段需一致）可提前报警；当前单一方法下自动成立、仅作为未来分叉的回归护栏。
3. `sql/init.sql`：**不以真实 SQL 存档**——该文件挂在 `/docker-entrypoint-initdb.d/init.sql`，首次启动被 psql 执行；若把含 `<schema>` 占位符的 DDL 写进去会在 psql 阶段把无租户表建在 public、且每租户同名 `eval_regression_report_id_seq` 与 :146 的 sequence 授权冲突。故**仅以注释形式**存入 DDL 供参考，不参与执行。

> **说明**：v5.1 曾写"建表一律由 runner 执行"，与「createTenantSchema 也建表」确有表述歧义——两者**都**是执行入口、但共用**同一 DDL 定义**；所谓"唯一"指的是"唯一 DDL 源"，非"唯一执行点"。

### 3.3 回归重跑报告

`regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
- `limit` **原样透传给 dataset**（保证"同一批样本"口径，否则 limit 差异即不同批）；**缺省值同 /dataset = `EvalProperties.datasetLimitDefault(50)`**（五轮），由 Controller 在缺省时填默认值后透传，决定数据集规模与指纹口径。
- **执行顺序（十一轮钉死）——先判空样本、后抢锁**：① `dataset(...)` 取当批样本，**0 样本 → 不落快照、不抢锁，直接返回明确提示**；② 样本非空才进 `tryLock(regressionLockTimeoutMs)` 抢锁 → 抢到才重跑+落快照，抢不到 → 409。**理由：把"0 样本判定"放抢锁之前，0 样本请求不会白占该租户锁最长 30s（否则并发下后续请求全被 409 挤掉）；两条不变量分别断言**（①判空在抢锁前、②非空时锁保证同一租户同批重跑串行）。
1. `dataset(...)` 取当批样本；**0 样本 → 不落快照、不抢锁，返回明确提示**（顺序见上）；
2. **Java 侧计算指纹**（四轮阻断项3）：对返回列表 `sessionRowId` 排序后 `md5(join(",", ids))`——与返回集天然一致，免 0 行 `string_agg` NULL；取 `rule_version = rag.eval.rule-version`；
3. 逐个 `evaluateNoCache()` 重跑（R3，无 Redis 写）；
4. 与 humanLabel + 落库 pass 比对生成报告；
5. **落快照**（显式租户 + 指纹 + rule_version + from/to + persisted_pass_agree），返回含 `reportId`。

**persisted_pass 来源（八轮钉死 + 十轮改名消歧义，不另回查落库）**：重跑报告里 `persisted_pass_i`（persisted_pass_agree 分子）**直接取 `dataset()` 返回的每条 `LabelledEvalSample.persistedPass`（=读库的 `e.pass`）**，与**重跑 pass**（`evaluateNoCache`/`doEvaluate` 结果，下称 `re_run_pass`）逐条比对——同一批样本里既有渲染值又含落库 pass，**无须（也不存在）按 eval_id 回查 `answer_eval_result`**（§6 Mapper 清单无该方法，另加回查只增复杂度与第二次读库）。`selectDataset` 输出需带 `persisted_pass` 列（§6 标注），供报告计算使用。**命名约定（十轮）**：DTO/SQL 用 `persistedPass`/`persisted_pass` 专指落库 pass；`re_run_pass` 专指本次重跑 pass；勿再共用 `autoPass` 一名。

**四格**（**re_run_pass vs humanLabel**，human=1 正类）：TP/TN/FP/FN。

**指标公式（三维一致率 + persisted_pass_agree）：**
```
n=sampleCount; accuracy=(tp+tn)/n; passRate=re_run_pass占比; avgScore=均分
precision=tp/(tp+fp)  分母0→1.0
recall=tp/(tp+fn)     分母0→1.0
f1=2pr/(p+r)          p=r==0→0.0       -- 灾难场景不可显示为满分
negative_recall=tn/(tn+fp)  分母0→1.0
relevancy_agree   = |{i: relevancy_i == (humanLabel_i>0)}| / n
correctness_agree = |{i: correctness_i == (humanLabel_i>0)}| / n
faithfulness_agree= |{i: faithfulness_i == (humanLabel_i>0)}| / n
   映射：humanLabel +1→正 / -1→负；维度布尔 vs 正负同向；分母固定 n。
persisted_pass_agree = |{i: re_run_pass_i == persisted_pass_i}| / n（四轮新增）
   -- 重跑 pass vs 落库 pass 一致率；不比 rule_version 更早暴露"规则被悄悄改"，无需额外元数据。
```

**`doEvaluate` 与 `EvalDecision`（四轮阻断项2）**：
- 新增私有 record:
  ```java
  record EvalDecision(boolean relevancy, boolean correctness, boolean faithfulness,
                      boolean pass, double score) {}
  ```
- 私有 `doEvaluate(query, context, answer)` → `EvalDecision`：由各 `AnswerEvaluator` 的布尔 + `passes` 映射得来。
- `evaluate()`/`evaluateAndPersist()`/`evaluateNoCache()` 三处统一走 `doEvaluate`；落库侧继续用 `dimensionScores`（Map<String,Double>，键**全名** relevancy/correctness/faithfulness）传分数。三维一致率用 `EvalDecision` 布尔维度。

**`EvalDecision → AnswerEvalResult` 映射规则（五轮补充，实现不设分叉）**：
- 抽 `doEvaluate(query, context, answer)` 为唯一评估入口，返回 `EvalDecision`；三个 `AnswerEvaluator` 各评估一次存入 record 三段布尔。
- `dimensionScores` 出参：`Map<String,Double>`，**键全名** `relevancy` / `correctness` / `faithfulness`，值 `true→1.0 / false→0.0`（对齐既有 evaluate():60-61、toEntity():64-66 的 `getOrDefault("relevancy"/"correctness"/"faithfulness", 0.0)` 全名读取）。
- `score`：三维均值 = `(relevancy?1:0 + correctness?1:0 + faithfulness?1:0) / 3`（对齐既有 :64 `scores.values()...average()`）。
- `pass`：`relevancy && correctness && faithfulness`（与既有 `AnswerEvalResult.allPass(passes)` 等价）。
- 上述四条作为 `doEvaluate` 与 `evaluate()/evaluateAndPersist()/evaluateNoCache()` 之间约定的**唯一还原规则**，避免实现时 score/dimensionScores 名称分叉。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 | 并发/校验 |
|---|---|---|---|
| POST | `/api/eval/dataset` | 抽样数据集 `List<LabelledEvalSample>`（from/to/limit） | from/to 非空→缺 400；limit 缺省 50、显式 ≤0 回落 50、越界≤200 |
| POST | `/api/eval/regression` | 跑回归 + 落快照（reportId/指纹/rule_version） | Service 按 tenantId 键锁 + tryLock(30s) 失败→409；0 样本 → 明确响应（见下）；limit 缺省 50 透传、≤0 回落 50、**越界≤200（同 /dataset，九轮钉死）** |
| GET | `/api/eval/history` | 历史快照**分页**(page/pageSize)，按指纹归因 | 只读；page **1**、pageSize **50** 缺省（≤0 回落）；pageSize 上限 `historyPageMax`（默认 200，十二轮独立键）；**手写 LIMIT/OFFSET + 独立 count（见下）**；**复用 dataset 的 schema 校验（见下）** |

**`/history` 分页响应结构（六轮钉死）**：`data` 为一个分页对象（对齐 MyBatis-Plus `IPage` 约定，字段名映射一致）：
```
{
  "code":200, "msg":"success",
  "data": {
    "records": [ { ...EvalRegressionReport 字段 }, ... ],
    "total": 123,          // 总条数
    "size": 50,            // 每页条数（= pageSize 缺省 50）
    "current": 1           // 当前页（= page 缺省 1）
  }
}
```
`page`/`pageSize` 缺省 `1`/`50`；记录内按 `run_time DESC, id DESC` 二级排序。**复用 dataset 的 schema 校验（见下）**。**pageSize 上限（十一轮 + 十二轮改独立键）**：越界 ≤0 回落 50、`> historyPageMax` 收敛（默认 200），**上限不再复用 `datasetLimitMax`**——`datasetLimitMax` 语义是「单次评测样本上限」（200→1000 调整时**不应连带把 `/history` 每页放大到 1000 条**，两者无关）；**单独用 `EvalProperties.historyPageMax`（绑定 `rag.eval.history-page-max`，默认 200）**，便于分别调优。
> **分页 `total` 实现路径（七轮钉死——走手写 LIMIT/OFFSET + 独立 count，不依赖 MP 分页）**：`TenantMyBatisPlusConfig` 仅注册 `TenantSchemaInterceptor`(:26) 与 `TenantLineInnerInterceptor`(:29)，**无 `PaginationInnerInterceptor`**；自定义 `@Select` 也不走 MP 分页（MP 分页需返回 `IPage<T>` 且方法参数带 `Page` 类型才注入 LINIT/OFFSET 并生成 count）。若上 `total`/`LIMIT` 全靠插件，`total` 恒 0、`LIMIT` 不注入会退化成**返回全量**。故：**不新增全局 `PaginationInnerInterceptor`**（避免改共享插件、影响所有既有查询，且与 §3.5「不改插件」冲突），`/history` 在 `EvalRegressionReportMapper` 内手写两条 `@Select`：① 查记录 `... LIMIT #{limit} OFFSET #{offset}`（pageSize/page-1 换算）；② 独立 count `SELECT COUNT(*) ...`（同 WHERE，无 LIMIT）。两语句经 `TenantLineInnerInterceptor` 各追加 tenant_id（表不在 ignoreTable）——schema 前置校验 + RLS 兜底同前。`total` = count 结果；**pageSize 越界 ≤0 回落 50、`>historyPageMax(200)` 收敛 200（十二轮改独立键 `historyPageMax`，不再借 `datasetLimitMax`）**；page ≤0 回落 1（对齐缺省）。

**统一 schema/tenant 校验（三个接口共用，六轮）**：`/dataset`、`/regression`、`/history` **共用同一套**解析校验逻辑（封装为公共方法，如 `resolveTenant()/resolveSchema()`）——`TenantContext.getTenantId()` null → **400**；`TenantContext.getSchema()` null/blank → **400**，再过白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`（越权 schema 名 → 400）。理由：`/history` 走 `EvalRegressionReportMapper` 自定义 `@Select`，`eval_regression_report` 表**不在 ignoreTable**，插件会按 `TenantContext` 追加 tenant_id，schema 为空时行为未定义——必须与 dataset 同样前置校验，不能只依赖插件兜底。

**0 样本响应语义（六轮，钉死）**：统一响应体 `R<T>` 仅 `code` / `msg` / `data` 三字段（`R.ok()` 里 `code=200`、`msg="success"`）。`/dataset` 0 样本 → `R.ok(emptyList)`（200 + 空列表，正常语义）。`/regression` 0 样本 → `R<X> r = R.ok(null); r.setMsg("无匹配样本，未落快照")`，响应 `{"code":200,"msg":"无匹配样本，未落快照","data":null}`，**HTTP 200**（非错误）。**`code=200` 是客户端区分"空结果"与"未落快照"的唯一依据**（不能写成 `code=0`/`message=`，R 无这些字段，会误导调用方把正常空跑当失败）。测试断言这一响应结构。

**`/regression` 缺省 limit（五轮）**：与 `/dataset` 相同，缺省 `EvalProperties.datasetLimitDefault(50)`，由 Controller 在缺省时填默认值后**原样透传**给 dataset；`dataset-limit-max` 越界收敛同样适用——直接决定数据集规模与指纹口径，必须收口。

口径与边界：
- 统一 `@PreAuthorize("hasAnyRole('ADMIN','USER')")`（**新端点** dataset/regression/history，防 viewer 读 context/answer 原文）；既有 /result|results|stats 是否收口见 §7 M5。
- `/regression` **POST**；**Service 层按 tenantId 键锁**（`ConcurrentHashMap<tenantId, ReentrantLock>`，不跨租户互阻）。**超时语义（钉死）**：**tryLock(30s) 失败 → 409，不做任何重跑**（附录基线：全流程 600 次毫秒级几乎不超时，30s 仅在抢锁时触发，勿与全流程超时混淆）。
- **多副本假设（五轮决策）**：**本期假设单实例部署**，进程内 `ConcurrentHashMap` 锁已足够；若后续多副本，进程内锁会各跑一遍、各落一条快照——届时升级为 **Redisson `RLock` 分布式锁**（`redissonClient` 已注入，项目当前无分布式锁使用），并发测试按分布式锁语义重写。
- **统一 schema/tenant 校验**：`/dataset`、`/regression`、`/history` 三接口共用 `resolveTenant()/resolveSchema()`（`TenantContext.getTenantId()` null→400 + `TenantContext.getSchema()` 服务端反查 + null/blank→400 + 白名单），客户端不可控，见本节第一段。

**配置绑定（六轮补充，消除静默读不到 + 七轮补注册注解 + 八轮改缺省写法）**：`rag.eval.*` 当前仅 `online-enabled` / `async-enabled` 两个键，用 `@Value` 读（company-rag-web 的 ChatController:56,59），全项目无 `rag.eval` 的 `@ConfigurationProperties` 类。本方案引入 **`EvalProperties`（`@Component` + `@ConfigurationProperties(prefix = "rag.eval")`，对齐既有 `ApprovalProperties`）**——**`@Component` 必须有**（七轮阻断项）：全项目**无** `@ConfigurationPropertiesScan` / `@EnableConfigurationProperties`，属性类仅靠 `@Component`/`@Configuration` + `@ComponentScan("com.company.rag")`（CompanyRagApplication:32）注册；只写前缀注解 → 不绑定 → `rule_version` 为 **null → 撞 `NOT NULL` → 落快照 500**。统一承载以下键，避免 `@Value` 逐个散读：
- `rag.eval.rule-version`（回归快照 rule_version 源；改规则须同步更新，测试断言写入==当前值）
- `rag.eval.regression-lock-timeout-ms`（默认 `30000`）
- `rag.eval.dataset-limit-default`（默认 `50`）
- `rag.eval.dataset-limit-max`（默认 `200`）
- `rag.eval.regression-gate-enabled`（占位，默认 `false`）
- 复用既有 `online-enabled` / `async-enabled`；**`enabled` 两处并存（十三轮对齐）**：①**装配门控**在 `EvalController:30 / AnswerEvaluationService:28 / 三 evaluator` 的类级 `@ConditionalOnProperty(name="rag.eval.enabled", havingValue="true")`——这是决定 Bean 是否装配、`/api/eval/**` 是否 404 的**唯一**来源；②**`EvalProperties` 同时持有 `enabled` 字段**（绑定同键 `rag.eval.enabled`，自身**不加** `@ConditionalOnProperty`，见下），仅供 `@PostConstruct` 决定是否执行 rule-version 断言（v5.9）——**不参与 Bean 装配**。两者是**不同职责的并存，非冲突**；§3.6/§6 已按此表述，v5.3/v5.5 沿来的「enabled 不放在 Properties 类」为**旧文，作废**。`regression-concurrency-keys` 本期单一取值 `tenant`，可并入占位，不作为独立 `@Value`。
- **`EvalProperties` 不加 `@ConditionalOnProperty`（六轮澄清）**：对齐既有 `ApprovalProperties`（`@Component + @ConfigurationProperties`）先例——`@Component` 注册即可，无需 `@ConditionalOnProperty`，Properties 只是纯配置绑定的普通 Bean，无副作用、可安全无条件装配。`rag.eval.enabled` 装配门控继续由 `EvalController` / `AnswerEvaluationService` 的既有 `@ConditionalOnProperty` 承担，不重复加在 Properties 上（避免与先例不一致）。
- **缺省写法（八轮阻断项，改字段初始值、禁用 `@DefaultValue`）**：Spring Boot `@DefaultValue` 的 `@Target({PARAMETER, RECORD_COMPONENT})` **不含 FIELD**——字段上写 `@DefaultValue` javac 编译不过；退到 setter 参数写会被静默忽略、配置缺失时字段全为 0（→ **`regressionLockTimeoutMs=0` 使 `tryLock(0)` 立刻 false → /regression 恒 409；`datasetLimitMax=0` 使上限口径崩**）。**正确写法：字段初始值 `private boolean enabled = false; private long regressionLockTimeoutMs = 30000; private int datasetLimitDefault = 50; private int datasetLimitMax = 200; private int historyPageMax = 200; private boolean regressionGateEnabled = false;`**（**字段名须与键 relaxed 对齐**：键 `regression-lock-timeout-ms` → 字段 **`regressionLockTimeoutMs`**，九轮——写成 `lockTimeoutMs` 会与键**不匹配、显式配置静默失效落回初始值**；`history-page-max` → **`historyPageMax`**，十二轮——`/history` 每页上限独立于 `datasetLimitMax`，见 §3.4），对齐 `ApprovalProperties:20-26`（`= 300/500/...` 字段初始值）。**`ruleVersion` 则相反、绝不能给初始值**——保持 `null`，由 `@PostConstruct` 在配置缺失（漏配/误删键/prod 无该键）时抛异常，防静默 500（见 §5 测试）。**`@PostConstruct` 断言仅在 `enabled=true` 时执行（十二轮）**：`EvalProperties` 增加 `enabled` 字段（绑定 `rag.eval.enabled`），断言写 `if (enabled && ruleVersion isBlank) throw`——否则 `enabled: false` 想停用评估也会因 EvalProperties 无条件装配 + 缺 ruleVersion 启动失败（见 §3.6）。
- **新键注册（七轮补 + 八轮纠偏）**：新增键统一挂 `application.yml` 基段的 `rag.eval`（**不只 application-dev.yml**，见 §3.4 配置）；数值/布尔缺省用**字段初始值**（非 `@DefaultValue`，见上）；并在 `EvalProperties` 的 `@PostConstruct` 断言 `ruleVersion` 非 null/blank（防 prod 误删键导致静默 500）。配置失效场景在 §5 单测防回归。

### 3.5 与既有机制边界

- 不触碰：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- 新增 `evaluateNoCache`/`doEvaluate`/`dataset`/`regression`；`evaluate()`/`evaluateAll()` 契约不变。
- 租户插件仅追加 ignoreTable 豁免两表；不改 append，**不新增 PaginationInnerInterceptor**（/history 走手写 LIMIT/OFFSET + count，见 §3.4）。
- 新增表三处同步（见 §3.2.3 迁移语义）。

### 3.6 前置条件（八轮纠偏——正确的键是 enabled 而非 online-enabled）

| 项 | 现状 | 影响 |
|---|---|---|
| `rag.eval.enabled`（**装配总开关，八轮定 + 十四轮对齐基段现状**） | 类级 `@ConditionalOnProperty(name="rag.eval.enabled", havingValue="true")`（无 `matchIfMissing`）作用于 **EvalController:30 / AnswerEvaluationService:28 / 三个 evaluator**（AnswerRelevancyEvaluator:17 / AnswerCorrectnessEvaluator:11 / AnswerFaithfulnessEvaluator:14）。**目标状态（v5.10 §6 落地后）**：`application.yml` 基段 `rag.eval` 含 `enabled: true` + `rule-version: v1.0`（十四轮）；**编码现状**：`application.yml` 的 `rag:` 段在 :121-138（目前仅 agent/memory，无 eval 子节点），`application-dev.yml` :79-82 有 `rag.eval.{enabled:true, online-enabled:true, async-enabled:true}`（无 rule-version），`application-prod.yml` :66 / `application-test.yml` :47 的 `rag:` 段均无 eval 子节点 | 基段若缺 `enabled`，忽略未书写的 profile（prod/test）下这些类**根本不装配** → `/api/eval/**` **全 404**（端点不存在，不是 500）。**§6 已把 `enabled: true` + `rule-version: v1.0` 写进 application.yml 基段**；按 Spring **property 级合并**（非 map 覆盖），`rule-version` 等 dev/prod/test 未书写的键会从基段继承，**三个 profile 均能拿到 `enabled: true` 与 `rule-version: v1.0`，启动即不崩**；基段**给死值、prod 想用不同版本再在 prod 覆盖**（见 §6） |
| `rag.eval.online-enabled` | 默认 `false`（company-rag-web 的 ChatController:56）；dev 显式 `true`（application-dev.yml:81） | 只决定 ChatController 是否触发**在线抽样**，**与端点装配无关**；生产需开启才有在线样本沉淀 |

**结论（两级前置，勿混）**：①**端点存在**靠 `rag.eval.enabled: true`（基段），缺失 → 404——这是伪造现有手段都查不到的；②**在线样本沉淀**靠 `rag.eval.online-enabled: true`（prod），否则反馈攒再多数据集恒空、首跑空报告。部署时**两级都核对**（§6）。

**`enabled` 开关失效隐患（十二轮）**：EvalProperties 是不带 `@ConditionalOnProperty` 的普通 `@Component`，若其 `ruleVersion` 的 `@PostConstruct` **无条件**断言非空，则运维想用 `enabled: false` 停用评估能力时——EvalProperties 仍装配、ruleVersion 缺键 → `@PostConstruct` 抛错 → **应用启动直接失败**，与「enabled=false 应优雅停用」意图矛盾。**修正：`EvalProperties` 增加 `enabled` 字段（绑定 `rag.eval.enabled`，`@ConditionalOnProperty` 装配门控仍在 EvalController/Service 上，此字段仅作断言开关）；`@PostConstruct` 的 `ruleVersion` 非空断言改为 `if (enabled && ruleVersion isBlank) throw`——即只在 `enabled=true` 时才强校验规则版本**（`enabled=false` 停用评估时放行，不因缺 rule-version 启动失败）。§5 补用例：`enabled=false` 时缺 rule-version **不抛**；`enabled=true` 缺 rule-version **抛**（防 prod 静默 500）。

## 4. 数据流

```
用户反馈 → rag_session.feedback（已有，不变）
   ↓
POST /api/eval/dataset（ADMIN/USER, from/to 非空, limit缺省50）
   → schema = TenantContext.getSchema()（服务端反查）
   → @Select join（ignoreTable 豁免 + e/s 显式租户断言 + DISTINCT ON + e.id 排序）
   ↓
POST /api/eval/regression（POST, 先判空样本、后抢锁, tryLock(30s) 失败→409）
   → dataset（limit 透传）→ **0 样本 → 不落快照、不抢锁，立即返回明确提示**
   → 仅样本非空才 tryLock（tenant 键锁）→ 抢到重跑+落快照
   → Java 侧指纹 + rule_version
   → evaluateNoCache 逐个重跑(doEvaluate→EvalDecision)
   → 四格 + accuracy/precision/recall/负类召回/F1/三维一致率/persisted_pass_agree
   → 显式租户落快照 → 返回 reportId
   ↓
GET /api/eval/history（分页, 按指纹归因）
```

## 5. 测试策略（最窄范围）

- **rag `AnswerEvaluationServiceTest`**：
  - `dataset`：feedback≠0 纳入；DISTINCT ON 内层 + `e.id DESC` 取最新（同轮多行同秒）；**外层 `ORDER BY t.create_time DESC` + `LIMIT` 取最新 n 会话（非最老——防数据集冻结，五轮阻断项）**；显式 `e`/`s` 租户断言；`TenantContext.getTenantId()` null→400（六轮 tenantId 单源）、`TenantContext.getSchema()` null/blank→400；schema 白名单；from/to 非空；limit 缺省 50 / **≤0 回落 50** / 越界≤200；**越权 schema 名被拒**；
  - `regression`：TP/TN/FP/FN + 各指标；F1 p=r==0→0（全 FN）；分母 0 退化；**0 样本不落快照且返回明确响应结构**（HTTP 200 + 提示字段，mock 验证不落库）；**0 样本时锁未被申请（十二轮新增：验证 dataset 返回空 → 未调用 tryLock/未持锁，把「先判空、后抢锁」顺序钉死，见 §3.3）**；`evaluateNoCache` 不触发 Redis（R3）；快照显式 tenantId(null 拒绝)+fingerprint+rule_version+persisted_pass_agree 正确写入；**指纹 = Java 侧排序拼接后 md5，与返回集一致**；**rule_version == 配置值**；**类锁/同租户串行在 Service 层（tryLock 未申请即跳过）**；
  - `doEvaluate`→`EvalDecision`：`evaluate`/`evaluateAndPersist`/`evaluateNoCache` 三路径结果一致；落库侧 dimensionScores 用全名键；**EvalDecision→AnswerEvalResult 还原规则**（pass=三维与、score=均值、dimensionScores 值 1.0/0.0）正确；**直接断言 `EvalDecision.pass == AnswerEvalResult.allPass(passes)`**（改为测试断言而非各自推导等价性，防两处口径漂移）。
  - **`EvalProperties` 配置绑定**：`rule-version`/`dataset-limit-*`/`regression-lock-timeout-ms` 读取生效；缺省值兜底；配置失效场景防回归。
- **web `EvalControllerTest`**：ADMIN/USER、viewer 拒、租户头缺拒、越权过滤、**三接口共用 schema/tenant 校验（`/history` 同样 tenantId/schema null→400、越权 schema 名→400）**、/history 分页（page/pageSize 缺省 1/50 + **手写 LIMIT/OFFSET + 独立 count 的 total 正确性与越界回落（≤0→50、`>historyPageMax`→200，十二轮独立键）** + `run_time DESC, id DESC` 不漏行 + **分页对象结构 records/total/size/current 断言**）、/regression 并发（**同单实例**同租户串行/异租户并行、**锁在 Service 层、经端点到 Service 断言进程内锁生效，Controller 不自行加锁（十轮钉死）**）、tryLock 失败→409、from/to 缺→400、**limit 越界≤200 对 dataset/regression 统一断言（九轮补 /regression 用例）**、**0 样本响应结构（HTTP 200 + `code=200` + 提示 msg + `data=null`）在回归用例断言**。
- **跨租户 IT（M1）**：两租户数据互不可见。
- **schema 建表测试**：`TenantServiceImplSchemaTest` 补新表建表 + 索引 + RLS + 幂等（DROP POLICY 重跑）；**DDL 片段一致性哨兵（比对 createTenantSchema 与 runner 两处渲染出的 DDL 片段逐字符相同）**；**新建 + 存量 schema 的 `rag_session` 均含 feedback 列 + `idx_<schema>_session_feedback` 索引（九轮改，存量经 runner 修复后同样断言，防 column does not exist / 无索引过滤，七轮+八轮+九轮）**；**runner 修复断言：存量 schema（列已存在）重跑后索引已建**；**新表已具 DELETE 权限（blanket GRANT/ALTER DEFAULT PRIVILEGES 口径，七轮）**。
- **迁移 runner 测试**：存量 schema 补表幂等（对齐 migrateAnswerEvalResultTable 用例）。**runner 结构断言（十三轮 + 十四轮钉观测点）**：①「已迁移（列已存在）」schema 重跑后**未**打印「成功添加 feedback 列」日志、**未**计入 `migratedCount`，仅计 `skippedCount`；②「未迁移」schema 重跑后**计入** `migratedCount`、打印该日志——即 `log.info`/`migratedCount++`（:82-83）只在 else 分支、无共享尾（防一个 schema 同时「跳过」又「成功迁移」，见 §3.1）。**观测点（十四轮钉死）**：reader 无 `migratedCount` 可读——它是 `migrateRagSessionFeedbackColumn`（:46）的**方法内局部变量**，lambda 跑完即弃，**唯一出口是 :91 那句 `log.info("rag_session 表 feedback 列迁移完成：成功 {} 个，跳过 {} 个", migratedCount, skippedCount)`**。故断言**只能捕日志文本**：以 `ListAppender<ILoggingEvent>` 挂 `logback-spring` 到该 runner 的 Logger 捕获 :91 输出，断言「未迁移 schema 重跑后日志含 `成功 1 个`、不含新增 `成功 N(N≥2)`」，已迁移 schema 重跑后仍含 `成功 1 个`（不+1）。**文档钉死只从 :91 日志文本观测**；`migratedCount`/`skippedCount` 不得改成字段或返回值（会破坏 :91 出处与整体 runner 范式一致），实现者照 :91 文本断言即可，勿猜测其它出口。
- **EvalProperties 测试（七轮 + 八轮改缺省写法 + 九轮补键名对齐 + 十二轮补 enabled 门控断言）**：`@Component` 注册生效（context 装配断言）；**字段初始值**缺省兜底（30000/50/200/false）与显式覆盖（**不用 @DefaultValue**，八轮）；**relaxed binding 断言：`rag.eval.regression-lock-timeout-ms: 5000` 显式配置后 `getRegressionLockTimeoutMs()==5000`（防字段名 `lockTimeoutMs` 与键不匹配导致静默落回初始值，九轮阻断项）**；rule-version 缺失时 `@PostConstruct` 启动断言——**`enabled=true` 抛错（防 prod 静默 500）、`enabled=false` 不抛（防停用评估时启动失败，十二轮）**。
- 验证命令：`mvn -pl company-rag-rag -am test -Dtest=AnswerEvaluationServiceTest`、`mvn -pl company-rag-web -am test -Dtest=EvalControllerTest`（**-am 编译依赖模块**，四轮）。

## 6. 改动清单

- **租户插件**：Modify `TenantMyBatisPlusConfig.java`：`ignoreTable` 追加 `answer_eval_result`、`rag_session`（**不新增 PaginationInnerInterceptor**，见 §3.4）。
- **数据库**：Create `EvalRegressionReportDdl`（**common 模块唯一 DDL 源，静态 `build(schemaName)`，只管 `eval_regression_report`，含建表+索引+RLS+授权；九轮收敛职责，不承载 rag_session 补丁**）/ Modify `TenantServiceImpl.createTenantSchema`（新租户：**feedback 列走 `buildCreateTableSql`**、**`idx_<schema>_session_feedback` 索引走 `buildCreateIndexSql`，该方法 `.formatted` 从 22 补到 24 个 schemaName 实参（九轮阻断项，严禁写入 buildCreateTableSql）**，见 §3.1）/ **Modify `SchemaMigrationConfig`（① 新增第 6 份 runner `migrateEvalRegressionReportTable`：抽公共 `forAllTenantSchemas` helper 遍历存量 schema，调唯一 DDL 源；不重构既有 runner；② **修复 `migrateRagSessionFeedbackColumn`**：把 `if (columnExists != null && columnExists) continue;` 改写为 `if/else`、**删除 `:65` 的 `continue;`**（skippedCount++ 保留在 if 分支），`ALTER ADD COLUMN` 移到 else 分支，`CREATE INDEX` 落在 if/else 之外、`IF NOT EXISTS` 幂等，未迁移先 ALTER 再建索引（**十二轮增补：删 continue 是必要条件，否则存量租户在 :65 即跳走、索引仍不可达**，见 §3.1）**）** / Modify `sql/init.sql`（新增**注释形式**的 `业务表模板` 式 DDL 参考段，不参与执行）。
- **rag 模块**：
  - Create `LabelledEvalSample.java`（含 tenantId + evalId）
  - Create `EvalRegressionReport.java`（报告 DTO，含地位字段）+ `EvalRegressionReportEntity.java`（快照实体，列对齐）
  - Create `EvalRegressionReportMapper.java`（`@Select` 插入 + 历史**手写 LIMIT/OFFSET + 独立 count** 分页，不依赖 MP 分页插件，见 §3.4）
  - **Create `EvalProperties.java`（`@Component` + `@ConfigurationProperties("rag.eval")`，承载 **enabled**/rule-version/lock-timeout/dataset-limit/**history-page-max** 等；数值/布尔用**字段初始值**给缺省（**`enabled=false`**/**`regressionLockTimeoutMs=30000`**/`datasetLimitDefault=50`/`datasetLimitMax=200`/**`historyPageMax=200`**/`regressionGateEnabled=false`，**字段名须与键 relaxed 对齐，九轮**），**禁用 `@DefaultValue`（八轮，@Target 不含 FIELD）**；`ruleVersion` 留 null，`@PostConstruct` 仅在 `enabled=true` 时断言非空（**十二轮：enabled=false 停用评估不抛，防启动失败；historyPageMax 独立于 datasetLimitMax，见 §3.6/§3.4**））**
  - Modify `AnswerEvalResultMapper.java`：新增 `@Select selectDataset`（含 `s.tenant_id` 断言 + `persistedPass`（`persisted_pass` 列）回显，十轮改名）
  - Modify `AnswerEvaluationService.java`：抽 `doEvaluate`→`EvalDecision`（约定位落库映射规则）；新增 `evaluateNoCache`、`dataset`、`regression`（**tenantId 取 `TenantContext.getTenantId()`** + TenantContext schema + 指纹 + rule_version + persisted_pass_agree + 空样本不落 + 显式租户落库；**persisted_pass_i 取 dataset 返回的 `persistedPass`，不按 eval_id 回查，见 §3.3**；**锁唯一落点在此：Service 层 `ConcurrentHashMap<tenantId,ReentrantLock>` 键锁 + tryLock(30s)→409，十轮钉死，Controller 只转发 409，不自行加锁**）
- **web 模块**：Modify `EvalController.java`：新增 `POST /dataset`、`POST /regression`、`GET /history`（ADMIN/USER；from/to 校验；**limit 收敛为新增实现（十一轮，非 listResults 复用）：缺省 50、≤0 回落 50、`>200` 收敛到 `datasetLimitMax(200)`**，`/regression` 缺省同 50 原样透传；**越界 200 对 dataset/regression 统一**（九轮）；**/history pageSize 上限用独立键 `historyPageMax`（十二轮，不再借 datasetLimitMax）**；**三个接口共用 `resolveTenant()/resolveSchema()` 校验**（tenantId null→400 + schema null/blank→400 + 白名单）；**不加锁——锁在 Service 层（十轮钉死），Controller 遇 tryLock 失败 409 仅转发**；0 样本 → HTTP 200 + `code=200` + 提示 msg + `data=null`；分页对象 records/total/size/current + 二级排序）。
- **bootstrap 模块**：Modify `SchemaMigrationConfig`：新增 `migrateEvalRegressionReportTable`（第 6 份 runner，调公共 `forAllTenantSchemas` helper + `EvalRegressionReportDdl.build`，见 §3.2.3）；**修复 `migrateRagSessionFeedbackColumn`——把 `if (columnExists != null && columnExists)`（:62）→ `continue;`（:65）改写为 `if/else`，**删除 `:65` 的 `continue;`**（skippedCount++ 保留在 if 分支），`ALTER ADD COLUMN` 移到 else 分支，`CREATE INDEX idx_<schema>_session_feedback`（现 :76-80，在 if 块内）移到 if/else **之外**、`IF NOT EXISTS` 幂等——未迁移租户走 else 先 ALTER 补列再建索引，已迁移租户走 if 跳过 ALTER 但仍建索引（**十二轮增补：删 continue 是必要条件，只挪语句不删 continue 对存量租户无效**）；**`log.info("...成功添加 feedback 列")`（:82）与 `migratedCount++`（:83）只能留在 else 分支内、无共享尾**（防已迁移 schema 被误计 migratedCount 且打印「成功添加」，十三轮），见 §3.1）**。
- **common 模块（七轮）**：Create `EvalRegressionReportDdl.java`——**唯一 DDL 源** `public static String build(schemaName)`（建表+索引+RLS+授权整段），tenant 的 `createTenantSchema` 与 bootstrap 的 runner 都经 common 调用，见 §3.2.3。
- **配置**：Modify `application.yml` **基段**新增 `rag.eval`（不只 dev，见 §3.4）——**必须含 `enabled: true`（八轮阻断项，见 §3.6）**，`application-dev.yml` 覆盖具体值：
  - 新增 `EvalProperties`，前缀 `rag.eval`，键：`enabled: true`（**装配开关，基段必须有；同时作为 EvalProperties 的 enabled 字段，`@PostConstruct` 仅在为 true 时断言 rule-version，十二轮见 §3.6**）、`regression-gate-enabled: false`（占位）、`rule-version: v1.0`（**基段必须给具体值，不能留空（十三轮阻断项）**——基段是 `enabled: true` + `rule-version` 留空 + `@PostConstruct(enabled=true 时断言非空)` 三合一 = **应用首次启动即崩**；`ruleVersion` 字段初始值 null，仅靠基段/环境提供的值填充，`enabled=true` 时 `@PostConstruct` 断言非空保底；**真实版本源**：改规则须同步把版本号改大（v1.0→v1.1…），prod 用不同版本在 prod 配置覆盖 `rule-version`，否则快照 `rule_version` 停留旧值误标同批）、`regression-lock-timeout-ms: 30000`、`dataset-limit-max: 200`、`dataset-limit-default: 50`、`history-page-max: 200`（**/history 每页上限，独立于 dataset-limit-max，十二轮见 §3.4**）（**缺省靠字段初始值，不写 @DefaultValue**）
  - `online-enabled` / `async-enabled` 复用既有（`online-enabled` 只管在线抽样，与端点装配无关）
  - 生产核对 `rag.eval.online-enabled: true`（§3.6）；**核对 `rag.eval.enabled: true`（基段已在，勿删）**
- **测试**：Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`、`TenantServiceImplSchemaTest`；新增跨租户 IT + migrate runner 用例；EvalProperties 断言 `@Component` 绑定 + 缺省兜底 + rule-version 非空启动校验（**`enabled=false` 时不抛、`enabled=true` 缺失才抛，十二轮**）；新建 schema `rag_session` 含 feedback**列+索引**、`eval_regression_report` 表/索引/RLS 且已具 DELETE 权限（§5）；**存量 schema feedback 索引建上（runner 修复断言，九轮）**。

## 7. 风险与观察项（正确口径 + 四轮）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **schema/IDOR（阻断项1）** | 客户端可传 schema+tenantId 构造他租户 | schema 取 `TenantContext.getSchema()`（JWT 服务端反查，客户端不可控）+ 白名单；X-Tenant-Id 已被 JWT tenantIds 校验 |
| **回归上下文局限** | 回归测固定历史 toolContext（company-rag-web 的 ChatController:178 快照），不覆盖检索/知识库更新 | 快照带 rule_version；文档明示口径边界 |
| **存量租户缺表（阻断项3）** | 仅 createTenantSchema 不覆盖存量 | `migrateEvalRegressionReportTable` runner 补存量 schema |
| **存量租户缺 feedback 索引（九轮阻断项，十轮补执行顺序、十二轮补结构——删 continue）** | `migrateRagSessionFeedbackColumn` 的 CREATE INDEX 在 `if (columnExists) continue` 分支内，存量租户列已存在 → 每次启动 continue（:65）→ 索引永远建不上 | **把 `if + continue` 改写为 `if/else`、删除 `:65` 的 `continue;`**，`ALTER ADD COLUMN` 移 else 分支，`CREATE INDEX` 落 if/else 之外、`IF NOT EXISTS` 幂等；未迁移租户先 ALTER 补列再建索引（防列缺失），已迁移租户跳过 ALTER 但仍建索引；§5 断言存量（列在）+ 新建 schema 均含索引 |
| **runner 修复结构/顺序做反（十轮+十二轮阻断项）** | 两条歧义落法：①只把 CREATE INDEX「移出 continue 分支」却**不删 `:65` 的 `continue;`**——存量租户仍在 :65 跳走，ALTER/索引都不可达，bug 原样保留，§5「存量已建索引」断言必然失败且被 catch 吞没人知道；②照 v5.6「无条件执行」把 CREATE INDEX 提到循环体最前——未迁移租户先建索引报 `column "feedback" does not exist` → 被 per-schema catch 吞(:85-88) → 列没补、索引没建、应用照常启动 | **措辞钉死「必须删除 `:65` 的 `continue;`、改写为 `if/else`，CREATE INDEX 位于 if/else 之外、不得早于 ALTER」**；§5 增「未迁移租户重跑后列+索引齐备」断言防次序/结构回归 |
| **feedback 索引塞错方法（九轮阻断项）** | 塞进 buildCreateTableSql（现 23 实参）多 2 个 %s → `MissingFormatArgumentException` → 建租户整段失败 | **钉死落 buildCreateIndexSql 并从 22 补到 24 个 schemaName 实参**；§5 断言索引在索引方法中 |
| **DDL 双份漂移（五轮 + 十四轮统一方法名）** | answer_eval_result 已在 buildCreateTableSql(t:236)与 runner(s:192)各写一份且授权口径漂移(runner 有 DELETE、build 无) | 新表 DDL **单一定义** `EvalRegressionReportDdl.build`，两路径引用同一源；设哨兵断言"两处 DDL 片段须逐字符相同"防未来分叉 |
| **init.sql 只存档不执行（五轮）** | init.sql 挂 docker-init 首次被 psql 执行；若把含 `<schema>` DDL 写入 public 会撞 sequence 授权(:146)；且业务表模板(:82-181)本就是块注释从不执行 | init.sql 仅注释存档；建表由 Java 侧唯一 DDL 源执行 |
| join 租户 ambiguous | 插件为两表各追加裸 tenant_id | ignoreTable 豁免 + @Select 手写 `e`/`s` 显式租户断言 |
| RLS 非兜底（M1） | 主防线 schema 隔离；RLS best-effort | SQL 显式租户断言为主；跨租户 IT 锁死 |
| 样本批不可复现归因 | 动态视图下次样本集变化 | 快照带 fingerprint+from/to+索引；fingerprint=f(完整输入)；/history 按指纹归因 |
| 同轮多行重复（M2） | 在线多入口成多行 | DISTINCT ON + `e.id DESC` |
| 回归污染缓存（R3） | evaluate 无条件写 Redis | 回归走 evaluateNoCache |
| 租户丢失（R2） | DTO 无 tenantId 落 0 | DTO 带 tenantId；快照显式 setTenantId+null 拒绝 |
| from/to 缺失静默空 | BETWEEN null 恒空集+200 | 非空校验→400 |
| 空样本撞 NOT NULL | string_agg 0 行 null（现已 Java 侧计算） | 0 样本不落快照+明确提示 |
| 数据源空 | `rag.eval.online-enabled` 默认 false+仅 RAG 行；**`rag.eval.enabled` 缺失 → 端点 404** | §3.6 两级前置声明；§6 基段补 `enabled: true` |
| 数据外泄（M5） | dataset 返回 context/answer 原文 | **本期只收新端点 `/dataset` `/regression` `/history`（统一 ADMIN/USER，十轮钉死）**；**既有 `/result` `/results` `/stats`（EvalController:55/66/81）仍 `isAuthenticated()`、viewer 可读，不在本期范围，列为后续加固（避免 scope 蔓延改既有行为）** |
| GET 重计算副作用（M4） | 重计算+落库+缓存风险 | POST；tenant 键锁；tryLock 失败→409 |
| 指标语义错位（M3） | pass 硬与门 vs humanLabel 主观 | 补 F1/负类召回/三维一致率/persisted_pass_agree；语义说明入文档 |
| F1 退化误报 | p=r==0 是灾难场景 | f1→0，与 precision/recall 分开处理 |
| rule_version 漂移 | eval 包无版本概念 | 配为 `rag.eval.rule-version`；测试断言写入==当前值；"改规则同步改版本"进评审检查项 |
| SQL 注入 | schema 名/输入 | schema 取 TenantContext + 白名单；其余绑定 |
| 快照表膨胀 | 只增不删 | 观察项；**本期不做保留/清理**，故逻辑上层不暴露 DELETE 写路径。**授权口径（七轮纠偏）**：`createTenantSchema` 步骤 6（TenantServiceImpl:147-154）有 `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %s`（晚于步骤 2 建表）+ `ALTER DEFAULT PRIVILEGES ... GRANT SELECT,INSERT,UPDATE,DELETE`（future 表自动），**runner 建表路径同样被 `ALTER DEFAULT PRIVILEGES` 覆盖**——故两条路径对 `eval_regression_report` **均已具 DELETE 权限**，DDL 里那句 `GRANT SELECT, INSERT` 只是冗余加固而非"唯一授权"。未来做保留策略时**无须补 DELETE**，仅需评估是否收窄为仅内务清理可删；若担心，可在建表后显式 `REVOKE DELETE ON <schema>.eval_regression_report`（本期不做，记录观察项） |
| 统计噪声误判 | 200 样本 accuracy CI≈±7% | 附录公式；以统计显著差异为门槛 |
| config 误配 | Mapper 未扫描；rag.eval 配置漂移静默读不到；**v5.9 及以前 `rag.eval` 只在 application-dev.yml（基段/prod/test 均无，十四轮标注现状已改）** | 新 Mapper 落既有 `com.company.rag.rag.eval.answer` 包（已 @MapperScan）；**新增 `EvalProperties`（`@Component + @ConfigurationProperties("rag.eval")` + **字段初始值**缺省 + rule-version 启动断言）统一绑定，并挂 application.yml 基段含 `enabled: true`**（§3.4/§3.6）；单测断言 |
| 多副本锁失效（五轮） | 进程内 ConcurrentHashMap 锁跨 JVM 失效，多副本各跑一遍各落快照 | **本期单实例假设**；多副本升级 Redisson RLock 分布式锁 |

## 8. 后续演进（本期不做）

- 固化逐样本数据集快照（`evalset` 表 + 版本）实现完整可复现评测。
- 硬门禁接入关键路径（`regression-gate-enabled` 占位）。
- 冷启动种子样本、反馈量不足采样降级。
- 回归报告/历史趋势接入可观测看板。
- 观察项：未来如需跨表别名插件级 join 租户自动追加，再评估处置 b（append）。

## 附录：超时与实际耗时基线 / 统计显著门槛

- **tryLock(30s) 语义（钉死）**：仅锁等待超时；**失败 → 409，不做任何重跑**。三个 `AnswerEvaluator` 均纯本地规则（无 HTTP/Embedding），600 次判定为内存字符串处理、毫秒级，**全流程几乎不可能超时**——30s 实际只在抢锁时触发。实现后补**实测 wall-time 基线**写回本附录。
- **最小可检测差异**：accuracy 95% CI ≈ `1.96 × √(p(1−p)/n)`，p=0.5 时取最大 ≈ `1.96×√(0.25/200) ≈ 6.9%`。**样本量越大区间越窄**（如 n=800 → ≈±3.5%）。团队对趋势对比应以统计显著差异为门槛，勿对 ±7% 内噪声调参。