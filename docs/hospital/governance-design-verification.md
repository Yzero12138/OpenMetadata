# 医院治理扩展设计与验证记录

日期：2026-10-09。使用本地 Impeccable 4.5.1 documenter 规范，承接用户指定的 Taste 默认版、直接代码实现与医院工作台视觉。

## 结论与范围

本次是 **Operate 模式下的既有系统扩展**。最终独立 finish review 的 disposition 为 **ship**：原始三项整改（窄屏顶部操作、节点栏与画布、窄屏分页）全部 resolved，material fixes 的 remaining 为 clear。这个结论只覆盖上述整改与本记录列出的证据边界。

本次记录对应医院治理工作流创建、域类型及相关中文适配、永久移除 GitHub 点星弹窗。全局 [DESIGN.md](../../DESIGN.md) 与 [.impeccable/design.json](../../.impeccable/design.json) 继续作为既有设计系统依据；本记录和 [surface brief](../../.impeccable/surfaces/hospital-governance-workflows.md) 只补充本次页面约束，没有建立新视觉身份或扩展全局令牌。

## 最终行为约束

- 创建抽屉收集技术名称、中文显示名称与描述，进入当前浏览器会话中的本地草稿。抽屉提交不创建服务器工作流；本次没有承诺刷新后恢复、跨设备同步或服务器草稿持久化。
- 编辑器配置触发器、节点与连线后，沿用原生验证及序列化流程；验证通过才调用原生创建接口。成功响应后切换为已保存工作流。
- 创建收到 409 时保留当前草稿、节点和连线，用户可以修改技术名称后再次保存。错误状态不能显示成保存成功，也不能清空正在配置的图。
- 节点选择仅开放本次 OSS 后端支持的能力，Policy Agent 节点不出现。系统工作流保持只读；既有工作流仍使用原生更新流程。
- 内置工作流和相关治理文案通过现有国际化机制展示中文；自定义名称与描述继续作为用户内容。中文标签不能替换 API 枚举或技术名称。
- GitHub 点星弹窗不再注册到已认证应用路由。这是移除弹窗入口，不是依赖临时关闭状态或首次访问标记。

域类型在选择表单和列表标签中采用同一映射：

| API 值 | 中文标签 |
| --- | --- |
| `Aggregate` | 聚合 |
| `Consumer-aligned` | 消费对齐 |
| `Source-aligned` | 源对齐 |

源代码依据：[工作流列表与创建抽屉](../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowsPage/WorkflowsPage.tsx)、[编辑器](../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowBuilder/WorkflowBuilder.tsx)、[保存动作](../../openmetadata-ui/src/main/resources/ui/src/hooks/useWorkflowActions.ts)、[OSS 能力](../../openmetadata-ui/src/main/resources/ui/src/utils/WorkflowClassBase.ts)、[节点栏](../../openmetadata-ui/src/main/resources/ui/src/components/WorkflowDefinitions/WorkflowBuilder/WorkflowSidebar.tsx)、[域类型映射](../../openmetadata-ui/src/main/resources/ui/src/utils/DomainTypeLabelUtils.ts)、[已认证路由](../../openmetadata-ui/src/main/resources/ui/src/components/AppRouter/AuthenticatedAppRouter.tsx)。

## 继承的设计约束

| 项目 | 本次保持的约束 |
| --- | --- |
| 配色 | 医院范围内继承 Integrate 绿色、浅色中性表面与语义状态色；共用控件继续使用 OpenMetadata 语义令牌。 |
| 字体与层级 | 保留医院中文系统字体栈及原生控件字级；页面标题、技术名称、提示和表单标签各自承担已有层级。没有新增全局字级。 |
| Scoped Alias Rule | 医院颜色别名保持局部作用域，不能将局部扩展变成全站主题替换。 |
| Numeric Meaning Rule | 沿用既有数字、不可用和加载状态的含义；本次不改写工作台总数规则。 |
| State Honesty Rule | 本地草稿、验证错误、创建冲突和成功保存分别呈现；合成验证数据始终标明来源。 |

创建抽屉使用原生 Input、TextArea、SlideoutMenu 与 Button；编辑器继续使用原生 Card、Tabs、工作流画布和节点表单。主要保存动作采用已有绿色主按钮，取消和验证采用原生次级操作。没有将此次控件组合提升为新的全局组件规范。

桌面是主要图编辑环境。在最大宽度 767px 的本次响应式规则中，编辑器头部改为纵向排列，标题和操作各有可用空间；长中文标题不挤掉取消、验证与保存。节点栏默认收起，通过带 `aria-expanded` 的按钮开关，展开后自身可纵向滚动并能到达最后一个任务，收起后让出画布。分页以局部网格安排上一页、页码、下一页与每页数量，避免在窄屏重叠。

