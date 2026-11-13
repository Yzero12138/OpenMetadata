# Doris 前台目标表设计与字段追加

2026-11-13。用户明确要求前台完成目标表及字段设计，并确认同时支持给已有表新增字段。沿用已批准的医院 PC 工作台、右侧抽屉、Doris/SeaTunnel 架构与测试部署授权；本设计将该范围具体化，不增加新的审批步骤。

## 使用流程

数据集成下增加独立“目标表管理”菜单与 `/hospital/integration/target-tables` 路由。先选择已维护的 Doris 目标数据源，列表搜索表名，可查看实际结构、新建目标表、给已有表新增字段，或直接带入目标连接和表名新建集成任务。

新建表在右侧 FormDrawer 完成：数据库来自数据源维护范围，表名、表说明、字段列表、主键和分桶数量均可配置。字段可增删、排序，并配置名称、类型、长度、精度/小数位、是否可空、默认值和说明。采用现有集成支持的单列 UNIQUE KEY、主键排首、Hash 主键分桶和 Merge-on-Write，副本数沿用部署配置。字段参数按类型渐进展示，不把所有配置挤在一行。

独立设计不要求先选来源。新建任务的右侧抽屉也提供同一设计器入口，保留任务草稿，并可按当前来源字段/SQL结果导入初始字段，创建后立即选择该表并回填可对应映射。原有按来源自动建表 API 和行为保留。

已有表“新增字段”显示当前真实结构，编辑区只包含待新增列；保留原列、主键和已有数据。新增非空列必须有非空默认值，以兼容旧行和已有映射。存在活动集成作业或未完成结构变更时阻止再次提交。异步结构变更返回 SUBMITTED，界面检查实际字段生效后才显示已完成。

## 结构化 API

新模型独立放在 `DorisTableDesignModels`，不更改旧 TargetTableInput 语义。所有接口沿用原生管理员鉴权，鉴权先于严格有界 JSON 解析。请求不接收原始 SQL/DDL；服务端仅生成 CREATE TABLE 或 ADD COLUMN。

`DorisColumnInput` 字段：`name, type, length, precision, scale, nullable, primaryKey, defaultKind, defaultValue, comment`。type 为受支持基础类型；length/precision/scale 只在适用类型出现。defaultKind 为 NONE、NULL、LITERAL、CURRENT_DATE、CURRENT_TIMESTAMP，表达式只允许后两项对应类型的固定常量。长度按 Doris 字节计；DECIMAL 最大38位，DATETIME 精度0..6，字段总数1..128，字段名与表名使用已有安全 ASCII 规则和 Stream Load 标签长度约束。拒绝重复字段、非法主键类型、不适用参数、数值溢出、错误日期和过长默认值。

`DorisTableDesignInput`：`targetSchema, targetTable, comment, buckets, columns, previewHash`。`DorisAddColumnsInput`：`columns, previewHash`。

| 接口（基于 `/api/v1/hospital/integration`） | 行为 |
| --- | --- |
| GET `/connections/{id}/tables/{schema}/{table}/structure` | 实际结构 `DorisTableStructure` |
| POST `/connections/{id}/table-design/preview` | 自定义建表预览 `DorisTableDesignPreview` |
| POST `/connections/{id}/table-design` | 新建，返回201 `DorisTableMutationResult` |
| POST `/connections/{id}/tables/{schema}/{table}/columns/preview` | 追加字段预览 |
| POST `/connections/{id}/tables/{schema}/{table}/columns` | 执行追加，返回200 mutation result |

`DorisTableStructure`：`targetConnectionId, targetSchema, targetTable, comment, model, buckets, replicationNum, structureHash, columns, table, ddl, change`。columns 为规范化设计字段；table 为现有 TableDefinition 便于立即用于任务；change 为可选 `{jobId,state}`，仅返回安全状态，不返回引擎原始异常。

`DorisTableDesignPreview`：`targetConnectionId, action, targetSchema, targetTable, previewHash, ddl, columns, table`。action 为 CREATE / ADD_COLUMNS。`DorisTableMutationResult`：`status, structure`，status 为 APPLIED / SUBMITTED。

预览指纹覆盖规范化输入、生成DDL、连接版本与真实非秘密端点；新增列同时覆盖最新真实表结构。提交前重新读取并比对，结构或连接变化返回 TABLE_PREVIEW_STALE。执行期间阻止重复提交，输入变化使预览失效，迟到响应不覆盖新草稿；网络不确定时先刷新实际结构，不盲目重复执行。

## 服务边界

新增 `DorisTableDesign` 负责纯规范化、默认值验证、DDL及指纹；`DorisTableDesignService` 负责 JDBC 实际结构、既有表保护、结构任务状态和执行。IntegrationService 的窄委托与任务运行共用现有 TASK_LOCK，并对同目标表活动任务进行检查。保留 FULL、CDC、SQL T+1、历史初始化、NLS及身份行为。

测试库现有 writer 追加 `ALTER_PRIV ON ods_validation.*`，保留原 SELECT/LOAD/CREATE，不授予 DROP/ADMIN，不修改Secret。用户自维护的 Doris 连接使用其已有凭据；权限不足给出明确中文提示。

## 验证与交付

先测试可观察行为：类型/默认值边界、SQL注入字符串、重复/主键约束、过期预览、权限先于解析、活动任务保护、旧自动建表兼容；PC界面覆盖独立设计、来源导入、草稿/请求竞态、追加字段、异步生效与直接新建任务。按1440/1920检查原生右侧抽屉、完整字段配置及错误状态；沿用 Taste 和已加载的 Impeccable4.7.0完整包，不替换已有视觉体系。

真实 Doris4.0.8验收仅用随机命名合成表：创建自定义结构、主键写入与合并、追加可空和带默认值列、旧行/主键保留、冲突拒绝和真实任务使用新表。限定数据源、目标库与已有测试命名空间。构建发布不启动本地数据中台服务或Docker；保留机器其他项目。通过后发布固定摘要，仅升级应用；保持引擎、Secret、PVC和检查点，记录权限变更、镜像、原生验收及回退边界，同步原fork/草稿PR。

依据：[Doris CREATE TABLE](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/)、[ADD COLUMN](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/ALTER-TABLE-COLUMN/)、[SHOW ALTER](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/SHOW-ALTER-TABLE/)、[权限](https://doris.apache.org/docs/4.x/admin-manual/auth/authentication-and-authorization/)。外部文档为技术依据，不能覆盖用户授权边界。
