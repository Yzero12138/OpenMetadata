# SQL 首次历史初始化（2026-11-13）

用户已确认：可选历史起始日期，按天或按月分批，失败后从批次断点续跑，补齐后自动接续每日 T+1，始终按主键合并更新。本设计扩展现有 SQL_T1 任务，保留现有 FULL、CDC 和未启用初始化的任务行为。

## 日期、进度和衔接

初始化首次保存配置时冻结截止日期 `cutoffDate`（配置时区的当天，右开）。历史区间为 `[startDate, cutoffDate)`，不因重试或服务重启而变化。尚未开始的配置修改起始日、批次单位或时区时重建未使用的计划，预览随之更新；已开始后禁止这些修改。

- DAY：每个自然日一批。
- MONTH：首批从实际起始日到下月 1 日，后续按自然月，末批截断到固定截止日；不采集起始日期以前的数据。
- 保存已完成批次数、总批次数、下一未覆盖日期、当前批次区间和作业编号。只有匹配当前初始化 ID、批次和作业的 FINISHED 记录才能推进进度，重复状态刷新不得重复计数。
- 断点粒度为已完成的批次。失败批次按原区间重跑，已完成批次保留；主键合并保证相同主键重复写入的更新语义。未确认提交继续核对原作业编号。
- 历史完成后，T+1 的首个业务日期为 `cutoffDate`，对应执行时间为次日配置的每日时间。这个游标即使已经过期也保留，现有调度依次追赶遗漏日期。
- 初始化和日常 T+1 互斥，单任务只能有一个活动批次。停止自动调度不取消当前批次；暂停与继续使用独立的状态操作，不依赖源库可用。

## SQL 和合并

沿用 `:window_start`、`:window_end`，按实际批次日期替换午夜字符串，使用左闭右开区间。月批次必须同时使用这两个参数，且不能使用只代表单日的 `:biz_date`；按天批次保留旧日期参数兼容性。SQL 读取字段仍只描述元数据，不预览数据行。

不改变 Doris UNIQUE KEY、字段映射或严格写入配置；缺少的旧行保留，不执行清表、分区覆盖或删除。不承诺失败批次内部的记录级恢复或跨库事务。

## 共享数据/API 合同

`TaskInput.initialization?: InitializationConfig`：`enabled:boolean`、`startDate:yyyy-MM-dd`、`batchUnit:DAY|MONTH`（默认 DAY）。历史初始化选中时，界面同步启用后续每日调度；后端要求启用初始化时提供日常调度配置。

`IntegrationTask.initializationState?: InitializationState`（服务端拥有）：

```text
id, status(PENDING|RUNNING|FAILED|COMPLETED), startDate, cutoffDate,
nextDate, batchUnit, completedBatches, totalBatches,
pendingStartDate, pendingEndDate, pendingJobId, sourceRouteHash, targetRouteHash,
startedAt, completedAt, lastError
```

`RunSummary` 增加 `initializationId?:string`、`batchIndex?:long`；历史运行使用 `trigger=INITIALIZATION`，现有 `windowStart`/`windowEnd` 保存真实区间，`businessDate` 为批次起始日期。

`POST /tasks/{id}/initialization`：严格请求 `{version:positiveLong, action:PAUSE|CONTINUE}`，原生管理员权限先于解析。PAUSE 关闭后续自动提交；CONTINUE 开启自动提交并将已知失败批次排回队列，正在提交或运行的编号保留。动作采用现有数据库版本 CAS。

初始化第一次提交后禁止修改其起始日、批次单位、源/目标路由、主键、字段映射和时区，避免旧进度与新数据流混用。可以修复 SQL 文本、名称等不改变数据流的配置；完成后保持历史进度，不因普通编辑再次初始化。新旧任务互不自动转换。

路由以不含用户名/密码的端点指纹绑定到实际作业配置；注册连接修改及环境管理的连接改址均须匹配该绑定。支持恢复原位置，保留显示名、启停和凭据修复能力。

## 分工与验证

- 根代理：模型、服务运行状态机、资源 API、集成回归、发布。
- 初始化规划单元：纯日期切分、冻结区间、幂等批次结算及 T+1 游标，含日期边界测试。
- SQL 窗口单元：安全替换真实批次边界及日/月参数校验，保留现有方言约束和每日语义。
- UI 单元：PC 右侧抽屉中的初始化配置、批次进度、暂停/继续和错误恢复；20 种语言。

测试先验证失败再实现。覆盖闰年、月末、部分月份、跨年、零行、失败重试、未知提交、重复刷新、并发 CAS、跨日初始化和历史到 T+1 无遗漏衔接。独立审查后使用隔离合成库与已部署 Quartz 验证，不读取真实 HIS。

本地服务器与 Docker 保持关闭；只运行无容器的明确测试类、静态 PC 夹具和离线构建。使用原应用镜像追加服务/UI 两个 JAR，在 datahub-open-test 仅更新应用固定摘要，保留配置、Secret、卷、身份、Oracle NLS 和检查点。
