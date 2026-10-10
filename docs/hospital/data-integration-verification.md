# 医院数据集成验证记录

日期：2026-10-09。范围：已批准的独立 SeaTunnel、中文任务工作台和原生目录联动，首条链路仅使用隔离合成数据。

## 已完成的真实引擎验证

固定 Apache SeaTunnel 3.0.0，提交 `5056e3e`。引擎镜像摘要为 `sha256:70e3b4cfa52f90783262539d9c08c5c2cc5458784c30561204a6e74172092c16`。实际 JVM 为 Temurin 17.0.20.1；连接器为 JDBC 3.0.0、PostgreSQL CDC 3.0.0，驱动为 PostgreSQL 42.7.14。来源和 ODS 均为 PostgreSQL 17.10，使用独立实例、PVC 和非超级用户。

执行 `tools/hospital-integration/verify-engine.ps1 -ManagePortForward -VerifyRestart`，2026-10-09 08:51–08:52 UTC，八项检查通过，退出码 0：

| 检查 | 实际结果 |
|---|---|
| 引擎版本 | 3.0.0，1 worker |
| 全量同步 | 100 行，所有所选字段按主键比较一致，`FINISHED` |
| CDC 快照 | 100 行，一致 |
| CDC 增删改 | +20、更新10、删除5，最终115行，一致 |
| 保存点 | `SAVEPOINT_DONE`，所有流水线保存点 `COMPLETED` |
| 原作业恢复 | 同一字符串作业 ID；暂停后新变更被同步，115行一致 |
| 实际 Pod 重启 | Ready Pod UID 改变；全量历史保留，CDC 自动运行，新变更同步，115行一致 |
| 收尾 | 验证作业保存检查点后停止 |

另执行 `-VerifyFieldMapping`，08:53–08:54 UTC，九项检查通过，退出码 0。四个字段均重命名，包括 `visit_id → event_id`；重命名后初始快照和新增、更新、删除均通过逐字段一致性比较。验证目标为专用 `visit_events_mapped` 表。

证据保存在忽略提交的 `.logs/integration-engine-verification.json` 与 `.logs/integration-engine-mapping-verification.json`。上述结果来自真实 SeaTunnel 和 PostgreSQL，未使用模拟引擎响应。单节点持久化验证不等同于跨节点高可用，也不证明真实 HIS/LIS 连接器适配或 exactly-once。

全量检查开始时专用目标表为空。当前全量写入策略为主键 upsert，不自动删除目标历史记录；CDC 检查才包含连续删除同步。非空目标的完整镜像替换不在本轮证明范围内。

## 发行包实际协议差异

3.0.0 单作业提交实际从查询参数读取 `jobId`、`jobName` 和 `isStartWithSavePoint`，正文只携带作业配置；将参数放在正文的 `params` 中会被忽略并生成另一作业 ID。服务端实现和验证脚本均以固定版本源码及真实请求结果为依据。

不存在的作业查询实际返回 HTTP 200、仅包含请求的 `jobId`，不能以 HTTP 200 或 ID 相等判定存在。实际计数键为 `SourceReceivedCount` 和 `SinkWriteCount`；停止保存点成功状态为 `SAVEPOINT_DONE`。平台对缺失状态、未知状态、不可达引擎均保守处理。

## 应用与界面验收

用户明确整个服务仅面向 PC。最终本地浏览器批次在桌面 1440×1000、1920×1080 各验收八个状态，共 16 张截图：空任务、配置、名称冲突、传输完成但目录失败、停止失败重试、保存点暂停、普通员工权限说明和引擎不可达。无页面脚本异常或文档水平溢出；实际编译样式检查确认作业 ID 跨整行、运行事实保留两列、语义背景色有效。

界面使用真实 React 组件和显式合成 HTTP 边界。创建冲突保留输入，作业 ID 保留字符串精度，缺失计数显示不可用，停止失败可重试，暂停时仅允许已确认保存点的恢复。它与真实引擎验证分别记录；不将 HTTP 模拟边界作为员工登录、数据库抽取或真实目录写入的证据。

