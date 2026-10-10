# 医院 PC 界面改造第一批验收记录

日期：2026-10-10。实施方案：ui-redesign-plan.md；基线提交：ab30f7c593925442a35466aa40a658f1ba463afd。

## 本批交付

- 全局入口按工作台、数据集成、数据资产、数据质量、数据治理、知识中心六组组织；系统管理和退出固定在底部。240px 展开侧栏、72px 收起侧栏和 64px 顶栏共用中文工作区框架。
- 数据集成下提供数据源管理、集成任务两个独立子菜单。数据源、任务列表和任务详情各自拥有 URL；旧入口经过现有认证流程后兼容跳转，不转抄登录票据。
- 数据源页独立加载连接定义；任务页分别处理引擎、连接、任务的失败。筛选与当前加载集合的 25 条分页存入 URL；详情返回和抽屉关闭保留列表上下文。
- 新增和编辑复用原生右侧 FormDrawer，正文滚动、底栏固定。未保存确认也位于同一抽屉。浏览器前进、后退先恢复原编辑条目，保留草稿，确认放弃后继续导航；不会插入伪造历史条目。提交中禁止重复保存和离开。
- 沿用 Integrate 身份、现有管理员权限、连接凭据保留规则、版本冲突、任务校验、运行/停止/恢复、目录关联及服务端接口。导航迁移保留已存显隐、用户顺序和插件入口。
- 新增菜单及状态文案覆盖 20 种现有语言；本批使用中文界面。其余四组的存量页面仅迁移入口和公共框架，内部重设计属于后续批次。

## 验证与限制

| 检查                                                  | 结果                                                                                                                                                |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Jest 导航/侧栏/菜单迁移/API/集成页面/历史保护 13 套件 | 最终 13/13 套件、190/190 项通过；首次合并运行的密码取消测试焦点恢复时序竞争已修复，完整组合已重跑通过                                               |
| 生产 Vite 构建                                        | 最终生产构建通过（退出码 0，8 分 2 秒）；包含独立复核的状态文字修复                                                                                 |
| 全量 TypeScript 与基线比较                            | 两者均 550 条既有诊断；按源文件和 TS 错误码统计无新增。本次不能宣称全仓类型检查通过                                                                 |
| PC 截图与几何检查                                     | 44 张最终确认截图均由作者和独立设计复核打开；1366×900、1440×1000、1920×1080、1093×720 四种可用尺寸检查通过                                          |
| 原生 Chromium 导航                                    | 任务/数据源编辑时 Back、Forward、连续 Back、带空 history.state 条目、无 Navigation API 的相邻未知条目及历史栈尾恢复通过；草稿和 history.length 保留 |
| Impeccable 检测                                       | 已完成且仅运行一次；针对改动的生产 UI 文件结果为空，退出码 0                                                                                        |
| 独立规格/代码复核                                     | 未保存确认、最新运行时间及 Provider 监听先后次序的发现项已处理，定向代码复核通过                                                                    |
| 独立设计复核                                          | 最终结论 ship；44 张主矩阵和 5 张定向截图已独立确认。共享 utility-success/error-700 状态文字对比度为 5.41:1 / 6.05:1，最终构建门禁已关闭            |

1093×720 用于验证 1366 屏幕在 125%缩放下的可用宽度，不代表手机适配。长表格允许内部横向滚动，页面本身不横向溢出，抽屉底部操作可达。

真实 React 组件的 HTTP 边界使用明确的合成数据、example.invalid 端点和合成界面身份。这些界面截图不证明真实员工已完成门户登录，也不证明 JDBC 抽取、CDC 或目录写入。本批未伪造 1027 身份，没有访问院内主业务库。原生历史回退在无 Navigation API 情况下验证的是相邻未知条目，不扩张为任意多步旧浏览器历史跳转保证。

