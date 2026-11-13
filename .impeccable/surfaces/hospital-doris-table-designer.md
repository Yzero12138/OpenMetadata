---
primaryTarget: openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/HospitalTargetTablesPage.tsx
relatedTargets:
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisTableDesigner.tsx
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisFieldEditor.tsx
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisTableStructureView.tsx
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/DorisTableDesignUtils.ts
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalTargetTablesPage/hospital-table-design.less
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx
mode: Operate
---

# Hospital Doris target table designer

2026-11-13。Ordinary extension，code-led，PC only，中文医院操作界面。方向来自用户已批准的 [Doris 设计合同](../../docs/hospital/doris-table-designer-design.md) 和既有医院工作台体系；源码基点 `f67b36bd9b047a53de0238fea8e80724cb7ce7ff`。本 surface 记录页面构成与交互，不替代 [DESIGN.md](../../DESIGN.md) 或 [design.json](../design.json)，也不升级为新的视觉世界或全局令牌。

## Direction contract

THESIS: 让管理员不依赖来源即可设计 Doris 目标表，核对结构化预览后创建，并在保留真实原列、主键和数据的前提下追加字段。

OWN-WORLD: 沿用 Integrate 绿色、中文系统字体、语义中性表格、core Input/Select/Checkbox/Button 及原生 SVG 图标。共享右侧 FormDrawer 承担编辑层；浅色字段配置区和细分隔线提供层级，不引入装饰图像或新组件库。

STORY: 从数据集成的独立目标表管理选择 Doris 连接，查看实际表、创建表或追加字段，再带入任务；在任务中也能使用相同设计器，并可从已有来源结构导入字段。独立建表成功只刷新列表，任务内回调负责选表与映射。

FIRST VIEWPORT: 面包屑、24px 标题和一个“新建目标表”主动作，随后是目标数据源、搜索、刷新以及每表三个明确动作。新建/追加在 1100px 右侧全高抽屉进行；真实结构的独立只读抽屉为 1000px。标题与底部操作固定，长表单在正文内滚动。

FORM: 表设置在前，字段主行显示名称、类型、单主键、可空和操作；展开配置随类型呈现字节长度、精度、小数位、日期时间精度、默认值和说明。复制、增删、移动只作用于草稿。当前结构与待新增字段分区，服务端 DDL 只读，修改配置后必须重新预览。延续现有紧凑数据层级、原生 focus 和 reduced-motion 规则。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Shipped layout and token authority

[医院工作台 brief](../briefs/hospital-workbench.md)、[PC 导航合同](hospital-pc-navigation.md) 与全局系统继续约束品牌和共享组件。该菜单是“数据集成”下的同级目的地 `/hospital/integration/target-tables`，不使用顶层对象切换 tabs。页面读取启用的 Doris TARGET 连接，仅管理员可发现和维护目标结构。

页面全宽，24px 内边距，工具栏三列，间距 16px。设计器字段区为两列，间距 18px/24px；表格为 13px，配置标题 13px/600，正文提示 12px。字段表名称/类型/操作列约占 24%/22%/28%，其余给主键与可空；展开区内边距 16px。真实结构摘要两列、间距 16px/24px；四项摘要捕获高 105px，是内容测量而非固定高度。

1100px 和 1000px 属于本 surface；不更改共享 FormDrawer 默认宽度、任务宿主宽度或全站断点。共享 header 为 20px/24px、body 为 24px，footer 固定。DDL 为 12px、1.65 行高、16px 内边距、8px 圆角，长文本换行并可局部滚动。字段输入保留自身水平滚动，完整名称可在展开标题中阅读。

品牌、正文、次级文字、边框、错误继续使用医院主题及 core 语义角色，浅字段区和 DDL 使用 `bg-secondary`。字体继承 PingFang SC、Microsoft YaHei、Source Han Sans SC、system-ui、sans-serif，保留 core 明确指定的角色。没有新增全局颜色、字体、阴影、圆角或动效令牌；也没有新的产品栅格素材。源码及详细来源见 [设计扩展记录](../../docs/hospital/doris-table-designer-design-extension.md)。

## Interaction and truth contract

