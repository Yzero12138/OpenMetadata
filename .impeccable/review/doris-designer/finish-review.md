disposition: fix

## persistence

pass — `PRODUCT.md`、既有 `DESIGN.md` / `.impeccable/design.json`、医院工作台与 PC 导航 brief 均存在。按用户指定的 code-led、Operate、ordinary extension 审查；沿用既有世界，不要求新 comp 或 concept roll。新功能合同为 `docs/hospital/doris-table-designer-design.md`。

实际查看证据目录根部全部 18 张 PNG（9 个场景 × 1440×1080 / 1920×1080），未使用 `before/`。完整视口、场景与文件名相符，无黑屏或缺失截图；表单内部滚动不是截图裁切。核对 `report.json`、`computed.json`、`structure-confirm.json`、`provenance.json` 和既有 detector `[]`，未运行浏览器、服务、集群或第二轮 detector。

本审查仅覆盖所提供 fixture 中的目标表列表、设计/追加字段/结构抽屉及这些 PC 视口；不认证全站导航、真实 SSO、Doris 执行、手机、深色或未捕获的任务抽屉。

## fidelity

| 元素 / 承诺 | 判定 | 证据 |
| --- | --- | --- |
| TYPE | match | 继承中文系统字体、24px 页面标题、18px 抽屉标题和紧凑数据字体；DDL 的等宽字体用于代码。 |
| MATERIAL | match | 原生 core 控件、白色工作区、浅色字段配置区和细表格分隔线；无伪物理材质或新增装饰世界。 |
| GROUND | match | native white / subtle surface 与既有语义色一致；`computed.json` 中字段配置背景为 `#fafafa`，表头背景为白色，未把医院页面底色当作所有组件底色。 |
| 列表入口 | match | 两张 target-list 中标题、目标数据源、搜索、新建目标表和每表三个动作可直接识别；无顶层对象切换 tabs。独立子菜单本轮截图不覆盖。 |
| 右侧编辑抽屉 | match | create/append 均为 1100px、右贴边、全高；1440 时 x=340，1920 时 x=820。标题与 footer 固定，正文滚动，操作未被遮挡。 |
| 字段与参数 | match | 主键、可空、类型及增删/移动/复制动作与展开参数区分开；精度、小数位、字节长度、默认值方式/值和说明有明确中文标签。64 字符字段名保留在原生输入内，展开标题完整且未横溢。 |
| 真实结构只读 | match | existing-structure 与 append-fields 的当前结构使用只读表格；修正后的四项摘要是两列 105px 网格，原列与待新增列分区。 |
| 预览与失败/异步状态 | match | DDL 为只读代码；权限错误给出 CREATE/ALTER 恢复路径并保留草稿；submitted 明确表示等待生效，提供刷新结构，未冒充完成。测量的相关正文对比最低 5.79:1。 |
| 独立新建预览的完成承诺 | contradicted | 两张 create-preview 显示“随后自动选中并应用字段映射”。但 `HospitalTargetTablesPage.tsx:430` 的独立入口完成回调只提示成功、关闭设计器、刷新列表；自动选择与映射只发生在 `IntegrationTaskForm.tsx:843` 的任务上下文。独立设计也没有来源可映射。 |
| 合成数据边界 | match | 所有截图顶端明确标记连接、表和任务为测试夹具；本次无新增栅格素材义务。 |

## ceiling

reached — 限所提供的两个 PC 视口及上述场景。QUALITY BAR 中的入口可找、中文配置、右贴边固定操作区、只读结构、预览/错误和长字段容纳均有对应证据。Operate 的原生层级、细分隔和克制绿色已承担界面组织；无需补装饰、材质或动效。页面横溢出和 pageerror 在提供的测量中均为零。

## material_fixes

1. F1 — truth / copy：在 `DorisTableDesigner.tsx:642` 使用对独立建表和任务内建表都真实的提示，例如“确认后将创建表，不覆盖已有同名表；创建完成后可用于集成任务。”，去掉共享设计器对自动选中/字段映射的无条件承诺；保留任务回调现有行为。一次修改后，以同名 `create-preview-1440.png`、`create-preview-1920.png` 回传，验收提示准确、仍可完整阅读且 footer 未受影响。

## keep

保留既有医院绿色与原生控件、右侧抽屉固定操作区、渐进字段参数、真实结构只读以及“已提交 / 已生效”的明确区分。