响应式源代码依据：[编辑器样式](../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowBuilder/WorkflowBuilder.less)、[分页样式](../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowsPage/WorkflowPagination.less)。这些是本 surface 的行为记录，不是所有 OpenMetadata 页面的统一移动布局规则。

## 浏览器证据

[report.json](../../.impeccable/review/governance/report.json) 记录捕获时间为 `2026-10-09T07:55:57.597Z`，fixture 明确为：

> Local synthetic HTTP boundary only; no real hospital authentication or metadata writes

验证运行真实工作流与域选择组件，HTTP 边界使用本地合成 fixture。它证明相应组件交互和请求行为，不代表真实医院认证、代表性医院数据或生产写入已经验收。

| 页面 | 桌面 1440 × 1000 | 窄屏 390 × 844 |
| --- | --- | --- |
| 工作流列表 | [截图](../../.impeccable/review/governance/workflow-list-1440.png) | [截图](../../.impeccable/review/governance/workflow-list-390.png) |
| 创建抽屉 | [截图](../../.impeccable/review/governance/workflow-create-1440.png) | [截图](../../.impeccable/review/governance/workflow-create-390.png) |
| 工作流编辑器 | [截图](../../.impeccable/review/governance/workflow-editor-1440.png) | [截图](../../.impeccable/review/governance/workflow-editor-390.png) |
| 域类型选择 | [截图](../../.impeccable/review/governance/domain-types-1440.png) | [截图](../../.impeccable/review/governance/domain-types-390.png) |

最终 8 张捕获均有效；报告中每张截图的 `errors` 为空，页面 `scrollWidth` 与相应 viewport 宽度一致。截图提供可见状态，交互结论以报告中的 checks 和本次独立复审为准。

桌面 checks 覆盖：草稿保持本地、配置后图提交、重名保留图、改名重试成功，以及 Policy Agent 节点不存在。390px checks 覆盖：长中文标题、全部顶部操作与画布工具栏可达，节点栏打开、滚动到最后一个任务和关闭，开始节点配置验证通过。**完整连线、保存和 409 冲突恢复的浏览器证据限桌面；390px 不声明完整图编辑流程已验收。**

## 原始整改复核

| 原始问题 | 最终结果 | 证据与边界 |
| --- | --- | --- |
| 窄屏顶部操作不完整 | resolved | 390px 编辑器中取消、验证工作流、保存工作流完整可达，长标题不挤占操作。 |
| 节点栏挤占画布 | resolved | 390px 节点栏可开关、可滚动至最后一个任务；收起后画布与工具栏可用。 |
| 窄屏分页重叠 | resolved | 390px 列表中分页与每页数量分开排列，没有重叠。 |

独立 finish review 记录：persistence pass；fidelity 三项全部 resolved；ceiling 达到原始三项；material fixes remaining clear；保留医院绿色体系、原生组件、中文标签与 API 枚举，以及桌面创建和冲突恢复。此记录不将复审扩大为整个应用或院内认证端到端验收。

## 工程验证与真实局限

下列结果来自本次实施会话已完成的验证，本 documentation pass 检查已有证据，没有重新运行 detector、浏览器或测试。

| 验证 | 最终结果 | 本地证据 |
| --- | --- | --- |
| 范围回归 | 13 suites / 85 tests PASS | [日志](../../.logs/governance-final-tests.log) |
| 响应式相关回归 | 3 suites / 15 tests PASS | [日志](../../.logs/governance-responsive-tests.log) |
| UI checkstyle | 74 个 UI source/resource 文件 PASS | [日志](../../.logs/governance-checkstyle-final.log) |
| 最新生产构建 | PASS | [日志](../../.logs/governance-ui-build-final.log) |
| 已执行的 scoped detector | `[]`，只代表该次扫描范围 | [结果](../../.logs/governance-detector.json) |
| TypeScript 全量检查 | exit 2；基线 550、最终 550，added `[]`、removed `[]` | [最终日志](../../.logs/governance-typecheck-final-v4.log)；实施会话用 `compare-governance-types.cjs` 比对 |

TypeScript 结果表示没有新增诊断，不表示全仓类型检查通过。工程回归和本地合成 HTTP 边界也不能替代真实后端权限、医院 Integrate 登录及生产环境写入验收。

本次按普通扩展保留全局设计记录，未执行格式迁移。已有 DESIGN.md 记载的原生按钮阴影工具名拼写差异仍为未确认漂移；TaskFormSettings 的既有蓝色渐变、阴影和 eyebrow 样式也没有被提升为医院设计系统规则。本次仅报告这些既存情况，不在文档收尾中修复或将其合法化。
