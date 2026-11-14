# Doris 目标表设计器页面扩展记录

记录日期：2026-11-13。源码基点：`f67b36bd9b047a53de0238fea8e80724cb7ce7ff`。本次记录独立目标表管理、共享字段设计器、已有表追加字段及任务入口衔接的已实现界面。交付范围为中文医院 PC 工作区，采用 code-led、Operate、ordinary extension；沿用既有 Integrate 绿色、中文系统字体和 OpenMetadata core 控件。

[PRODUCT.md](../../PRODUCT.md)、[DESIGN.md](../../DESIGN.md)、[设计 sidecar](../../.impeccable/design.json)、[医院工作台 brief](../../.impeccable/briefs/hospital-workbench.md) 和 [PC 导航合同](../../.impeccable/surfaces/hospital-pc-navigation.md) 继续提供上层约束。本次没有新增视觉世界、全局令牌、组件库或产品栅格素材，保留 DESIGN.md 与 design.json 原样。功能方向见 [设计合同](doris-table-designer-design.md)，操作步骤见 [使用说明](doris-table-designer.md)，持久化 surface 见 [hospital-doris-table-designer.md](../../.impeccable/surfaces/hospital-doris-table-designer.md)。

## 已实现的路径与交互

数据集成新增独立的“目标表管理”子菜单和 `/hospital/integration/target-tables` 路由，与数据源管理、集成任务保持同级。页面只列出启用的 Doris 目标连接。管理员选择连接后可搜索目标表、刷新列表，并对每表执行“查看结构”“新增字段”“创建集成任务”。无可用连接时引导至数据源管理；无表、加载失败和加载中各有对应内容。非管理员不发起连接发现请求。

“新建目标表”不依赖来源。共享设计器展示已保存连接限定的只读数据库，编辑表名、表说明、分桶数量及字段。新建采用单个非空主键、UNIQUE KEY、按主键 HASH 分桶与 Merge-on-Write；主键在规范化时排首。副本数由环境控制。

字段主行保留名称、类型、主键、可空和操作。一个字段的展开配置区渐进展示 CHAR/VARCHAR 字节长度、DECIMAL 总精度和小数位、DATETIME 小数秒精度，以及默认值方式、字面值和说明。默认值选项随类型及可空性变化；浏览器不接受任意 SQL 表达式。主键使用原生 radio，互斥且禁用不适用的 FLOAT、DOUBLE、STRING；选定主键同时取消可空。其他控件使用 core Input、Select、Checkbox、Button，行操作沿用原生 SVG 图标及明确的可访问名称。

新增字段聚焦名称输入；复制保留字段配置、生成不重复的名称并取消主键；上移、下移、删除仅改变草稿字段。最多 128 列，字段名最多 64 字符。真实表结构始终只读，不提供删除原列、改名、改类型或替换主键的界面。

追加字段前读取实际结构，拒绝不支持的表模型、活动结构变更及已达列数上限的表。当前结构和待新增列分区；数据库、表名不可改变。新增非空列要求非空默认值，已有字段与主键保留。详细类型及版本限制以使用说明和服务端合同为准。

预览分别调用结构化 CREATE 或 ADD_COLUMNS 接口，只显示服务端生成的只读 DDL。确认动作要求当前草稿与预览一致；任一输入变化都会清除旧预览。请求代次、草稿键、来源键、AbortController 和卸载检查共同防止迟到响应覆盖新草稿；提交期间阻止重复执行和关闭。

## 任务衔接与状态边界

独立页面完成创建后提示成功、关闭设计器并刷新列表。列表或结构抽屉的“创建集成任务”把有界的连接 ID、数据库和表名带入任务页面，再由任务流程读取实际定义。

任务抽屉也使用同一设计器，可以在未选来源时建表。已选来源表，或具备主键的 SQL 结果，可导入初始字段；已修改字段会先确认覆盖，表名及表说明保留。任务内创建成功才由其回调选中新表并回填可对应映射，保留其他任务草稿。原来源自动建表入口继续存在。两种入口分别保存 busy/dirty，再合并向父抽屉报告，防止关闭一个干净入口清除另一个入口的未保存状态。任务路由被历史初始化锁定时不提供设计入口。

| 状态 | 界面及恢复方式 |
| --- | --- |
| 编辑、未预览 | 保留可编辑草稿；确认创建/新增不可用。 |
| 预览有效 | 展示只读 DDL，允许确认；修改字段或表设置后需重新预览。 |
| 请求进行中 | 禁止重复操作和关闭；原生 footer 保留位置。 |
| 校验或权限失败 | 可聚焦错误摘要，字段保留；权限错误说明目标库需要 CREATE/ALTER。 |
| 已提交、等待生效 | 显示“结构变更已提交”，字段锁定，提供“刷新结构”；关闭不撤销已经提交的变更。 |
| 网络结果未知 | 不自动重复执行 DDL；先手动读取实际结构核实结果。 |
| 已核实生效 | 新列的类型、空值、默认值和说明匹配，且无活动/失败结构任务时才调用完成回调。 |

