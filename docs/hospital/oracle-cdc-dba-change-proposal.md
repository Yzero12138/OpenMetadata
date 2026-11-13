# HIS Oracle CDC 源库变更提案

状态：供 DBA 和院方审阅，尚未执行。开发范围已获确认；本文件不代表已获归档、授权、重启或源表写入的批准。

## 对象和已知现状

- 数据源 `his_cs`，`172.16.100.211:1521`，服务名 `ORCL`；实际表 `QCRMYYCS.ZYYS_YZXX`。
- 2026-11-13 的只读检查：Oracle `11.2.0.1.0`，字符集 `ZHS16GBK`，`NOARCHIVELOG`，数据库最小补充日志未启用。
- 102 个字段，单列非空主键 `YZLSH NUMBER(18)`，其中 `BLYY` 为 CLOB。已存在日志组中的无条件记录仅覆盖主键，不能当作 101 个非 LOB 字段的全覆盖证明。
- SeaTunnel `3.0.0`、内含 Debezium Oracle `1.9.8.Final`、JDBC/NLS `19.32`。JDBC 连接成功和全量复制结果不能替代这个组合的 CDC 验证。Oracle 的 JDBC 19.x 认证表列明 `11.2.0.4`，当前 `11.2.0.1.0` 需要保留未认证提示。[Oracle JDBC 兼容性](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/JDBC-getting-started.html)

## 第一步：先确定可验收的字段范围

当前连接器组合对“未修改的 CLOB 在后续整行更新中的保留”缺少可靠保证。第一批平台会阻止包含 CLOB/NCLOB 映射的 Oracle CDC，错误码为 `ORACLE_CDC_LOB_UNVERIFIED`。全量读取 CLOB 的能力保持独立。

若先验证不含 `BLYY` 的 CDC，必须创建独立的 101 字段目标表，并将该目标的全部业务字段纳入映射。禁止向已经有 `BLYY` 的 102 字段全量目标发送缺少该列的整行更新。完整 102 字段 CDC 需要另行完成大字段变更、未变更、置空与恢复重放的适配和验收。

最终集成审查还确认，固定连接器会将模式名和表名直接用于正则过滤，并在快照回填时重复设置该过滤器。因此第一批Oracle CDC明确拒绝模式名或表名中的`$`，避免条件被误判就绪后却无法发现表。现有HIS对象不含该符号；这一限制不扩展到普通全量读取或字段名。

## 第二步：DBA 制定日志与恢复窗口

1. 记录当前数据库、日志组、归档目的地、空间、保留策略和备份基线，确认是否存在其他日志采集消费者。
2. 确认能覆盖首次快照、最长停机和增量追赶所需的归档保留时间。归档磁盘容量和备份任务由 DBA 填入变更单。
3. 当前为 `NOARCHIVELOG`。切换归档模式需要实例关闭并在 mount 状态执行，DBA 应提供维护窗口、前后备份和业务恢复检查。平台不执行该操作。[Oracle 11g 归档模式管理](https://docs.oracle.com/cd/B28359_01/server.111/b28310/archredo003.htm)
4. 拟议补充日志范围为数据库最小补充日志和这一张源表的全部可记录列，避免扩大为整个数据库的 ALL COLUMNS。供审阅的语句如下，尚未执行：

```sql
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER TABLE "QCRMYYCS"."ZYYS_YZXX"
  ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
```

ALL COLUMNS 补充日志不涵盖 LOB 等特殊类型，不能用它宣称 CLOB 增量已经可用。开启表级标识日志可能导致该表现有 DML 游标失效，应纳入窗口评估。[Oracle 表级补充日志](https://docs.oracle.com/cd/B28359_01/server.111/b28322/man_gen_rep.htm)

## 第三步：核对已有权限，按缺项授权

平台预检只查询实际会话可见的版本、日志、元数据和权限，不自动授予 DBA、ANY TABLE 或 ANY TRANSACTION 等权限。已有授权、PUBLIC 和当前启用角色均纳入证明；访问不明或不完整的检查显示 UNKNOWN 并阻止启动。

本轮平台将固定 `log.mining.read.only=true`、`log.mining.strategy=online_catalog`、`skip_analyze=true`、`use_select_count=true`，关闭自动 schema 变化，并不允许任意 `heartbeat.action.query`。这一配置避免连接器的刷盘辅助表写入、ANALYZE 和 redo 字典构建，但真实日志采集仍会建立会话并调用 LogMiner，需要 DBA 审阅对应权限。

本轮新候选的实际只读预检已确认当前账号的包 EXECUTE 元数据、LogMiner 视图零行访问和版本所需权限检查通过，当前不提出新增授权语句；没有调用 LogMiner 包。后续若换为专用 CDC 账号，应重新检查具体缺项再授权。当前库并非 CDB。第一批不将尚未验证的 CDB/PDB 根容器路径标为就绪。[SeaTunnel 3.0.0 Oracle CDC](https://seatunnel.apache.org/docs/3.0.0/connectors/source/Oracle-CDC/)

## 第四步：分开批准运行和测试记录

日志配置完成后先重新执行只读预检。然后明确专用记录的主键范围、使用窗口、可变更字段和清理方式，再批准以下运行验收：

- 单个原生 CDC 任务完成首次快照并接续日志，使用独立 Doris Unique Key 目标。
- 范围内的 INSERT、UPDATE、DELETE，18 位主键、日期秒值与中文字段核对。
- 保存点停止与恢复、进程重启、目标暂不可用、归档日志过期时的明确失败行为。
- 对账使用目标实际查询结果；引擎累计事件数不作为唯一数据量。尚无可靠原生接口的快照/增量阶段与已提交 SCN 保持未知。

当前专用源记录范围尚未提供，因此不会执行源表增删改。单地址 TCP 1521 网络规则已经获批，无需为同一规则重复申请。

## 回退边界

先停止新 CDC 任务并确认引擎终态，保留任务、保存点、日志和独立目标。DBA 只撤销本次新增且已确认未被其他消费者依赖的授权或日志组，保留既有日志组。数据库归档模式和备份策略不随应用回退自动撤销；若确需变更，单独安排窗口和备份恢复评估。回退不删除 HIS 源记录或当前 Doris 验证表。