四个 Jest 套件共 44 项通过；新增界面通过 ESLint 和所有 20 种语言的键同步检查。全仓 TypeScript 诊断仍有既有 550 项，当前与基线一致，新增 0 项；没有声称全仓类型检查通过。

独立代码审查提出的四项问题已经修复并复核：失败停止保留错误并可重试、任务列表按预算轮换刷新、拒绝重复来源到目标链路、首次观察终态时记录并保留结束时间。独立 Impeccable 收尾审查发现的 LESS 除法与语义令牌问题也已修复，替换同一批 PC 证据后结论为 `ship`；检测器只执行一轮。

应用镜像、部署资源和认证边界的实际结果记录于下文。

## 原生服务与目录验证

Java 21 完整编译覆盖 1,893 个主源码文件；最终八个专用测试类共 34 项通过，失败、错误和跳过均为 0。20 个新增 Java 文件经过 scoped Spotless apply/check。最终服务 JAR 为 10,430,689 字节，SHA-256 为 `5329c047af63d853c28b9187753c52c81cfb3bfa41d782da7411ea1fee2068fc`。

真实目录测试使用独立空 PostgreSQL 数据库和真实 OpenSearch，调用 `IntegrationService.syncCatalog` 两次。实际创建并检查两个数据库服务、数据库、Schema 和表，一个流水线服务和流水线；重复后实体数量与 UUID 保持稳定。检查原生 `UPSTREAM` 关系中真实流水线 UUID 和两条字段映射、服务 JSON 不含连接凭据、任务持久化状态为 `SYNCED`。没有写入现有医院元数据库，也没有创建员工会话。

测试通过显式 `HOSPITAL_INTEGRATION_TEST_CATALOG_SCHEMA_PATH` 加载匹配版本的已迁移纯结构基线，不隐式依赖 `.logs`；本次本地输入来自测试元数据库的只读 schema-only 导出，不含记录、角色或权限定义，未提交此文件。两个临时测试数据库/搜索容器均已清理。可复核输出为 `openmetadata-service/target/hospital-integration-tests-package.log`；完整源码编译证据在 `hospital-integration-full-compile.log`，该初次执行中的旧夹具失败已由最终 34 项成功执行解决。

## 真实网络边界

执行 `tools/hospital-integration/verify-network.ps1`，2026-10-09 09:10 UTC，四项通过：引擎 REST 只有 ClusterIP、应用可访问真实 3.0.0 引擎、应用可访问两侧数据库、无关标签的临时 Pod 无法访问引擎与两侧数据库。临时验证 Pod 已清理。证据为 `.logs/integration-network-verification.json`；未扩大既有 Integrate 网络策略。

## 应用发行与测试部署

最终 UI 生产构建与 Maven JAR 打包通过。UI JAR 为 50,595,811 字节，SHA-256 为 `c32f4108ec3567e99b77aae1fd4283b0379d9cdf2a6bcc1893f943bbe7ea1ed28`。包内 16 个选定 UI 文件与最终 `dist` 逐字节一致，31 个新增服务 class 文件与最终编译产物一致。

应用版本为 `2.0.4-integrate-v5`，摘要 `sha256:aac72da620e0ab54cc428319a4bf94dec7afece72eb62bea44093e03ebc1d1c0`。应用与迁移容器使用同一固定镜像；服务端 dry-run、实际 apply 和 rollout 成功，迁移退出码为 0。测试命名空间中的应用、原有元数据库与搜索、独立引擎与两个合成数据库共六个 Pod 均为 Running/Ready。启动日志注册了全部 12 个新增原生集成接口。

| 验证 | 结果与证据 |
|---|---|
| 新增接口认证边界 | 13 项通过；未认证、伪造管理员头及无效 Bearer 均被拒绝，响应无 JDBC/密码泄露。`.logs/integration-api-boundary.json` |
| 部署前端资源 | 16 项通过；所有入口 chunk、集成页、工作流页与编辑器资源 SHA-256 一致，首页经原生路径、nonce 和换行转换后模板精确一致。`.logs/integration-deployed-assets.json` |
| 身份保留 | 7 项只读检查通过；原人员 UUID、稳定用户名、管理员权限、角色、团队及关系保留。`.logs/integration-identity-preservation.log` |
| 门户与登录协议 | 17 项通过；包括真实 Integrate 回程拒绝不存在的票据、Origin 校验、原生人员登录入口关闭及两个 PC 尺寸的入口页面。`.logs/live-deployment-checks.json` |
| 运行状态 | 镜像、init 迁移状态、就绪状态、配置引用和实际注册路径已记录。`.logs/integration-deployment-snapshot.json` |