- 新建不要求来源；数据库只读，单个非空主键，主键排首，HASH 分桶和 Merge-on-Write，副本数由部署决定。类型及默认值只接受结构化输入。
- 追加前读取实际结构，拒绝不支持的模型、活动变更及列数上限；真实字段只读。非空新列必须有非空默认值。没有删除原列、改名、改类型或替换主键入口。
- 输入变动使预览失效，提交只能使用当前 previewHash。代次、草稿/来源键、取消请求与卸载检查防止旧响应覆盖新草稿。
- busy 时禁止重复提交、关闭和导航；dirty 时保留既有继续编辑/放弃确认。自动建表和手动设计器独立保存 dirty/busy，再合并到任务宿主。
- 来源导入只替换字段，保留表名与说明；已修改字段先确认覆盖。任务内成功回填实际目标表及可对应映射，保留其他草稿；独立页面成功仅关闭并刷新列表。
- SUBMITTED 表示等待 Doris 生效，字段锁定并提供刷新结构；正常提交每 3 秒检查。未知网络结果先手动刷新，不重复 DDL。新列定义、默认值、说明及结构任务状态核对完成才认定生效。关闭面板不会撤销已提交的变更。
- 错误使用可聚焦摘要并保留草稿；CREATE/ALTER 权限恢复、预览过期、活动任务和结构失败有不同含义。保留旧来源自动建表及 FULL、CDC、SQL T+1、历史初始化流程。

共享 CREATE 预览只承诺“确认后将创建表，不覆盖已有同名表；创建完成后可用于集成任务。”。自动选中与字段映射属于任务上下文，不能向独立入口无条件承诺。

## Evidence and review boundaries

最终 [.impeccable/review/doris-designer](../review/doris-designer) 根目录有 9 场景 × 1440×1080 / 1920×1080，共 18 张完整视口图。[report.json](../review/doris-designer/report.json) 列 14 张；[computed.json](../review/doris-designer/computed.json) 补两张长字段错误及指定对比测量；[structure-confirm.json](../review/doris-designer/structure-confirm.json) 补两张独立结构及两列摘要。[provenance.json](../review/doris-designer/provenance.json) 保存最终 18 图哈希。完整场景链接和本次文档直接查看的 10 张代表图见设计扩展记录。

这是实际 React 页面工作区、共享设计器和 core 控件的静态夹具，HTTP 在 `.invalid` 域拦截并返回显式合成数据，无监听服务器、真实 HIS、员工登录或集群写入。外围布局、用户状态和 Toast 边界有替身；截图未呈现完整认证侧栏/顶栏。捕获使用 reduced motion，不认证正常动效。画面顶部合成提示是夹具说明，不是产品功能。

在单批观察中修复了真实结构摘要缺样式的纵向堆叠，再确认两列布局。指定测量中页面横向溢出、pageerror 均为零；字段配置标题最低观测对比约 5.79:1，仅适用于相应浅色捕获元素。

[fresh finish review](../review/doris-designer/finish-review.md) 查看全部 18 图后为 `disposition: fix`，唯一 material finding F1 是独立建表不成立的自动选中/映射提示。已用专属 createHint 和 20 个实际语言翻译修正，只替换两张 CREATE 预览图；[f1-confirm.json](../review/doris-designer/f1-confirm.json) 记录完整可见和无溢出。[最终 verdict](../review/doris-designer/finish-verdict.md) 为 F1 resolved、remaining clear、`disposition: ship`，原文限定：

> 本次仅评分前次唯一 material finding F1；未重开整面审查。`ship` 仅覆盖这条文案修复在 1440×1080、1920×1080 两个 PC 视口的验收，不构成新的全局、真实认证或引擎验收。

父任务验证记录为后端 249 passed / 2 既有 skip、UI 相关 138 passed，后续设计器 8/8、任务设计入口 4/4；TypeScript 为 550 既有诊断、涉及文件新增 0，不是全仓干净。单次 [detector](../../.impeccable/review/doris-designer/detector.json) 为 `[]`。本次文档没有重跑检测器、浏览器、服务或构建。

截图不包含任务抽屉；任务回填、预选目标后选择来源、dirty/busy 和未知结果保护由源码及行为测试补充。正式构建、镜像、发布和部署后验收另行记录，静态夹具构建不建立其通过结论。候选 Doris/SeaTunnel 原生验收见 [验证记录](../../docs/hospital/doris-table-designer-verification.md)，不代替最终发布证据。不认证手机、深色主题、真实患者数据、员工 SSO、全站导航或跨源 CDC。

## Preserved pre-existing drift

core Button 的 `shadow-xs-skeuomorphic` 与 globals 的 `--shadow-xs-skeumorphic` 拼写差异仍按既有全局记录保留；早期 data-integration 扩展文档仍有 1280px/32px/27px 的旧页面描述，当前代码和现行系统为全宽/24px/24px。只报告，不修复，也不将历史截图底色或这些差异规范化为新令牌。
