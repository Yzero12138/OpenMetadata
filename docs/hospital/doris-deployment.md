# Doris TEST 部署

该目录为 `datahub-open-test` 提供独立的 1 FE + 1 BE，只承载合成数据验证库 `ods_validation`。清单与现有应用的 Kustomization 独立，支持先启动 FE，再启动 BE 并执行 bootstrap。

## 版本与部署方式

2026-10-20 核对[官方下载页](https://doris.apache.org/download/)：`4.0.8` 为 Stable，`4.1.5` 为 Latest，维护分支为 4.0/4.1。本次选择 Stable 4.0.8，FE/BE 同版，按现有 amd64 节点固定平台摘要。

| 组件 | 官方标签 | 固定的 linux/amd64 manifest 摘要 | 原多架构 index 摘要 |
| --- | --- | --- | --- |
| FE | `apache/doris:fe-4.0.8` | `sha256:0994b0e017ca024116e9c721b74fbc7293b1edc18ada988cdb1abdfed60cfa2f` | `sha256:390413325a93d9121768bda153ccaa3c7a8da4aa0fd2a466406cddc14a6399b2` |
| BE | `apache/doris:be-4.0.8` | `sha256:b56c70cbe32d4ccdea4a166ddf0ed55a38e82ce4eac5a8aceb80480c946ebf76` | `sha256:d911ae3826491598be4e383cba83011a266091b8fdc6c34c036280220cc4fea9` |

摘要来自 `docker buildx imagetools inspect apache/doris:<tag>`；原镜像来源为 [Apache Doris Docker Hub](https://hub.docker.com/r/apache/doris)。已镜像到 `harbor.qcrmyy.local/coop/hospital-doris-fe:4.0.8` 和 `hospital-doris-be:4.0.8`，独立远端核验的摘要分别与上表官方 amd64 manifest 完全相同。清单固定 Harbor 平台摘要，并使用命名空间现有的 `harbor-cred`。

FE 使用 Docker Buildx registry copy 复制原始 manifest。BE 以官方 registry 的原始 manifest/config/layer 构建 OCI archive，每个 blob 都核对原始 SHA-256 后以标准 `docker load` 导入，再推送原 manifest。两组件的文件系统均未修改；用于诊断本地 content-store 的仅 LABEL 镜像没有发布。公开来源记录和临时下载工具位于忽略目录 `.cache/doris/`，Secret 则单独保存在受限 ACL 的子目录。

官方 Kubernetes 指南以 [Doris Operator](https://doris.apache.org/docs/4.x/install/deploy-on-kubernetes/intro/) 为管理方式。本次 TEST 采用手工启动方式的两个原生 StatefulSet，避免新增集群 CRD、Operator 及其全局权限。单 FE/BE 不提供高可用；升级、节点扩容、备份和故障恢复均需人工协调。

## 资源和必要条件

| 组件 | request | limit | Java heap | 专用 PVC |
| --- | --- | --- | --- | --- |
| FE | 1 CPU / 2Gi | 2 CPU / 4Gi | 最大 2Gi | 5Gi，元数据 |
| BE | 2 CPU / 4Gi | 4 CPU / 8Gi | JNI 最大 512Mi；BE `mem_limit=6G` | 20Gi，数据及辅助文件 |

两个 Pod 必须调度到 `k8s-worker01`，同时要求 `amd64`。这些资源低于[官方通用开发测试硬件建议](https://doris.apache.org/docs/4.x/install/preparation/env-checking/)，仅用于少量合成记录、目标建表和字段映射验收；不能据此评估生产容量。BE 的线程数也按少量并发收缩。

2026-10-20 从现有 TEST Pod 读取的初始预检结果：worker01/02/03 均支持 AVX2，`vm.max_map_count=65530`，soft nofile 为 1024；worker01/02 的 hard nofile 为 524288，且 `/proc/swaps` 无启用项。随后按[节点准备记录](doris-node-prerequisite.md)仅将 worker01 的 map count 调整到 2000000，并移除一次性 helper；其他 worker 保持原值。worker01 根盘可用约 189GiB，满足本次合计 25Gi 测试卷准备。

[官方 BE 启动脚本](https://github.com/apache/doris/blob/branch-4.0/bin/start_be.sh) 要求 `vm.max_map_count >= 2000000`、nofile 至少 60000、禁用 swap。`start.sh` 在非 root 容器内将 soft nofile 提高到 65535，并显式保留 `SKIP_CHECK_ULIMIT=false`。`prepare.sh` 对 map count 再做一次提前检查。清单不会修改宿主机，也不会跳过官方检查；将来扩大 affinity 范围前必须对新节点重新预检。

部署前仍需核实 Pod 拉取固定镜像的连通性，以及 `local-path` 新卷可供 uid/gid 1000 写入。PVC 请求只表达申请容量，不能假设 local-path 为目录提供磁盘配额。`local-path` 为 `WaitForFirstConsumer` 且回收策略为 `Delete`；显式保留 PVC 不等于删除 PVC 后还能恢复文件。

## 启动和身份

所有容器以 uid/gid 1000 运行，禁用权限提升、移除 capabilities、启用 RuntimeDefault seccomp 和只读根文件系统，不挂载 service-account token。初始化容器将官方 `conf` 复制到可写 `emptyDir`，保留 JDK 17 所需的官方 Java 参数，仅调整 heap 并追加配置。原始 `LOG_DIR` 先被改为可写日志目录，再读取 JVM 参数，防止 GC 日志提前捕获镜像内的只读路径。日志、PID、临时文件以及持久数据均使用独立可写目录，不使用 root init 容器。入口将 TERM 转发给官方 console launcher 的实际 JVM/BE 子进程并等待退出。

入口直接调用官方 `start_fe.sh --console` / `start_be.sh --console`，不使用镜像默认的自动注册入口。FE 首次对外监听前，初始化过程已经从 Secret 加载二阶段 SHA-1 格式的 `initial_root_password`，不依赖先空口令启动再修改口令。镜像实际默认 `skip_localhost_auth_check=true`，清单显式设置为 `false`，使 localhost bootstrap 也执行真实口令认证。该配置定义见[官方 FE 配置源码](https://github.com/apache/doris/blob/branch-4.0/fe/fe-common/src/main/java/org/apache/doris/common/Config.java)。保留 FE PVC 时必须保留匹配的 bootstrap Secret，不能重新生成密码后假设原元数据会自动更改。

凭据分为两个独立 Secret：

- `hospital-doris-bootstrap`：只用于初始化和管理的 root 口令、初始密码 hash 与本地 MySQL client 配置。应用和引擎不引用该 Secret。
- `hospital-doris-writer`：`hospital_ods_writer` 及独立随机口令，只授予 `internal.ods_validation.*` 的 `SELECT_PRIV, LOAD_PRIV, CREATE_PRIV`。CREATE 用于页面目标建表；不授予 DROP、ALTER、GRANT、NODE 或全局管理权限。权限含义见[官方授权说明](https://doris.apache.org/docs/4.x/admin-manual/auth/authorization/internal/)。

`initialize-secrets.ps1` 用系统密码学随机数生成两个 256-bit 口令，只写到忽略目录 `.cache/doris/secrets/`，移除继承 ACL 并限制到当前 Windows 身份；不会输出口令，也不会应用到 Kubernetes。脚本拒绝覆盖现有文件，不能作为轮换密码的工具。`bootstrap.sh` 使用挂载的 client 配置认证，不在命令参数中传递口令，也不会打印失败 SQL 或口令 hash。

## 网络和客户端

| 用途 | 集群内地址 | 端口 |
| --- | --- | --- |
| SQL / JDBC / 建表 | `hospital-doris-fe.datahub-open-test.svc.cluster.local` | 9030 |
| FE Stream Load 入口 | 同上 | 8030 |
| BE HTTP / Stream Load | `hospital-doris-be.datahub-open-test.svc.cluster.local` | 8040 |

只有 ClusterIP 和 headless Service，无 NodePort、LoadBalancer 或 Ingress。FE 在新集群启用 [FQDN 模式](https://doris.apache.org/docs/4.x/admin-manual/cluster-management/fqdn/)，BE 注册为 `hospital-doris-be-0.hospital-doris-be-headless.datahub-open-test.svc.cluster.local:9050`，不把易变化的 Pod IP 写入元数据。

新的 FE/BE NetworkPolicy 只允许同命名空间 TEST 应用、SeaTunnel、Doris 内部必要端口，以及集群 DNS。额外的 `hospital-seatunnel-to-doris` 和 `hospital-openmetadata-to-doris` 只补充到 Doris 标签的 8030/9030/8040 egress，不覆盖原有 policy。FE 返回 BE 的 Stream Load 重定向，因此引擎必须能访问稳定 BE Pod FQDN 的 8040，只有 FE 8030 的连通性不够。

[SeaTunnel 3.0.0 Doris sink](https://seatunnel.apache.org/docs/3.0.0/connectors/sink/Doris/) 支持 Doris 1.1 及以上的 exactly-once/CDC，使用批量 Stream Load；4.0.8 满足其声明的服务端版本范围，仍需通过本仓库的合成任务验证。Zeta 运行还需要 Doris connector JAR 和 MySQL JDBC driver。目标参数应为 `fenodes=...:8030`、`query-port=9030`、`database=ods_validation` 和 scoped writer。单 BE 的默认副本策略由 bootstrap 建库时设置为 `tag.location.default:1`；目标 DDL 同样应显式指定 `replication_num=1`，避免连接器模板默认生成三个副本。建库属性见[官方 CREATE DATABASE](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/database/CREATE-DATABASE/)。

## 执行顺序

在仓库根目录执行；不要把 Secret 文件或 `kubectl get secret -o yaml` 输出贴入日志。先确认同名 Secret 不存在；已有 Secret/PVC 时应复用原凭据并调查现有状态。

```powershell
powershell -NoProfile -File deploy/hospital/k8s/doris/initialize-secrets.ps1
kubectl apply --dry-run=client -k deploy/hospital/k8s/doris
kubectl apply --dry-run=server -k deploy/hospital/k8s/doris
```

确认节点前置条件通过后，执行：

```powershell
kubectl apply -f .cache/doris/secrets/
kubectl apply -k deploy/hospital/k8s/doris
kubectl -n datahub-open-test rollout status statefulset/hospital-doris-fe --timeout=600s
kubectl -n datahub-open-test rollout status statefulset/hospital-doris-be --timeout=600s
kubectl -n datahub-open-test exec hospital-doris-fe-0 -c fe -- bash /config/bootstrap.sh
```

bootstrap 幂等注册 BE、创建 `ods_validation` 与 scoped writer，确认 writer 可登录并等待 BE `Alive=true`。BE 尚未注册时，Doris 的 `SELECT 1` 和系统 TVF 也可能要求可用 BE；因此初始化阶段使用 FE 的 `SHOW DATABASES`、`SHOW TABLES` 和 `SHOW BACKENDS` 验证状态。它不会读取业务源或装载数据。重复运行不会重置已有用户密码；若 Secret 与已有用户不匹配，会失败并要求调查。

随后以 scoped writer 完成合成表建表、Stream Load/SeaTunnel 写入和 SQL 行数对账；确认错误口令与无权限库访问失败。应用、引擎的凭据注入及其镜像更新需单独处理。

## 回退和数据保留

停止 Doris 的最小回退操作为：

```powershell
kubectl -n datahub-open-test scale statefulset/hospital-doris-be statefulset/hospital-doris-fe --replicas=0
```

两个 StatefulSet 显式配置 `persistentVolumeClaimRetentionPolicy` 的 `whenDeleted=Retain`、`whenScaled=Retain`。必须保留 `data-hospital-doris-fe-0`、`data-hospital-doris-be-0` 及匹配的 bootstrap/writer Secret。不要删除 namespace、PVC 或底层 local-path 目录。worker01 丢失时不能靠调度到其他节点恢复本地卷；变更副本数或 affinity 前先制定数据迁移方案。

日志使用有限大小的 `emptyDir`，Pod 重建会清空；需要故障留证时先收集脱敏日志。清单的本地静态校验只能证明 API 结构合法，最终可用性以真实 Pod 健康、BE Alive 和合成端到端对账为准。

## 验证记录

已通过全部 Bash 脚本语法检查、Secret 生成脚本 PowerShell AST/真实生成、二阶段密码 hash 匹配、受限 ACL 和 Git 忽略检查。11 个 Doris 资源及两个独立 Secret 均通过 Kubernetes server dry-run；准备脚本本身不执行实际 apply。

官方 FE 镜像在本地隔离 Docker 网络、uid 1000、只读根文件系统、2 CPU / 4Gi 下完成实际启动；`/api/health` 返回 200。显式关闭 localhost 免认证后，正确 bootstrap 口令可访问 FE 元数据；在 `--no-defaults` 并清除 `MYSQL_PWD` 的探针中，空口令和错误口令均被拒绝。scoped writer 的建用户/授权 SQL 与登录通过，尝试创建其他数据库被拒绝；信号转发后停机约 0.9 秒，未发生 OOM。临时 FE 容器及其专用 Docker 网络已经移除。

BE 已验证完整镜像 blob 和 config 的 SHA-256，导入后 RootFS DiffIDs 与原 config 完全一致。在 uid 1000、只读根文件系统、无网络和 `cap-drop ALL` 下，官方 `doris_be --version` 执行成功，conf、启动脚本和二进制的模式均为 755；版本为 `doris-4.0.8-rc02(AVX2)`，commit 为 `bc8ea1bac6d62d92cdebd2e3ac33cc668961ec36`。本地 Docker 的 map count 只有 262144，验证了 `prepare.sh` 对该低值拒绝启动的行为。

已创建 FE 的只读状态复核显示 `Alive=true`，Host 为 `hospital-doris-fe-0.hospital-doris-fe-headless.datahub-open-test.svc.cluster.local`，确认 Kubernetes 中实际使用稳定 FQDN。

2026-10-20 实际应用 11 个资源后，只读复核显示 FE/BE Pod 均位于 `k8s-worker01`，状态为 `Running`、`Ready=true`，没有 waiting/init waiting 原因。BE 已在满足 map count 的节点上通过非 root、只读根文件系统的实际启动。完整验收还包括 bootstrap 的 BE 成员注册、SQL `Alive=true` 及应用/SeaTunnel 的合成写入对账。
