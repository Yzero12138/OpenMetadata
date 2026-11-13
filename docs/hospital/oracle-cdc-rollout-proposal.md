# Oracle CDC 第一批测试环境升级提案

状态：候选与验证已准备完成，等待发布及测试环境升级审批；尚未上传镜像或修改部署。本轮用户确认的范围为开发；HIS 数据库日志、授权、重启和源记录增删改另行确认。

## 当前基线

2026-11-13 的只读检查确认命名空间为 `datahub-open-test`。22:19再次只读核对了下面两个工作负载的镜像和单副本状态，仍与本表一致：

| 对象 | 当前状态 |
| --- | --- |
| `Deployment/hospital-openmetadata` | 单副本、Recreate；镜像 `harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:f9fac7c3c8230093c501e9730be4486234fd17e1e498b928fc9e36b9f559b518` |
| `StatefulSet/hospital-seatunnel` | 单副本、RollingUpdate；镜像 `harbor.qcrmyy.local/coop/hospital-seatunnel@sha256:477f41ca16167c9fbec07f188235a781aa7bf6ec844de4826c31016b638367c0` |
| 应用持久性 | 应用 Pod 没有数据 PVC，仅挂载既有 JWT Secret；应用 `/tmp` 不能存放升级回退的唯一备份 |
| 引擎持久性 | `/data` 使用既有5Gi数据卷，日志与临时目录为 EmptyDir |
| 元数据库 | `Deployment/hospital-metadata-postgres`，PostgreSQL17.10；PVC `hospital-metadata-postgres-data` 挂在 `/var/lib/postgresql/data` |

19:06 的元数据库只读聚合显示：1 条 FULL 失败任务，0 条启用调度，0 条待初始化任务。仅输出状态和数量，没有读取业务表内容或导出任务 SQL。执行升级前必须重新检查，不能把这次快照当作届时状态。

## 发布内容和范围

- 应用候选：新的服务 JAR 和 PC UI 包，包含已审查的 Oracle 类型、分片、适配器、预检、运行/恢复校验及页面接入。
- 引擎候选：在同一固定3.0.0基础镜像增加已核验的 Oracle CDC 连接器，保留 JDBC/NLS19.32、自定义 Oracle 方言、现有连接器和检查点路径。
- 最终代码为`a0a487a0ae805b3d87609c997def72d594826c57`。下面的本地候选内容已经冻结，完整机器可读信息见[候选清单](oracle-cdc-candidate-manifest.json)。
- 部署差异限定为上述两个容器的镜像。现有 Integrate 身份配置、JWT、Secret、Service、数据库、Doris、网络策略、存储卷和检查点路径保持原配置。已有 HIS 单地址 TCP1521 规则无需再次新增。
- 单副本 Recreate 会带来测试环境短时不可用窗口。先确认引擎无活动/待提交任务，再安排替换；不强制停止未知的业务任务。

| 候选 | 待发布标签 | 已核验本地镜像标识 |
| --- | --- | --- |
| 应用 | `harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v15-oracle-cdc-20261113` | `sha256:f457fc6b80ffb9ae32ef32eca6ec671595dcd1e63eecb1934f3671a6fa1267fe` |
| 引擎 | `harbor.qcrmyy.local/coop/hospital-seatunnel:3.0.0-oracle-cdc-v1` | `sha256:2d9a15cd94ec3e878acfce177c9224a1647199b8dcccb080093683178b46f67c` |

这里的本地Image ID不冒充Harbor的远端manifest摘要。批准后先核对本地候选仍为上述内容，再上传、回拉验证源码标签及归档内容，并记录Harbor返回的实际固定摘要；Kubernetes只使用核实后的`repository@sha256:…`，不以可变标签升级。任何内容、源码或摘要核对失败均停止后续升级。

应用内服务JAR SHA256为`5293a39c9f826e62fec07bfa50bda801cae2623295c1ec233dcd84c99e49c887`，UI JAR SHA256为`3664243b68a8b4b56e9be562783d5bfd06588085f1092817957dff34016874a7`。已在无网络、只读的一次性容器内核对两者，并验证实际UID/GID均为1000、基础层前缀及Env/Entrypoint/Cmd/WorkingDir/端口/卷等继承配置一致。未启动应用。

最终引擎镜像的工厂/类型/NLS加载，以及实际生成配置的原生SQL投影、18位数值、日期秒值、四种行事件和Doris删除序列化均通过一次性无网络检查。检查容器全部退出并移除，未启动引擎服务或连接数据库。这些验证不替代真实CDC运行验收。