已提交且结果确定的变更每 3 秒检查实际结构；读取失败或结构任务失败后停止自动检查，保留手动刷新。仅看到同名字段不足以认定完成。关闭、导航、继续编辑和放弃草稿继续使用既有 FormDrawer 与导航守卫；busy 期间阻止离开，dirty 状态要求明确放弃。

## 对现有设计系统的核对

| 项目 | 来源与实际应用 |
| --- | --- |
| 色彩 | [医院主题](../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less) 的现有品牌、正文、次级文字、边框别名连接 DESIGN.md 的 `primary`、`primary-hover`、`ink`、`muted`、`border`。字段配置区、选中行和 DDL 使用 core `bg-secondary`，失败使用既有 error 语义。未新增固定色值。 |
| 字体 | 继承 PingFang SC、Microsoft YaHei、Source Han Sans SC、system-ui、sans-serif；原生控件明确指定的字体仍由 core 决定。页面标题为现有 24px/600，抽屉标题 18px/600；数据表和字段配置标题 13px，支持文字 12px。DDL 用等宽文本。 |
| 平面与深度 | 中性工作区、细分隔线和浅色字段区组织内容；不增加装饰阴影。编辑层保留 core SlideoutMenu 的覆盖层、阴影和 footer 分隔。 |
| 焦点与状态 | 品牌色 radio 与既有 2px 焦点轮廓；原生控件负责禁用、忙碌和焦点行为。错误、已提交与已生效使用不同文字，延续 The Scoped Alias Rule、The Status Meaning Rule、The State Honesty Rule。 |

局部参数来自 [设计器样式](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/hospital-table-design.less)、[集成页样式](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less) 和 [共享抽屉样式](../../openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/form-drawer.less)，不是全局令牌更新：

- 页面沿用全宽和 24px 内边距。工具栏为目标连接、搜索、刷新三列，连接列范围 240–360px，搜索至少 240px，间距 16px。
- 新建及追加使用 1100px 右侧全高 FormDrawer；独立只读结构使用 1000px。共享默认宽度和其他页面宽度未改。两张编辑捕获分别位于 x=340 和 x=820，对应 1440/1920×1080。
- 原生标题区内边距 20px/24px，正文内边距 24px，footer 操作固定，正文内部滚动。表设置和展开字段区为两列，行列间距 18px/24px。
- 字段表采用固定布局，名称约 24%、类型 22%、操作 28%，其余分配给主键和可空；单元格内边距 12px/8px。展开区内边距 16px，完整字段名显示在其标题内。
- 真实结构摘要为两列，间距 16px/24px，标签 13px、值 14px。捕获中四项摘要总高 105px，这是测量值，不是固定高度规则。
- DDL 使用 12px、1.65 行高、16px 内边距和 8px 圆角；长文本可换行并保留局部滚动。64 字符名称在原生输入内水平滚动，展开标题不溢出正文。

实现入口：[目标表页面](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/HospitalTargetTablesPage.tsx)、[设计器](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisTableDesigner.tsx)、[字段编辑器](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisFieldEditor.tsx)、[结构视图](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisTableStructureView.tsx)、[任务表单](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx)。

## 图像、复核和准确范围

证据目录为 [.impeccable/review/doris-designer](../../.impeccable/review/doris-designer)。[来源记录](../../.impeccable/review/doris-designer/VISUAL-REPORT.md) 和采集源码表明：真实页面工作区、设计器、字段编辑器、结构视图、FormDrawer 与 core 控件通过静态 Vite 夹具运行；Playwright 在 `doris-designer-fixture.invalid` 拦截请求并返回显式合成响应，无监听服务器。外围 PageLayout、应用用户状态与 Toast 边界有轻量替身；画面未呈现完整认证侧栏和顶栏。顶部合成提示仅属于夹具。捕获使用 reduced motion，因此不建立正常动效验收。

[report.json](../../.impeccable/review/doris-designer/report.json) 记录 14 张列表/设计状态；[computed.json](../../.impeccable/review/doris-designer/computed.json) 补充两张长字段错误状态；[structure-confirm.json](../../.impeccable/review/doris-designer/structure-confirm.json) 补充两张独立结构状态。[provenance.json](../../.impeccable/review/doris-designer/provenance.json) 保存根目录最终 18 张图的文件名、字节数和 SHA-256。

