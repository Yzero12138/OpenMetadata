# SQL Server CDC 接入和验收边界

日期：2026-11-14。实现和测试发布执行记录另见本轮验证报告。本文件说明首版产品配置与后续实库验收要求；连接器离线验证不等于数据库同步验收。

## 使用流程

1. 在“数据集成 → 数据源管理”登记 SQL Server 来源，填写实际主机、固定 TCP 端口、数据库、允许采集的 Schema、版本和访问凭据。凭据只在平台填写，不提交到代码或聊天。
2. DBA 根据实际测试库检查 CDC 配置与读取权限。平台仅做只读预检，不执行启用 CDC、授权、SQL Server Agent 操作、重启或源表修改。
3. 在数据源和任务抽屉执行 CDC 前置检查。数据源级检查与具体任务的主键、字段、目标映射检查分别生效；程序已适配不表示当前表满足启动条件。
4. 在“目标表管理”准备独立的 Doris Unique Key 目标，并在“集成任务”的右侧抽屉配置来源表、单列非空主键、完整目标字段映射及源时区。CDC 默认首次快照后接续增量；独立 FULL 后启动 latest 不能替代这一过程。
5. 全部必需检查通过后才能启动。任务暂停后的恢复复用原检查点并重新检查来源身份、采集实例及字段形状。日志过期、实例重建或结构变化不能自动跳过；须明确重新初始化或补数方案。

## 首版限制

- 以独立 SQL Server 2019 的 Standard/Enterprise，以及测试用 Developer/Evaluation 为基线。2016（包括 SP1）及其他未知版本、拓扑仍需单独验收，不能只因源库具有 CDC 功能就视为当前组合已验证。
- 需要已启用的库级、表级 CDC，单一且可确认的采集实例，覆盖映射字段及主键的捕获列，可读取的变更函数和 CDC 元数据，以及可观测的 Agent、采集作业、保留配置和有效 LSN 范围。原生首次快照使用 `SELECT *`，读取权限必须覆盖源表全部字段；字段映射不会缩小这一步的读取范围。权限不足显示失败或未知，不会当成满足条件。
- SQL Server 2019 开启 ADR 与 CDC 的组合不纳入支持。事务复制、AG/故障切换、Azure 等拓扑没有通过本轮实库验收。
- 使用与系统目录实际大小写完全一致的普通 ASCII 来源标识符、明确的数据库和固定 TCP 端口。即使数据库排序规则不区分大小写，原生表身份仍区分大小写。当前原生连接器的 URL、过滤器和回填路径不适合隐式实例名、IPv6 或带正则含义的名称；不自动重写连接目的地。
- 身份列按实际值同步；tinyint 的 0–255 范围不会按有符号 Byte 缩窄。小数使用精确表示。rowversion/timestamp 是二进制版本值，不能当业务日期。
- 原生日期转换器在负微秒边界存在问题。首版对日期时间的小数秒精度采取保守限制，精度超过 3 的 CDC 时间字段暂时阻断；类型规则作用于全部捕获字段，未映射的捕获字段也不能绕过。不会扫描业务记录后猜测是否安全，也不静默截断。FULL/SQL 的既有类型规则不因此扩大或缩小。
- 二进制、rowversion、datetimeoffset、TIME、大字段/MAX、计算列及特殊类型在相应端到端表示没有验收前明确阻断。这里是当前平台组合的限制，不声称 SQL Server CDC 本身无法捕获这些类型。
- 字段和采集实例变化需重新检查；不自动传播破坏性 DDL。一个物理目标只允许一条受控写入链路。源快照和增量可能重放，依靠 Doris 主键合并与删除收敛，不承诺跨表事务或未经验证的端到端 exactly-once。

## 实库验收清单

待用户登记来源并提供专用测试记录范围后，使用独立 Doris 目标依次验证：首次快照；快照期间写入；INSERT/UPDATE/DELETE；同一事务内同一主键多次更新；主键变化；中文、补充平面字符、大整数、小数与日期边界；停止恢复、进程重启、目标短时不可用；LSN 清理过期；采集实例或表结构变化。分别对比来源、目标及恢复后的结果，不能只用引擎累计计数代替落盘对账。

检查点接口没有暴露可信的源端已提交 LSN；界面不从源最大 LSN 或日志文字推算同步提交位置。检查点状态、目标行数与数据一致性分别验证。

原生分片还读取分区统计 DMV。SQL Server 2019 的该 DMV 需要数据库状态及定义可见性；预检会确认相应只读证据，平台不会自动授权。具体最小权限由 DBA 按测试库核对。[Microsoft 分区统计权限说明](https://learn.microsoft.com/en-us/sql/relational-databases/system-dynamic-management-objects/sys-dm-db-partition-stats-transact-sql?view=sql-server-ver15)

## 依据

- [SeaTunnel 3.0.0 SqlServer-CDC](https://seatunnel.apache.org/docs/3.0.0/connectors/source/SqlServer-CDC/)及同版本 Maven 二进制/源码。
- [Microsoft CDC 已知问题和错误](https://learn.microsoft.com/en-us/sql/relational-databases/track-changes/known-issues-and-errors-change-data-capture?view=sql-server-ver17)
- [Microsoft CDC 变更读取函数](https://learn.microsoft.com/en-us/sql/relational-databases/system-functions/cdc-fn-cdc-get-all-changes-capture-instance-transact-sql?view=sql-server-ver17)
- [Microsoft SQL Server 2019 版本类型](https://learn.microsoft.com/en-us/sql/sql-server/editions-and-components-of-sql-server-2019)
