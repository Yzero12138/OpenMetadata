# Doris 目标端页面扩展记录

记录日期：2026-10-20。范围：现有医院 PC 数据集成页面中的 Doris 目标类型、目标建表和字段映射。采用项目内固定的 Impeccable 4.7.0 完整配套包和 Taste，沿用已确认的 Integrate 绿色、中文系统字体及原生组件身份。本次为代码驱动的现有系统扩展；未引入新的视觉世界、组件库、构图样稿或设计种子。

[PRODUCT.md](../../PRODUCT.md)、[DESIGN.md](../../DESIGN.md) 及其 [sidecar](../../.impeccable/design.json) 继续作为产品和系统约束；本次文档不修改后两者。[Doris 方向契约](../../.impeccable/surfaces/hospital-data-integration.md#direction-contract--doris-target-extension-2026-10-20) 保留页面局部承诺。后台建表和部署分别见 [Doris 后端契约](doris-backend-contract.md)、[Doris 部署记录](doris-deployment.md)，它们与本记录的页面证据分别验收。

## 已实现的操作路径

数据源管理和集成任务继续使用独立菜单及路由。管理员在原有数据源右侧抽屉中选择 Apache Doris，登记 SQL 和 HTTP 端口、数据库及账户。Doris 仅可作为目标端；表单默认 SQL 端口 9030、HTTP 端口 8030，版本输入提示为 4.0.8，并明确当前只支持关闭 TLS。数据库同时约束采集范围，切换数据库类型会清除不适用的家族设置。数据源列表继续显示 SQL/HTTP 端点事实，保存定义与连接测试仍是不同结果。

任务抽屉保留四类现有源数据库。选定源表和 Doris 目标后，路由选择区下方出现“创建目标表”区域。展开后可编辑建议的小写目标表名及列名，逐列选择“包含”或“不采集此字段”；源主键列保持包含。名称校验限制为小写字母开头，后续可含小写字母、数字和下划线，最长 64 个字符，目标列名必须唯一。

“预览目标表”调用服务端返回目标类型、主键和可空信息，并以只读 DDL 披露生成结果。只有与当前草稿一致的预览才能进入“确认创建目标表”。修改目标名称或包含列会使原预览失效；旧请求、已离开的源表和已卸载表单不能覆盖新草稿。预览及创建期间锁住重复操作和父抽屉关闭。失败显示可聚焦的错误摘要并保留编辑；预览过期或同名表冲突会要求重新预览。

创建成功后刷新目标表选项、选中新表，并使用服务端返回的字段映射初始化任务。任务名称等既有草稿仍保留，被跳过的列仍明确显示为不采集。截图中的成功状态来自合成 HTTP 返回，不证明 Doris 已执行 DDL。

实现依据为 [连接表单](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnectionForm.tsx)、[目标建表表单](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTargetTableForm.tsx)、[任务表单](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx)、[工作区状态衔接](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx) 和 [类型化 API](../../openmetadata-ui/src/main/resources/ui/src/rest/hospitalIntegrationAPI.ts)。

## 对现有设计系统的核对

对照 [医院主题](../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less)、[集成页局部样式](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less) 及现有系统文件，Doris 扩展延续以下已记录规则，没有形成新的全局设计令牌。

| 项目 | 已核对的代码事实及用途 |
| --- | --- |
| 颜色 | 主题绿色使用现有语义别名，对应主色 `#12634b`、悬停 `#087354`；正文、次级文字、分隔线对应 `#182f29`、`#53675f`、`#dce6e0`。错误和成功继续使用核心语义角色。 |
| 字体与层级 | 继承 PingFang SC、Microsoft YaHei、Source Han Sans SC、system-ui、sans-serif。页标题 24px/600，区标题 17px/600，局部小标题 15px，表格和源列名 13px，支持信息 12px；抽屉标题沿用现有 18px 原生角色。 |
| 页面和字段 | 页面宽度 100%、内边距 24px；抽屉字段沿用两列、20px/24px 间距。建表列行以源字段、目标名、包含开关三列排列，使用细分隔线和 12px 纵向内边距。 |
| DDL 与深度 | DDL 为功能性等宽文本，12px、1.7 行高、16px 内边距、8px 圆角，最大高度 280px 并可滚动。普通内容区仍平面分区；原生抽屉保留覆盖层、阴影和页脚分隔。 |
| 焦点与状态 | 沿用 2px 品牌色焦点轮廓及 3px 偏移；原生 Input、Select、Checkbox、Button 提供键盘、禁用和忙碌状态。加载、错误、跳过字段和创建成功使用不同文本含义。 |

现有命名规则仍适用：The Scoped Alias Rule、The Status Meaning Rule、The Numeric Meaning Rule、The State Honesty Rule。Doris 的端口、列布局及 DDL 尺寸属于本页面的功能和局部测量，不上升为全站规则。

数据源抽屉宽度仍为 720px；任务新建 880px、编辑 960px。使用现有 FormDrawer，标题与底部动作保持可见，长映射和预览内容在正文内滚动。宽度依据 [数据源宿主](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnections.tsx) 及任务工作区代码；本轮任务截图覆盖编辑抽屉，并不单独建立 880px 新建状态的视觉验收。

## 滚动问题及修复证据

[首次完成评审](../../.impeccable/review/doris/finish-review.md) 为 `disposition: fix`，只提出一项实质问题：预览和创建失败状态会滚动整个抽屉，标题、关闭按钮离开顶部，页脚上移。旧记录在 1440px 下显示抽屉 `scrollTop=191`、标题 `y=-191`、页脚底部 809px，而视口高度为 1000px，见 [red 记录](../../.impeccable/review/doris/drawer-scroll-red.json)。

修复仅在本页建表列的 Checkbox 标签上设置 `position: relative`，使原生隐藏输入的焦点锚点归属于该行；未修改共享 FormDrawer。后续 [green 记录](../../.impeccable/review/doris/drawer-scroll-green.json) 覆盖两个 PC 尺寸的鼠标点击、键盘切换、滚轮、预览按钮自动滚动、DDL 展开和错误自动聚焦。记录显示抽屉滚动位置保持为 0，标题位于顶部，页脚底部分别固定在 1000px 和 1080px，变化发生在正文滚动位置。

[第二次评审](../../.impeccable/review/doris/finish-verdict.md) 返回 `disposition: ship`：该项修复已解决，重新采集内容未出现归因于此修复的回归。此结论只覆盖该项受评修复；不扩展为整个页面新一轮完整通过。

## 页面证据和验证边界

[最终报告](../../.impeccable/review/doris/report.json) 的采集时间为 `2026-10-20T09:47:33.806Z`，`captureRound: 2`、`passed: true`、记录错误数 0。八张截图使用实际 React 工作区、原生核心组件和 FormDrawer，连接本地明确标注的合成 HTTP 夹具；未使用员工 JWT、生产接口或医院数据库端点。

| 状态 | 1440 × 1000 | 1920 × 1080 |
| --- | --- | --- |
| Doris SQL/HTTP 端点 | [截图](../../.impeccable/review/doris/doris-source-endpoints-1440.png) | [截图](../../.impeccable/review/doris/doris-source-endpoints-1920.png) |
| 目标列预览及只读 DDL | [截图](../../.impeccable/review/doris/doris-table-preview-ddl-1440.png) | [截图](../../.impeccable/review/doris/doris-table-preview-ddl-1920.png) |
| 创建失败、保留草稿 | [截图](../../.impeccable/review/doris/doris-table-create-failure-1440.png) | [截图](../../.impeccable/review/doris/doris-table-create-failure-1920.png) |
| 新表选中及字段映射 | [截图](../../.impeccable/review/doris/doris-table-created-mappings-1440.png) | [截图](../../.impeccable/review/doris/doris-table-created-mappings-1920.png) |

最终报告中八个状态均记录无文档宽度溢出，标题 `y=0`、抽屉 `scrollTop=0`，页脚底部等于视口高度。本文档核对了全部状态的结构化测量，并直接查看其中四张代表截图：1440px 端点与 DDL、1920px 失败与映射。第二次评审负责全部八张重新采集图的复核。

父任务已记录集成/API 组五套件 73/73 测试通过；organize-imports → ESLint --fix → Prettier 顺序检查完成；TypeScript 仍为既有 550 条项目诊断，所负责集成/API 文件新增诊断为 0；新增文本已传播至 20 个实际翻译文件。单次 [检测器结果](../../.cache/doris/ui-detector.json) 为 `[]`。这些检查由父任务执行，本次文档没有重跑测试、浏览器、服务器、检测器或构建。

本记录仅确认已实现页面及上述合成边界证据。最终 Vite 构建、镜像、Doris 服务部署、JDBC 连接、实际 DDL、数据写入和目录登记由父任务在各自交付轨道记录；页面截图和视觉结论不代替这些结果。交付范围为 PC，未建立手机或深色主题验收，也未建立真实医院数据、员工登录或多源 CDC 验收。

## 保留的既有偏差

本次保留 DESIGN.md 和 design.json 原样。它们已记录的原生按钮阴影拼写差异（组件请求 `shadow-xs-skeuomorphic`，全局定义 `--shadow-xs-skeumorphic`）仍存在；历史合成截图的 `#f8f9fc` 底色也不成为固定品牌色。两者继续作为未规范化的既有偏差，不因本次文档改写或修复。

较早的 [数据集成扩展记录](data-integration-design-extension.md) 仍有居中 1280px、32px 内边距、27px 页标题的描述；当前代码和现行 DESIGN.md/sidecar 已记录 100% 宽度、24px 内边距、24px 页标题。此为已有文档漂移，本次只报告；本 Doris 记录以上述当前源代码及现行系统为准。

## 真实同步发现后的功能提示补充

原生 SeaTunnel 3.0.0 会把目标库、表、作业号、子任务与时间戳追加到 Stream Load 标签。服务端使用固定短前缀，并在预览、创建和任务提交前约束库名与表名合计不超过 67 字符，且只使用英文字母、数字和下划线。`DORIS_LABEL_INVALID` 已加入 20 种实际翻译和明确请求拒绝分类；提示保留草稿、聚焦错误并指引缩短名称后重新预览。该限制涉及库名和表名，中文及 emoji 数据不受影响。新增提示和版本示例未更改布局、样式或既有八个捕获状态；对应行为另由错误映射和草稿回归验证。

最终功能补充后的集成/API回归为5个套件75/75通过，TypeScript仍为550基线、受改文件0新增。完整套件曾复现旧测试外层异步act对原生焦点恢复的调度干扰；仅修改该测试中“继续编辑”的点击方式，保留全部原断言，生产代码及已经发布的UI制品不变。最终镜像与实际同步、保存点恢复证据见 [交付验收](doris-verification.md)。