最终持久部署差异限定为：`Deployment/hospital-openmetadata`中`openmetadata`容器的image，以及`StatefulSet/hospital-seatunnel`中`seatunnel`容器的image。执行时以JSON Patch的test操作核对对象、容器名、当前旧摘要及副本状态后才替换对应image；远端摘要获得前不生成可直接执行的假摘要补丁。应用临时缩容至0以冻结任务写入，备份核验后恢复至原单副本。

## 必须先处理任务记录的向前兼容

新的可选任务字段包括 `cdc`、`runtime`，运行记录包含检查点样本和配置指纹。当前旧服务的存储反序列化开启未知字段拒绝。新版本写入这些字段后，直接把镜像改回旧版会使旧版无法读取相关任务。

因此准备采用以下受限备份和回退流程，执行动作仍待本提案最终批准：

1. 上传已批准的固定候选后，重新核对资源名称、镜像和副本数。暂停应用实例，等旧应用 Pod 退出，冻结调度和平台写操作。
2. 再次检查引擎活动/待提交任务以及元数据库里的不确定提交状态。存在尚不能解释的运行时状态时停止升级，保留当前部署和记录。
3. 在元数据库 Pod 内，以只读事务导出 **仅 `extension='hospital.integration.task'` 的记录** 到元数据库既有 PVC 上本次升级专属目录。目录使用独立升级标识，权限0700，文件0600，拒绝覆盖既有备份。记录数量和文件摘要；内容不进入 GitHub、浏览器、聊天或本地工作区。无需复制源业务数据或 Secret。
4. 导出内容保留 `id`、`extension`、`jsonschema`、`json`，不整库备份/恢复，不改动身份、连接凭据、元数据资产或医院业务库。后续恢复只能作用于这一扩展名，不能按前缀误处理其他扩展。
5. 应用替换前确认备份确实存在于 PVC、可读取且摘要一致。引擎替换只改变固定镜像，复用 `/data`，完成就绪与连接器加载检查后启动新应用。
6. 新版本就绪后先执行只读状态与预检验证，确认旧任务可读、新字段接口权限正确、单实例运行，再验收 PC 页面。归档日志缺失、LOB 或其他必需检查未通过时，真实 Oracle CDC 继续被阻止。

## 回退流程

回退不能对正在运行的新 Oracle CDC 作业直接切回缺少连接器的旧引擎。先冻结应用写入，核对引擎状态；如有新作业，按已批准的任务操作明确停止并保留保存点与记录。

在元数据库 PVC 上另存回退时的全部集成任务扩展，保留本次升级之后产生的配置、指纹和运行审计。校验数量及摘要后，才允许在单个数据库事务里移出当前的 `hospital.integration.task` 记录并恢复升级前的同名扩展快照。新版本记录保留为受限备份，不静默丢弃；恢复时检查升级标识、扩展名和预期记录数。

仅在任务记录完成隔离、引擎无不兼容活动作业后，恢复上述两个原镜像并等待就绪。原 JWT、Secret、连接记录、Doris 数据、PVC 和检查点保持。若新任务已经开始实际采集，应先判断保存点和目标的恢复需求，再选择继续使用兼容的新服务或执行此回退；不能用元数据旧快照掩盖真实作业。

本提案不授权 DROP/TRUNCATE Doris 目标、不删除 HIS 源记录、不撤回数据库归档和补充日志。数据库变更由 [DBA 源库提案](oracle-cdc-dba-change-proposal.md) 单独处理。

## 验证边界

受控候选全量的6470189行、207项汇总及6484条样本结果见 [候选全量验证](oracle-full-candidate-validation-20261113.md)。该验证使用独立任务存储和新目标，未把新字段写入当前旧版应用。真实 CDC、故障恢复和源记录增删改仍未验收。

本次审批对象为上述两个已冻结的本地镜像、元数据库PVC内的受限任务记录备份，以及核验远端摘要后的两处镜像替换。源码、归档、测试和脚本均已具体化；实际远端摘要和带前置test的执行补丁会在批准上传后记录。本文件是待审批提案，不是已执行的升级记录。

待审批脚本已固化在[发布工具](../../tools/hospital/oracle-cdc-release/README.md)目录的`backup-tasks.sh`和`restore-tasks.sh`，与先前审查版本逐字节相同。两者通过一次性无网络只读容器的`sh -n`语法检查和独立静态安全审查，尚未执行数据库命令，也未声称完成实际恢复演练。调用方必须先核验应用副本为0及引擎空闲；脚本的防误操作环境标记本身不代表审批或完成这些核验。备份与错误文件只保留在元数据库PVC，stdout仅输出状态、数量和摘要。
