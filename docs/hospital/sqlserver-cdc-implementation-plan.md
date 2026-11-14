# SQL Server CDC 实现与测试部署

日期：2026-11-14。依据：已确认的 Oracle/SQL Server 分期方案，以及本轮“SQL Server 也开始适配并部署到测试环境”“先完成适配和部署，稍后绑定 SQL Server”。本轮无需再次等待源库登记或镜像发布确认。

## 范围和验收边界

在现有 SeaTunnel 3.0.0 → Doris 链路增加 SqlServer-CDC，复用 Integrate 身份、右侧任务抽屉、字段映射、目标互斥、检查点恢复和只读前置检查。SQL Server 2019 为首个适配配置；2016、未知版本/版本类型和未验证的字段类型明确阻断，不把连接器加载或模拟测试写成实库验收。

尚未登记 SQL Server 数据源。本轮完成代码、离线原生连接器验证及测试环境部署；首次快照、增删改、并发更新、重启恢复和 LSN 过期的实库验收留待绑定源库后进行。不开启源库 CDC、不授权、不重启、不执行源表 DML，不新增未知地址的网络放行。保持本地测试服务关闭。

## 顺序

1. **引擎打包与原生验证**：固定 SqlServer-CDC 3.0.0 JAR 和摘要，在现有已发布 JDBC/Doris 基础镜像上加入 Oracle、SQL Server 两个连接器，保留驱动、入口和 `/data`。用无网络、只读、一次性容器验证实际工厂、类型、TLS 属性和四种变更事件；不启动本地数据库或引擎服务。
2. **服务端适配和只读预检**：增加 Mssql 适配器和明确的预检分支。实际版本/版本类型、数据库身份、CDC 标志、唯一采集实例、列覆盖、权限、Agent/采集作业、保留配置和 LSN 范围须有可信证据；权限不足或证据不足显示未知并阻断启动。新建默认 initial；不提供任意跳过 LSN。限制原生连接器不安全的名字/路由及未验证类型。完整目标行映射、单写入链路和脱敏错误沿用现有规则。
3. **恢复保护**：SQL Server 指纹绑定可信数据库、源表及采集实例身份，避免同名重建后错误续跑；不包含持续变化的 LSN/保留时间，不改变现有 Oracle/Postgres 指纹。每次启动/恢复均重新检查；过期日志由实际引擎失败，不自动重置为 latest。添加行为测试并执行 Java 格式检查。
4. **PC 界面**：在现有数据源和任务抽屉接入 SQL Server CDC、预检项和有限参数，保持 ACL、异步过期结果保护和中文操作流程。新增文案翻译到全部 20 个语言；执行 UI checkstyle、相关 Jest 和实际组件视觉检查。使用项目中的 design-taste-frontend / Impeccable，直接改代码。
5. **发布与测试升级**：完整构建服务和生产 UI，生成组合候选镜像（Oracle + SQL Server）。检查当前任务/引擎状态；短暂停止单副本应用并在现有元数据库 PVC 备份且校验精确任务扩展记录；固定摘要上传院内 Harbor，并只升级测试命名空间现有应用与引擎。保留 Secret、PVC、身份及网络策略。验证 rollout、健康和新能力，保存摘要、回退条件和真实验收待办；推送当前功能分支并更新现有草稿 PR，不合并。

## 实现细则

- SQL Server 原生数据库/表过滤存在正则与回填路径限制，第一版使用受限的普通标识符和显式数据库/端口；不支持实例名或 IPv6 路由的隐式重写。
- 不把 rowversion/timestamp 当时间；高精度时间、小数、大整数和 Unicode 不允许静默丢失。未证明安全的类型明确提示“本平台此组合尚未验证”，不声称 SQL Server 本身不支持。
- 2019 ADR 与 CDC 不兼容；Agent、作业或权限不可观测不能当成满足。不得因为 CDC 函数返回零就武断判定未配置。
- 每个实现任务由独立需求审查、代码审查后进入下一步；保留现有 Oracle/Postgres/FULL/SQL T+1 的验证。
- 以实际运行命令和产物摘要记录结果。模拟/离线原生验证、K8s 部署成功和实库端到端验收分别表述。

## 依据

- [SeaTunnel 3.0.0 SqlServer-CDC](https://seatunnel.apache.org/docs/3.0.0/connectors/source/SqlServer-CDC/)，同时核对 Maven 上的 3.0.0 源码及二进制。
- [Microsoft CDC 已知问题](https://learn.microsoft.com/en-us/sql/relational-databases/track-changes/known-issues-and-errors-change-data-capture?view=sql-server-ver17)
- [Microsoft 启用和禁用 CDC](https://learn.microsoft.com/en-us/sql/relational-databases/track-changes/enable-and-disable-change-data-capture-sql-server?view=sql-server-ver17)