本机证据（忽略目录，不提交原始日志与截图）：.impeccable/review/pc-navigation 下的 report.json、navigation-behavior.json、jest-final.json、typecheck-comparison.json、production-build.txt、detector.json、finish-review.md，以及 finish-fix/report.json。验证工具及运行方法位于 tools/hospital-pc-preview/README.md。

## 构建与测试环境

本批前端打包脚本为 docker/hospital/build-pc-ui-image.ps1。固定继承已部署服务端镜像 hospital-openmetadata@sha256:934be97369255e2b08bd803fb719b0de0112ff8ba0c739e90ee3d2212f41a473，仅替换 assets 资源 JAR，避免将原工作区尚未交付的 Java 修改带入本批。

### 源码及固定镜像

源码已推送到用户 fork 的 `codex/hospital-pc-ui-phase1` 分支，草稿 [PR #2](https://github.com/Yzero12138/OpenMetadata/pull/2) 的基线是 `feat/hospital-governance-integrate`。镜像对应实现提交 `0a8607115f44cb3bfe3da63f90f7e34d8159e247`；之后的提交仅完善设计和验收文档。

- Harbor 标签：`harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v7-pc`。
- 实际部署固定摘要：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:839e43e99a74c7e475cd5b4daab708f9720080dec2c73d4951ef72bfe9c3b4db`。
- 服务端 JAR SHA-256 保持 `97c22e4d7729a6df5bb7901d0033b5414a5e849677fe47f41b3e4d7502acfdcb`。
- UI JAR SHA-256 为 `040748e4e0cd617822c47717c0eab3258b8890cb6dc1ef173823c93592568257`。本地归档与在线 Pod 内文件均已核对。

### 测试环境升级后确认

2026-10-10 已将 `kubernetes-admin@kubernetes` 上 `datahub-open-test/hospital-openmetadata` 的 `openmetadata` 容器升级到上述固定摘要。rollout 返回成功，可用副本为 1。访问入口仍为 [Integrate 应用门户](https://integrate-dev.qcrmyy.local/s/portal)，测试应用地址为 [医院数据治理](http://172.16.120.211:30925)。

- 根页面、登录入口、两个独立列表以及任务详情 SPA 路径均返回 200；首页内容在执行服务端既有的换行规范化、根路径和每请求 CSP nonce 替换后，与本地构建精确一致。
- 入口与依赖资源、两页动态资源共 138 个文件通过 HTTP 逐字节 SHA-256 校验。
- Integrate 认证配置及系统版本端点返回 200，版本仍为 2.0.4；未带身份的连接、任务和引擎状态接口全部返回 401。未使用合成界面身份访问线上接口。
- 升级前后部署 UID、环境变量名称、ConfigMap/Secret 引用及挂载相同；命名空间 10 个 PVC 的 UID、绑定卷和 Bound 状态相同。SeaTunnel StatefulSet UID 及镜像保持 `sha256:26eb80de58a8855a6a7c459979d283919c143e17eca13fd90e29cb4af75b2347`。

线上核验器最初直接比较原始首页模板与响应体，因服务端注入路径、随机 nonce 并规范化换行而失败；已按 IndexResource 的实际转换规则修正核验并通过，应用代码没有因此改动。真实员工的正向门户登录及院内主业务库同步不在本批自动验证范围。

本机发布证据位于同一忽略目录：image-verification.json、deployment-before.json、deployment-after.json、deployment-reference-verification.json、deployment-http.json、live-jar-sha256.txt、harbor-push.txt 及 volumes-before/after.txt。

### 回退

如需回退本批 UI，只将同一部署的 `openmetadata` 容器恢复为基线固定镜像 `harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:934be97369255e2b08bd803fb719b0de0112ff8ba0c739e90ee3d2212f41a473`，然后等待 rollout。保留现有身份、Secret、数据库、持久卷、SeaTunnel 和检查点。打包及回退说明见 docker/hospital/README.pc-ui.md。
