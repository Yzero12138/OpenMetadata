# 医院数据集成测试环境运维

本阶段使用独立的 Apache SeaTunnel 3.0.0 和两个 PostgreSQL 17.10 实例。来源与 ODS 中只有合成就诊事件，不连接 HIS、LIS、EMR 或患者数据库。工作台入口为 `/hospital/integration`，沿用 Integrate 工号登录及 OpenMetadata 原生管理员权限。

## 部署

先按仓库现有说明构建服务端、前端 JAR 和医院应用镜像。集成引擎的构建会核对固定 SHA-512，包括发行包、JDBC、PostgreSQL CDC 连接器和 PostgreSQL 驱动，镜像内无需联网下载插件。

```powershell
pwsh -File docker/hospital/seatunnel/build-image.ps1 -Push
pwsh -File deploy/hospital/k8s/integration/create-secrets.ps1
kubectl --context kubernetes-admin@kubernetes apply --dry-run=server -k deploy/hospital/k8s
kubectl --context kubernetes-admin@kubernetes apply -k deploy/hospital/k8s
kubectl --context kubernetes-admin@kubernetes -n datahub-open-test rollout status statefulset/hospital-integration-source
kubectl --context kubernetes-admin@kubernetes -n datahub-open-test rollout status statefulset/hospital-integration-ods
kubectl --context kubernetes-admin@kubernetes -n datahub-open-test rollout status statefulset/hospital-seatunnel
kubectl --context kubernetes-admin@kubernetes -n datahub-open-test rollout status deployment/hospital-openmetadata
```

父级 Kustomization 已包含 `integration`。Secret 创建脚本只适用于 `datahub-open-test`；首次在内存中产生随机密码，后续保留已有密码，不打印值、不写本地明文文件。已有 PVC 的数据库不会重新执行初始化脚本；修改初始化 SQL 后，需要单独、明确地迁移现有合成库。

`hospital-integration-config` 保存开关、内部引擎地址、固定 JDBC 地址及用户名。只有应用容器接收这些配置：`HOSPITAL_SOURCE_JDBC_PASSWORD` 和 `HOSPITAL_TARGET_JDBC_PASSWORD` 分别引用运行 Secret 中的对应密码；迁移容器无需连接业务数据源。浏览器只提交 `synthetic-source`、`synthetic-ods` 逻辑连接标识和经过校验的表、字段信息。

## 工作台操作

1. 从 Integrate 应用门户进入数据治理平台，以已授权的治理管理员身份打开“数据集成”。普通员工显示权限说明，服务端每个控制接口均再次检查管理员权限。
2. 测试来源和目标连接。新建任务，选择 `public.visit_events` 和目标表，选择全量或 CDC，确认来源主键以及字段映射，先验证再保存。
3. 运行后查看真实作业状态和记录计数。作业 ID 使用字符串保留完整精度；记录计数表示处理记录或变更事件数，不代表表中当前行数。不可用的指标不显示为零。
4. CDC 使用“保存检查点并停止”。只有引擎报告 `SAVEPOINT_DONE` 且所有流水线的保存点均为 `COMPLETED` 时，才开放“恢复”。恢复使用原作业 ID 和原配置；暂停期间禁止修改任务或重新发起一个新作业。
5. 使用“登记到目录”登记来源表、目标表、集成流水线和字段血缘。目录登记与数据传输状态分别显示；登记失败可以单独重试，无需再次传输。

全量模式读取当前来源数据，并按主键在目标表新增或更新；不会自动清空目标，也不会移除来源中已删除的历史记录。需要持续同步删除时应使用 CDC。验证器的全量一致性检查使用预先清空的专用合成目标表，不能据此推断非空目标会自动变成完整镜像。

同名任务、重复来源到目标表的链路、过期版本和正在运行的任务会拒绝覆盖。同一来源与目标表使用一个任务，避免原生血缘的流水线归属被另一个任务覆盖。提交超时后，平台保留原作业 ID 并查询引擎；确认状态前不会生成另一个作业。引擎不可达时保留最后已知状态和明确错误，不能据此认定任务已停止。停止请求失败时保留失败原因，并允许重试停止。

任务列表每次请求在三秒引擎查询预算内轮换刷新最多五个作业；较多任务需要后续轮询逐批刷新。结束时间记录平台首次确认终态的时间，不能当作引擎精确结束时间。

