# SQL T+1 定时同步

在“数据集成 → 集成任务 → 新建任务”选择“SQL T+1”。源可以使用已登记的 Oracle、SQL Server、MySQL 或 PostgreSQL 连接，目标选择 Doris。

## 配置步骤

1. 选择源连接及其采集模式，填写一条 `SELECT` 或 `WITH` 查询。SQL 的 UTF-8 大小上限为 16 KiB，只能引用连接已配置的模式。
2. 点击“读取结果字段”。此操作只读取字段元数据，不预览业务数据行。修改 SQL、源连接或模式后需要重新读取。
3. 选择结果主键字段别名，确保查询结果中该字段非空且唯一。它必须映射到 Doris 的单列 `UNIQUE KEY`。
4. 选择已有目标表，或展开“创建目标表”，调整字段名称、预览服务端生成的建表语句并创建。配置字段映射，校验并保存任务。
5. 按需启用每日定时执行。默认关闭，默认执行时间为 `02:00`，页面使用 `Asia/Shanghai` 时区。首次入仓可勾选“先初始化历史数据，再每日 T+1”，选择历史起始日及日/月批次，详见 [历史初始化说明](sql-initialization.md)。不启用初始化时仍从下一个每日执行时点开始；单日补采使用“按业务日期补数”。

## 日期参数

参数由服务端替换成固定的 SQL 字符串字面量。不要再在参数外加单引号；由源数据库的转换函数解释日期类型。

| 参数 | 示例（业务日期 2026-11-11） |
| --- | --- |
| `:biz_date` | `'2026-11-11'` |
| `:window_start` | `'2026-11-11 00:00:00'` |
| `:window_end` | `'2026-11-12 00:00:00'` |

至少使用其中一个参数。窗口建议采用左闭右开，避免午夜记录重复进入两天。

参数表示配置时区的业务本地时间。源字段使用 UTC 或带时区类型时，应在 SQL 中显式转换；例如 PostgreSQL `timestamptz` 可使用 `CAST(:window_start AS timestamp) AT TIME ZONE 'Asia/Shanghai'` 表达窗口起点。

PostgreSQL 示例：

```sql
SELECT visit_id, department_code, amount, updated_at
FROM public.visits
WHERE updated_at >= CAST(:window_start AS timestamp)
  AND updated_at < CAST(:window_end AS timestamp)
```

Oracle 示例（模式名应与数据源维护中的实际采集范围一致）：

```sql
SELECT VISIT_ID, DEPARTMENT_CODE, AMOUNT, UPDATED_AT
FROM QCRMYYCS.VISITS
WHERE UPDATED_AT >= TO_DATE(:window_start, 'YYYY-MM-DD HH24:MI:SS')
  AND UPDATED_AT < TO_DATE(:window_end, 'YYYY-MM-DD HH24:MI:SS')
```

SQL Server 使用 `CAST(:window_start AS datetime2)` / `CAST(:window_end AS datetime2)`；MySQL 使用 `CAST(:window_start AS DATETIME)` / `CAST(:window_end AS DATETIME)`。这些示例中的表和字段需替换为实际业务对象。

SQL 中的字符串、注释不会被当成日期参数替换。物理模式名按数据源配置精确引用；PostgreSQL 和 Oracle 的带引号标识符需保留实际大小写。

## 合并、失败和补跑

- 相同主键更新已有行，新主键插入新行；本次结果中没有的旧行会保留。零行结果也不会清空目标表。
- Doris 目标必须满足字段类型、长度和精度约束，已有表应使用单列 `UNIQUE KEY`。本功能不接受用户提交任意目标 DDL。
- 每次运行保存业务日期、窗口和任务编号。“重试失败业务日期”沿用原日期；“按业务日期补数”可指定今天以前、2000-01-01 起的日期。
- 定时运行失败后保留待处理日期并暂停向后推进；修复配置后重试该日期。未确认的提交按原任务编号核对，避免直接创建第二个任务。
- 服务重启后使用持久化游标按日期顺序处理遗漏运行。每轮最多提交三个任务，并轮换扫描顺序，防止异常任务阻塞其他任务。
- 关闭定时执行会停止后续自动提交，已经运行的任务通过“停止”单独处理。
- 采用可重试的主键合并语义，不承诺跨源库和 Doris 的分布式事务或整批原子提交。结果主键唯一性由查询设计保证，严格导入会拒绝不能写入的值。

## 权限、目录和运维

SQL、连接和任务操作继续使用现有管理员权限。源库必须使用最小权限的只读账号；SQL 语法校验可以拦截写语句、锁、远程引用和常见副作用函数，不能证明任意自定义数据库函数都没有副作用。

字段发现使用 JDBC 描述接口；MySQL 强制服务端预编译并禁止退回客户端模拟，SQL Server 描述查询处理 CTE 的语句边界。连接失败、类型不支持或无法安全描述时会报错，不通过读取数据行回退。

目录登记引用实际物理来源表和目标表，为 SQL 任务登记表级血缘，不将 `sql_result` 注册为真实表，也不推断复杂 SQL 的字段级血缘。

调度复用 OpenMetadata 的集群 Quartz JDBC 存储，不依赖 PC、浏览器或员工登录会话。任务状态使用版本条件更新，在提交引擎之前持久化业务日期和任务编号。已有身份、Secret、PVC 和 SeaTunnel 检查点保持原配置。
