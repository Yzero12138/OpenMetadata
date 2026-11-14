# 历史初始化测试环境发布（2026-11-13）

## 入口与版本

- 院内入口：[Integrate 应用门户](https://integrate-dev.qcrmyy.local/s/portal) → 数据中台 → 数据集成 → 集成任务。
- 应用地址：`http://172.16.120.211:30925`，沿用工号统一登录；初始化配置、编辑和补跑仍使用 PC 右侧抽屉。
- 功能源码提交：`08ac7f3c4155dd16814c5d22dc7f61eed81aa565`。
- 发布标签：`harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v13-sql-initialization-20261113`。
- 运行镜像：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:a7fbf27e96283e9f7dc3cd81d0a58f0a18e45240b4e81ac0450747e091e6da16`。
- 上一镜像：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:53a696d608db57de47d8f0e09e6a45376dfe34da4249b168a18d7adca36b4e93`。

通过 Harbor API 复用全部原镜像层，仅追加服务/UI两个 JAR，保留原运行配置；本轮未启动 Docker 或本地数据中台测试服务器。当前机器的其他病历项目服务和容器未修改。测试环境先 dry-run，再仅替换应用容器镜像。

| 运行文件 | SHA-256 |
| --- | --- |
| `openmetadata-service-2.0.4.jar` | `5944e444fd58af05ae2fef2da2fc90c454342be5e0363322364a4d57ce02cdc9` |
| `openmetadata-ui-2.0.4.jar` | `7db9c3cd489c9d2f132011e429749f9dd8d26d144bb114f5adf755cbb25d7ecd` |
| 保留的 `orai18n-19.32.0.0.jar` | `b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56` |

实际就绪 Pod 内文件摘要与构建一致。`datahub-open-test` 的8个 Deployment/StatefulSet 仅应用镜像改变；40个 ConfigMap/Secret/PVC 引用对象的 UID、资源版本及卷绑定保持一致，迁移镜像、SeaTunnel、身份、Secret和检查点保留。15项 HTTP/认证检查及138项静态资源字节比对通过；新增初始化接口对匿名畸形请求先返回401。

## 真实 Quartz 故障恢复与每日衔接

辅助 JAR 仅包含验收程序，所有生产类从已发布 `/opt/openmetadata/libs/*.jar` 加载，未直接调用手动运行、调度分发或断点结算。原生 PostgreSQL 集群 Quartz 每60秒检查，实际提交、执行、查询和持久化均由服务完成。

为在同一天验证衔接，创建任务时单独使用固定时钟模拟 `2026-11-12 10:00 Asia/Shanghai`；后续操作、Quartz和引擎均使用真实时间。此验收不是实际等待24小时，也没有重启应用 Pod；重新打开辅助 JVM 并读取原生持久状态验证继续操作。

- 临时任务：`675b096a-7170-4d3f-a98c-b77f3e881057`；初始化ID：`539976ba-569f-4b7a-abf9-17a92c911135`。
- 历史起始日 `2026-10-20`，冻结截止日 `2026-11-12`（不含），MONTH共2批，最后完成2/2。
- 第2批源 SQL 故意执行逐行除零，真实引擎返回FAILED；断点保持1/2、下一日2026-11-01、原窗口及作业编号，日常游标未提前生成。
- 修复该合成行后，在新辅助 JVM 通过正常CONTINUE操作恢复：只以新作业编号重跑第2批原窗口。已完成首批的源金额特意改为999.00，目标仍保留10.50，证明没有回退重扫首批。
- 历史完成后自动按原截止日启动业务日期2026-11-12的每日作业；最终FINISHED，待处理窗口清空，下一日游标为 `2026-11-14 00:05 Asia/Shanghai`。
- 原生 PostgreSQL 过期版本写入和过期版本CONTINUE均被拒绝；整个过程同一任务最多1个活动作业。

| 类型 | 批次 | 窗口（起始含，结束不含） | 结果 | SeaTunnel作业编号 | 读取 | 写入 |
| --- | --- | --- | --- | --- | --- | --- |
| INITIALIZATION | 1 | 2026-10-20 00:00:00 → 2026-11-01 00:00:00 | FINISHED | 6570635650045953895 | 1 | 1 |
| INITIALIZATION | 2 | 2026-11-01 00:00:00 → 2026-11-12 00:00:00 | FAILED | 1714306873505474998 | 0 | 0 |
| INITIALIZATION | 2 | 2026-11-01 00:00:00 → 2026-11-12 00:00:00 | FINISHED | 2207390303354692922 | 1 | 1 |
| SCHEDULED | — | 2026-11-12 00:00:00 → 2026-11-13 00:00:00 | FINISHED | 6087827405771205472 | 2 | 2 |

Doris UNIQUE KEY 最终恰好3行：ID1金额10.50保留，ID2从20.00合并为30.25，新增ID3金额21.25。起始日前及当天尚未到期数据均未采集；没有清表、覆盖或删除旧行。

验收后先禁用该UUID任务的调度，核对4个引擎作业全部终态，再仅删除该临时原生任务记录和源表。受限目标账号没有DROP权限，保留纯合成目标表 `ods_validation.sql_init_qz_2ee914a3950b`。引擎历史中1个FAILED是本次主动注入的故障，不能据此报告失败数为0。初次验收夹具误填SQL虚拟表名，在配置校验阶段拒绝且无任务/作业；清理临时源表并修正辅助程序后上述完整链路通过，应用代码无需改动。

源范围仅 `synthetic_source.public`，目标仅 `ods_validation`，未读取真实 HIS 数据。日/月底/闰年、跨日追赶及旧任务兼容等209项后端和114项UI验证见 [验证记录](sql-initialization-verification.md)，操作与SQL示例见 [使用说明](sql-initialization.md)。

## 回退边界

本版不修改元数据库结构，但新增初始化状态字段及多日窗口语义。存在这些任务时不能直接切回旧镜像：先暂停自动提交，等活动作业终态，备份任务JSON、断点、运行历史和Quartz组状态；将新初始化记录安全迁出旧扫描范围或完成兼容转换后再恢复上一固定镜像。不得清空元数据库或丢弃用户历史。Secret、PVC、Oracle NLS与引擎检查点无需回退。
