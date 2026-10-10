# 数据中台名称与图标调整

日期：2026-10-10。用户指定将侧栏左上角名称改为“数据中台”，并授权设计与替换图标。本次是已交付 PC 工作区的品牌细化。

## 实现

- 侧栏默认字标改为“数据中台”，20 种界面语言保持同一正式产品名称。
- 新增原创几何 SVG：三个输入节点汇入分层底座，24px 画布、1.7px 圆端线条，置于既有 32px 绿色标记中。菜单收起后保留图标及可访问名称。
- 图标使用现有白色前景语义令牌，并补齐前缀令牌和原生白色回退；避免未定义的非前缀变量使图标继承深色文字。字体、导航布局及现有自定义 Logo 优先级沿用现状。
- Integrate 仍为院内身份门户名称；本次修改侧栏产品标识，不改变登录协议、应用代码或权限。

## 验证

- 已有侧栏及 BrandImage 两套回归共 19 项通过。Windows 工作树目录下，Jest 原始包含 rootDir 的匹配式无法发现文件；本次命令显式使用跨平台 testMatch，并运行指定的两套原测试。
- 实际 React 侧栏已检查 1440×1000、1920×1080、1093×720，以及菜单收起和深色主题共五种状态；图标加载、尺寸、无页面横向溢出、键盘焦点及返回工作台链接通过。
- 字标对比度为浅色 13.50:1、深色 15.73:1；图标白线与绿底为 7.21:1。主题切换按真实 ThemeProvider 在 html 根节点加类，并等待既有颜色过渡结束后采样。
- 五张完整截图与一张品牌局部图均已打开确认；本次品牌目标仅运行一次 Impeccable 检测，结果为空、退出码 0。
- 真实界面组件使用明确的合成 HTTP 及界面身份；截图不代表真实员工登录或业务库采集。现有浏览器连接工具本轮不可用，发布核验使用 HTTP 资源和 K8s 状态。

本机证据位于忽略目录 `.impeccable/review/data-platform-brand`：report.json、五种状态 PNG、brand.png、jest.json/jest.txt、detector.json/detector.txt 及 production-build.txt。品牌设计与来源已同步到 PRODUCT.md、DESIGN.md、.impeccable/design.json 和对应 surface brief。

## 生产构建与发布

生产 Vite 构建通过，退出码 0（built in 5m 35s）。新镜像使用独立标签 `hospital-openmetadata:2.0.4-integrate-v8-brand`，打包路径继续固定继承已验证的服务端镜像，仅替换 UI JAR。

源码对应提交为 `be4c20934f012d3a2cfa15bd29167e07970aebb8`，已推送到用户 fork 的 `codex/hospital-pc-ui-phase1` 分支并包含在 [PR #2](https://github.com/Yzero12138/OpenMetadata/pull/2)。

- 已上传并实际部署：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:38968329cc43724b19c2c79ad84e48b16e0316760f6d0e42fd2f429dd711750e`。
- 资源标签：`harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v8-brand`；UI JAR SHA-256：`498e8b4001ae4c6f0fb31411b3bfdf369c4b59be0000ff4ccdba5a276cbc2a71`。本地归档、镜像文件和在线 Pod 文件一致。服务端 JAR 保持 `97c22e4d7729a6df5bb7901d0033b5414a5e849677fe47f41b3e4d7502acfdcb`。
- `datahub-open-test/hospital-openmetadata` rollout 成功，可用副本为 1。首页及 SPA 路径按既有服务端路径/nonce/换行规则精确匹配，138 个静态资源通过 HTTP 逐字节校验。
- Integrate 认证配置及系统版本返回 200，连接、任务和引擎状态接口在未登录时均返回 401。部署 UID、环境变量名称及引用、挂载保持一致；SeaTunnel UID/镜像与 10 个 PVC 的 UID/绑定卷/状态相同。

从 [Integrate 门户](https://integrate-dev.qcrmyy.local/s/portal)进入应用后，刷新已打开页面可加载新标识。本次发布没有使用合成身份访问线上接口。

发布证据包括 image-manifest.json、image-verification.json、release-image.json、release-verification.json、deployment-http.json、deployment-before/after.json、image-jar-sha256.txt、live-jar-sha256.txt、engine-before/after.txt、volumes-before/after.txt 及 harbor-push.txt。

## 回退

本次品牌发布的回退点是此前 PC 工作区版本：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:839e43e99a74c7e475cd5b4daab708f9720080dec2c73d4951ef72bfe9c3b4db`。只恢复同一部署的 openmetadata 容器镜像并等待 rollout，保留现有身份、Secret、数据库、数据卷、引擎及检查点。