| 状态 | 1440×1080 | 1920×1080 |
| --- | --- | --- |
| 目标表列表 | [截图](../../.impeccable/review/doris-designer/target-list-1440.png) | [截图](../../.impeccable/review/doris-designer/target-list-1920.png) |
| 字段设计 | [截图](../../.impeccable/review/doris-designer/create-fields-1440.png) | [截图](../../.impeccable/review/doris-designer/create-fields-1920.png) |
| CREATE 预览，F1 后 | [截图](../../.impeccable/review/doris-designer/create-preview-1440.png) | [截图](../../.impeccable/review/doris-designer/create-preview-1920.png) |
| 追加字段 | [截图](../../.impeccable/review/doris-designer/append-fields-1440.png) | [截图](../../.impeccable/review/doris-designer/append-fields-1920.png) |
| ADD COLUMN 预览 | [截图](../../.impeccable/review/doris-designer/append-preview-1440.png) | [截图](../../.impeccable/review/doris-designer/append-preview-1920.png) |
| 已提交，等待生效 | [截图](../../.impeccable/review/doris-designer/append-submitted-1440.png) | [截图](../../.impeccable/review/doris-designer/append-submitted-1920.png) |
| 权限失败与草稿 | [截图](../../.impeccable/review/doris-designer/create-permission-1440.png) | [截图](../../.impeccable/review/doris-designer/create-permission-1920.png) |
| 真实结构只读 | [截图](../../.impeccable/review/doris-designer/existing-structure-1440.png) | [截图](../../.impeccable/review/doris-designer/existing-structure-1920.png) |
| 长字段与错误 | [截图](../../.impeccable/review/doris-designer/long-field-error-1440.png) | [截图](../../.impeccable/review/doris-designer/long-field-error-1920.png) |

第一轮观察发现真实结构摘要引用了未定义的布局样式，呈现默认纵向 dt/dd 堆叠。单批修复改为上述局部两列摘要后确认。结构化记录中的页面横向溢出和 pageerror 均为零。测量的表头、字段配置标题、权限错误、抽屉标题对比分别约为 6.05:1、5.79:1、6.05:1、14.21:1；这些仅是捕获浅色背景上的指定元素，不是全站或双主题认证。

[fresh finish review](../../.impeccable/review/doris-designer/finish-review.md) 实际检查根目录全部 18 图，初始为 `disposition: fix`，唯一 material finding F1 是共享 CREATE 预览无条件承诺“随后自动选中并应用字段映射”。独立建表没有来源，其完成回调只刷新列表。修正使用专属 `hospitalTableDesign.createHint` 及 20 个实际语言翻译；中文为“确认后将创建表，不覆盖已有同名表；创建完成后可用于集成任务。”，原来源自动建表提示保留原义。

F1 后仅替换两张 create-preview 图，并更新其 provenance 项；[f1-confirm.json](../../.impeccable/review/doris-designer/f1-confirm.json) 记录提示完整可见、高度 18px、y=929.58、无溢出和无页面异常。[最终 verdict](../../.impeccable/review/doris-designer/finish-verdict.md) 为 F1 resolved、remaining clear、`disposition: ship`，原文限定：

> 本次仅评分前次唯一 material finding F1；未重开整面审查。`ship` 仅覆盖这条文案修复在 1440×1080、1920×1080 两个 PC 视口的验收，不构成新的全局、真实认证或引擎验收。

本次文档核对源代码和上述结构化记录，并直接查看 10 张代表图：1440 的列表、字段设计、结构、CREATE 预览、ADD COLUMN 预览、已提交；1920 的 CREATE 预览、追加字段、权限错误、长字段错误。未启动浏览器、服务器、构建或第二轮检测器，也不替代 fresh reviewer 的评分。

## 行为验证与发布边界

父任务的 [验证记录](doris-table-designer-verification.md) 与现有日志记录服务端 249 项通过、2 项既有环境用例跳过；UI 相关 11 套件 138 项通过。后续设计器文案回归为 8/8、任务设计入口回归为 4/4。TypeScript 仍有 550 条既有诊断，本次涉及文件新增 0 条，不能称全仓类型检查通过。imports → ESLint → Prettier 检查由父任务执行。单次 [detector](../../.impeccable/review/doris-designer/detector.json) 为 `[]`，本次没有重跑。

任务回填、未保存保护、未知结果及实际结构匹配主要由源码和行为测试建立；本轮 18 图不包含任务抽屉，也不单独建立这些流程的截图验收。夹具内的 DDL 与成功/异步响应不证明真实 Doris 执行。候选服务的 CREATE/ADD、两次 SeaTunnel 完成、隔离 `ods_validation` 的 ALTER 权限变更等原生证据单独保存在验证记录；它们是发布前证据，不代替最终镜像及部署后结果。

正式服务构建、Vite 生产构建、镜像、发布和部署后验收由各自交付记录说明；静态视觉夹具构建不代表这些门槛已通过。本文不声明已发布。无真实 HIS、医院患者数据或员工 SSO 验收，也未建立手机、深色主题、跨源 CDC 或全站导航验收。凭据留在既有服务端连接边界，界面不把连接密码暴露到浏览器。

## 保留的既有漂移

现有 DESIGN.md 和 sidecar 已记录 core Button 请求 `shadow-xs-skeuomorphic`、globals 定义 `--shadow-xs-skeumorphic` 的拼写差异；本次读取源码确认仍在，不据此推导新阴影规则。历史夹具的 `#f8f9fc` 观测也继续不作为固定品牌背景。

较早的 [data-integration-design-extension.md](data-integration-design-extension.md) 仍描述居中 1280px、32px 内边距、27px 页面标题；当前源码及现行系统记录为全宽、24px 内边距、24px 标题。该历史文档漂移仅报告，不在本次范围内修复。本次未发现需要改写全局令牌的新增设计差异。