实际员工通过门户创建、运行及登记新任务的完整操作，仍需授权设备上的人员会话验收；本轮没有伪造管理员 JWT、创建管理员机器人或读取员工会话来替代这一验收。

## 配套 Integrate 回归恢复

最终回归发现，测试环境 Integrate 在后续 HR/MyBatis 发布后缺少数据中台 SSO 控制器，Basic 回程请求被通用 Bearer 过滤器返回 401，治理平台保守返回 503。网络和共享 Secret 配置没有因此放宽。当时在保留静态资源中检索到握手代码，误判为活动门户已包含 SSO；2026-10-10 的实际入口和浏览器回归确认门户 web 也需补充发布，见下文。

在精确现网源码基线 `75941efefa7acd669304e2a324a6395bbae0f88d` 上建立独立分支，补回已验证的 SSO 实现。原认证与门户基线 36 项通过；修复后七个 Java 测试类 56 项、两个门户 Vitest 文件 26 项通过，独立只读代码复核无重要问题。候选 JAR 对比仅六个既有 SSO 相关 class 与 Maven 属性改变，新增 11 个 SSO class，无删除项；所有依赖库、数据库迁移、其他资源及无关业务 class 均与现网 SHA-256 一致。

配套源提交为 `60940c2fc1d064e36fb4c382bf5f9339d3cc660b`，保存在 `F:/datacenter/Integrate-sso` 的 `codex/datahub-sso-test-20261009` 本地分支，未推送到另一个 Gitee 仓库。部署仅替换 `coop-dev/platform` 的容器镜像，包含原镜像及 resourceVersion 检查，未覆盖并发发布或修改其配置、Secret、卷、数据库、副本和设备入口策略。

配套镜像摘要为 `sha256:79bc2fc9e71fc1e38afdf25a1994db6191e6c7604fc6ea83af2bb68f3e0db584`。实际 Pod Ready、重启次数 0；运行 JAR 的 SHA-256 为 `52db40c7b0122bda4c8250e67e280d72130f47703b78c6f7e99491af8c546824`，与候选一致。随后真实内网交换对虚构票据返回 `200 {"active":false}`，治理平台返回预期的 401，全部 17 项门户回归于 10:12 UTC 通过。构建、比较、受保护发布及回退记录保存在忽略提交的 `.logs/integrate-sso-*` 中。

后续 Integrate 测试发布必须包含上述 SSO 源提交，避免再次遗漏配套协议；相关源码及发布说明不混入 OpenMetadata fork。

## 门户活动前端补充验证

门户打开治理平台后若停留在 `?integrate_connect=1`，提示需要从门户进入，应检查活动前端是否在导航前断开了 `opener`。服务端票据接口正常，仍不能证明门户已运行配套握手。静态目录为兼容已有会话保留的孤立旧文件，会使全目录字符串搜索产生误判；必须从实际 HTML 入口追踪当前门户模块并验证浏览器行为。

补充回归使用 1440、1920 Chromium 点击真实门户组件。旧前端可重现未发起授权或交换请求、治理页提示缺少门户连接；补齐门户 SSO 后，两种宽度均完成 challenge/code/complete 并断开 opener。实际治理前端提交不存在的合成票据均被服务端返回 401。门户 HTTP 授权边界为显式隔离数据，不等同于真实员工登录成功。

门户单元回归 26 项、完整 Vue TypeScript 检查、Vite 生产构建通过；补充浏览器检查 9/9 与原有登录安全回归 17/17 通过。独立只读复核未发现重要问题。配套门户前端发布应同时保留旧静态资源、下载文件与设备许可入口，不能只发布服务端协议。

后续前端发布须执行活动入口和浏览器握手检查；真实员工在授权设备上的正向登录、退出及撤权验收仍待完成。院内部署及回退明细单独保存在本地配套发布记录中。
