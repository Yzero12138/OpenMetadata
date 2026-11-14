# Doris 目标表设计器测试发布

2026-11-13。应用命名空间 `datahub-open-test`。入口通过 [Integrate 测试门户](https://integrate-dev.qcrmyy.local/s/portal) 的“数据中台”，应用地址为 `http://172.16.120.211:30925`。新功能位于 **数据集成 → 目标表管理**，操作说明见 [使用指南](doris-table-designer.md)。

## 版本与部署

- 源码提交：`f67b36bd9b047a53de0238fea8e80724cb7ce7ff`。
- Harbor 标签：`harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v14-doris-designer-20261113`。
- 实际镜像：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:f9fac7c3c8230093c501e9730be4486234fd17e1e498b928fc9e36b9f559b518`。
- 回退镜像：v13，摘要 `sha256:a7fbf27e96283e9f7dc3cd81d0a58f0a18e45240b4e81ac0450747e091e6da16`。
- 就绪 Pod：`hospital-openmetadata-67d9c4994d-tl4kx`，UID `696b140e-d238-4d4f-b2a3-57a2c597ec3b`。

从已提交源码正式构建服务和 UI。Spotless check 通过；Vite 构建 4 分 35 秒，UI 资源归档成功，逐字节核对 index、目标表页面、共享设计器 JS/CSS 均进入归档。使用无 Docker 守护进程的附加层发布方式，只替换两份归档，保留 v13 的基础层、环境、入口和运行配置。

| 归档 | Pod 内 SHA-256 |
| --- | --- |
| `openmetadata-service-2.0.4.jar` | `6622f4fd41172ff584a9937baa1b3c8c9c3657a36a515f71c9f7c4a65401a153` |
| `openmetadata-ui-2.0.4.jar` | `98b4afd50ec5d76d34b073d9b41b4699c8832edf4253f04b9347db877f5bf6e0` |
| 保留的 `orai18n-19.32.0.0.jar` | `b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56` |

部署前确认引擎无活动或排队作业，再以 UID、resourceVersion 和旧镜像三重断言只修改应用容器镜像。8 个工作负载保留原 UID 和其余 spec，40 个 ConfigMap、Secret、PVC 的 UID、resourceVersion 及卷引用保持不变。迁移镜像 `934be973…`、SeaTunnel `477f41ca…`、Doris FE/BE、调度持久化与检查点均保留。

Doris 只对已有账号 `hospital_ods_writer` 增加隔离库 `internal.ods_validation.*` 的 ALTER_PRIV，仍无 DROP/ADMIN。未重建运行中的哈希 ConfigMap 或重启 FE。详见 [验证记录](doris-table-designer-verification.md)。

## 发布服务原生验收

在上述就绪 Pod 中运行仅包含验收帮助类的 JAR，生产类只从 `/opt/openmetadata/libs/*` 加载；没有复制、优先加载或覆盖候选服务归档。5 个阶段逐一核对 11 个生产类来源及实际 service JAR 摘要，全部匹配本次发布版本。

独立结构化 CREATE、原生类型/默认值/注释、同名拒绝、ADD COLUMN、活动任务保护、结构指纹过期、重新预览、重复字段拒绝均通过。追加字段后原数据、主键和旧映射保留。

实际 SQL T+1 任务使用业务日 `2026-11-12` 手动执行两次：

| SeaTunnel 作业 | 状态 | 读取 / 写入 |
| --- | --- | --- |
| `1498600023632253925` | FINISHED | 2 / 2 |
| `5363330409359536199` | FINISHED | 3 / 3 |

第二次将 id=1 金额更新到 99.75，保留 id=2 的 20.25，新增 id=3 的 21.25；最终恰有三行。中文、BOOLEAN、STRING、毫秒时间、字面字符串 NULL、引号/反斜杠默认值及新增列取值全部匹配。

临时任务 `fcaf0c93-1ed4-4dce-94b8-687596ad697e` 及自有源表在全部作业终态后清理。目标 `ods_validation.sql_design_api_e0d3fa79a800` 保留为证据，未申请 DROP 权限。引擎最终无活动或排队任务；失败作业数量 1 是此前故障恢复验收的既有记录，本轮两个新作业均成功。

这是实际运行归档、JDBC 与 SeaTunnel 的原生 API 方法验收，不声称模拟了员工 HTTP 登录或真实院内患者数据，也不作为新的 Quartz 时钟验收。页面及单元验证边界见 [界面扩展记录](doris-table-designer-design-extension.md)。

## 登录通道核验

发布核验发现测试门户当前平台包遗漏此前的 SSO 组件，通用 JWT 过滤器先于 Basic 票据接口返回401，数据中台因此返回503；当前门户启动脚本也遗漏了票据握手。应用到平台的原有网络策略和 TCP 8081 通道正常。恢复以当前运行版本为基线，保留现有病历入口和 JPA 接口，不回退整个门户版本。

- 平台只覆盖 `/app/app.jar`，镜像为 `harbor.qcrmyy.local/coop/platform@sha256:cec5694159972e9209f687939f0c24f6d420f90aa6fc0ed6fcd4bfd44ff93997`。最终归档 SHA-256 为 `152707e719e4fcced705d650c7a3a7bc90d9aff0c37ac83ad819349daaa5fdab`。仅5个主类替换、11个类新增，其余1159个条目及165个依赖保留原内容。基线6类逐字节一致，最终打包类测试60/60及独立质量补充5/5通过。
- 门户只替换现有 PortalPage 的启动函数并新增 SSO helper，镜像为 `harbor.qcrmyy.local/coop/web@sha256:fdcaae0e3d19033370bb120b6a5719cf16df3aaf2825536d73d1a598c8e82824`。普通应用立即断开 opener，病历内置路由、驾驶舱、链接及代填分支保留；Datahub 使用严格来源、窗口和 challenge 绑定，只传单次票据，不向子窗口转发门户 JWT。正式35项和独立2项客户端合约通过，SPEC/质量均批准。
- 两次门户部署均检查当前镜像、UID 和 resourceVersion。`coop-dev` 共18个工作负载只改变 platform/web 镜像，55个 ConfigMap、Secret、PVC 引用保持不变；设备门禁、网络和其他服务配置未修改。
- 实际服务的 PortalPage SHA-256 为 `b7327d4272447ca960f4a84a70f0b97306e653b7de8c578149a4e2725680a0de`，helper 为 `4683a33bec833d018ca012ecc512223be94d4517e58f6974ab6277b0c54c42f6`。原入口脚本逐字节未变，旧 PortalPage ETag 请求返回200及新内容，两个文档仍无破坏 opener 的 COOP 响应头。

恢复后数据中台 **21项HTTP/鉴权检查、141项生产资源摘要检查通过**。不存在票据正确返回401；五个新增设计器接口对匿名及畸形未认证请求均先返回401。浏览器真实员工登录或桌面客户端完整跳转未在本轮执行；已启动握手的取消/失效由隔离客户端合约建立，前置旧 launch 请求的浏览器生命周期未扩展验证。

请先完整刷新 Integrate 门户，再点击“数据中台”。现有 SPA 的模块实例需要重新加载；当前资源没有 immutable 缓存，mtime/size ETag 已变化。

本次没有修改其他 Integrate 工作树。私有恢复源码和补丁持久保存在本地 `F:/datacenter/_scratch/integrate-sso-restoration-20261113/`，未上传到公开 OpenMetadata fork。后续从 Integrate 源码重建前，应将等价适配合入其现行分支并复验，避免再次遗漏 SSO；该目录 README 说明实际基线和应用边界。