## 合成链路验证

```powershell
pwsh -File tools/hospital-integration/verify-engine.ps1 -ManagePortForward -VerifyRestart -VerifyFieldMapping
```

验证器只允许指定测试命名空间和本机回环地址，临时端口转发结束后自动清理。它会重置专用合成表，因此会先拒绝存在运行中或等待中作业的环境。验证包含 100 行全量、CDC 初始快照、插入 20 行/修改 10 行/删除 5 行后的 115 行逐字段一致性、保存点恢复、实际更换引擎 Pod 后恢复，以及包括主键在内的四字段重命名。执行结果写入忽略提交的 `.logs/integration-engine-verification.json`；失败退出码非零。

本服务按用户要求仅面向 PC，浏览器验收尺寸为 1440 与 1920。本地 UI 验证另使用显式合成 HTTP 边界，只证明真实 React 组件的表单、状态、权限展示及桌面布局，不证明员工登录、数据库抽取或真实目录写入。

## 运行和排障

SeaTunnel REST 仅为 ClusterIP；网络策略只允许医院应用访问 8080，只有引擎节点互访 5801。引擎只可连接两个专用数据库和集群 DNS。数据库仅允许引擎及医院应用访问 5432。原有 Integrate 后端网络策略保持独立。

引擎 `/data/checkpoints` 与 `/data/imap` 挂在同一专用 PVC，分别保存检查点和作业状态。启动脚本用 `exec` 使 JVM 接收终止信号。重启可能自动恢复运行，也可能停留在已经完成的保存点；应先刷新状态，有确切保存点后再恢复，不能重复新建作业。

本阶段为单引擎节点和单应用副本，IMap 备份数为零，PVC 使用测试集群的 `local-path`。这验证持久化和受控恢复，不构成跨节点高可用。不要直接扩容应用副本：当前任务并发保护以单进程锁和版本检查为基础。

源库账户为非超级用户，具有逻辑复制及在专用合成库创建发布的权限；目标账户为非超级用户且拥有目标表。逻辑复制槽按任务 UUID 固定命名。暂停 CDC 时复制槽保留，源库设置 `max_slot_wal_keep_size=1GB`；长时间停用后，应检查槽和 WAL 保留状态，再决定恢复或清理已废弃任务的槽。不要清理仍需恢复的槽或保存点。

凭据只由 Kubernetes Secret 注入运行进程。引擎运行状态包含作业连接配置，需按凭据资产保护引擎 PVC 和管理员访问；不要公开原始 `/job-info` 或复制原始作业配置到工单。平台接口只返回经过筛选的状态与通用错误码。

Integrate 后续发布必须保留 `datahub` 的一次性票据控制器、精确 POST Basic 认证边界及门户握手。若票据交换返回 503，先从应用 Pod 核对真实内网响应；通用 Bearer 过滤器的 401 不等同于无效票据，不应在治理平台把它改成成功或放宽登录校验。本次配套恢复源保存在 `F:/datacenter/Integrate-sso` 的 `codex/datahub-sso-test-20261009` 分支，源提交 `60940c2f`；尚未推送到 Integrate 的 Gitee 仓库。后续构建须纳入该提交，详见[验证记录](data-integration-verification.md)。

## 回退

回退应用镜像至此前已验证的 `2.0.4-integrate-v4`，同时回退 `migrate` 和 `openmetadata` 两个容器的镜像。旧镜像不读取集成配置，原有元数据数据库、治理界面及 Integrate 登录保持原来的版本。

如暂时停用引擎，先通过工作台为正在运行的 CDC 保存检查点并确认完成，再将 `hospital-seatunnel` 缩容为零；合成来源和 ODS 可随后缩容为零。默认保留三个 StatefulSet 的 PVC、运行 Secret 及任务定义，以便恢复。不要删除原有元数据数据库 PVC，也不要对整个测试命名空间执行删除。

如果仅关闭新入口的服务端能力，将 `HOSPITAL_INTEGRATION_ENABLED` 改为 `false` 并重启医院应用即可；这不会自动停止已有引擎作业，应先处理作业。凭据轮换需要同时更新数据库账户、Secret 和运行中的作业配置，不能只重新运行 Secret 创建脚本。
