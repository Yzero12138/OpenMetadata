# 医院数据治理第一阶段

基线：OpenMetadata `2.0.4-release`。Fork：<https://github.com/Yzero12138/OpenMetadata>。

## 工号统一登录

员工在 **Integrate** 使用工号、密码登录，在 `/s/portal` 应用门户打开 `datahub` 应用。
OpenMetadata 不接收工号或密码，不展示邮箱密码表单，也不提供原生注册、找回密码入口。
直接访问治理平台时，入口页提供返回 Integrate 门户的链接。

现有 Integrate 源码 `DatahubSsoService`、`DatahubSsoController` 和 `frontend/src/modules/portal/launch.ts`
已经定义一次性票据协议。已核对 `coop-dev` 中的测试部署启用了这套 SSO 配置，
并核对双方现有的 `integrate-datahub-sso` Secret 一致；共享凭据未写入本仓库。

### 配置对照

先构建本分支的服务和 UI，再使用 [医院镜像构建脚本](../../docker/hospital/build-image.ps1)
打包。服务端参数使用 [Kubernetes ConfigMap](../../deploy/hospital/k8s/configmap.yaml)，
凭据通过 Secret 注入。**上游原版镜像不包含本分支的适配代码。**

| Integrate 配置 | OpenMetadata 环境变量 | 要求 |
|---|---|---|
| `coop.portal.datahub-sso.enabled` | `INTEGRATE_SSO_ENABLED` | 双方启用 |
| `coop.portal.datahub-sso.issuer` | `INTEGRATE_SSO_ISSUER` | 相同的 Integrate HTTPS origin |
| `coop.portal.datahub-sso.target-origin` | `INTEGRATE_SSO_TARGET_ORIGIN` | 治理平台 HTTPS origin |
| `coop.portal.datahub-sso.client-id` | `INTEGRATE_SSO_CLIENT_ID` | 相同的客户端标识 |
| `coop.portal.datahub-sso.client-secret` | `INTEGRATE_SSO_CLIENT_SECRET` | 相同的随机密钥，至少 32 字符，仅后端持有 |
| `coop.portal.datahub-sso.allow-http-test` | `INTEGRATE_SSO_ALLOW_HTTP_TEST` | 仅 HTTP 测试环境显式启用 |
| 无需改门户 origin | `INTEGRATE_SSO_BACKCHANNEL_ORIGIN` | 可选后端地址，默认 issuer；不返回给浏览器 |

Origin 必须是完整协议、主机和可选端口，不能含路径或尾部 `/`。
例如 `https://integrate.example.hospital` 与 `https://metadata.example.hospital`。
应用 launch URL 固定为 `https://metadata.example.hospital/?integrate_connect=1`。
给员工分配 Integrate 的 `app:datahub` 权限并启用此应用；不需要再创建一组员工密码。

OpenMetadata 通过后端认证地址访问 Integrate 的 exchange/introspect 接口，返回的 issuer
仍须精确等于公开门户 origin。测试门户的网关要求员工设备证书，因此服务间通道使用
`platform.coop-dev.svc.cluster.local:8081`；Basic 客户端校验、票据 challenge 和会话核验全部保留。
反向代理应保留浏览器 `Origin`，不缓存认证响应，允许这两个不同 origin 之间保留弹窗的 opener。
不要为这条门户启动链配置 `Cross-Origin-Opener-Policy: same-origin` 或 `noopener`，否则浏览器会断开握手。
无须放开跨域 CORS；浏览器只调用治理平台自己的接口。

### 会话与权限

1. 新窗口生成随机 challenge，只接收来自精确门户 origin、原始 opener 且 challenge 一致的票据。
2. 治理平台后端用 Basic 客户端凭据调用 Integrate 的 `/api/platform/portal/datahub/exchange`。
3. 后端核验 active、issuer、audience、稳定 subject 和有效期，创建绑定 Integrate 的本地会话。
4. 人员身份以 issuer + subject 的 SHA-256 映射，显示名来自 Integrate。
   用户名保留 `integrate_<完整 SHA-256>`；数据库内部邮箱使用 `<完整 SHA-256>@integrate.invalid`，
   `@` 前固定为 64 个字符。JWT 独立读取 username 和 email，保持账号标识与会话关联。
   内部邮箱由服务端生成，员工仍通过 Integrate 的工号和密码登录。
5. 新员工默认非管理员。已有员工的本地角色不会因登录被覆盖；治理管理员分配数据权限和角色。
6. 每次人员 API 访问、刷新及 WebSocket 会话检查，后端都向 Integrate 核验会话。
   门户退出、用户禁用、应用权限撤回或会话到期后，旧会话拒绝访问。
   身份服务不可用时采用拒绝访问策略；恢复后从门户重新进入。
7. 治理平台“退出”撤销治理平台会话；不擅自退出门户中的其他应用。

升级会修复早期版本生成的内部邮箱：原用户名的 `integrate_` 前缀使邮箱本地部分达到 74 字符，
超过原生 `@Email` 校验允许的 64 字符。服务启动、票据交换和刷新使用一致的邮箱映射。
迁移仅允许启用 Integrate 时，将同一 UUID、同一稳定用户名的精确旧邮箱转换为正确内部邮箱；
机器人、已删除账号、自定义邮箱和非管理员执行的更新不适用。用户名、UUID、管理员权限及角色关系保留。
升级后应从 Integrate 门户重新进入，让新的访问令牌携带修正后的邮箱。

浏览器只保留原生服务生成的访问令牌；共享密钥与 Integrate session_token 不进入前端、URL 或浏览器存储。
Integrate session_token 通过 OpenMetadata 现有 Fernet 机制加密后保存。
保留上游 JWT 签名密钥、Fernet 密钥和管理员/采集机器人配置；这些密钥应由院内密钥管理提供。
机器人服务令牌继续遵循上游权限机制，不以员工账户代替采集机器人。

### 验收路径

- 工号密码登录 Integrate → 应用门户 → 数据治理 → 自动进入中文工作台。
- 直接访问 `/signin` → 只有门户链接，没有邮箱或密码字段。
- 无 `app:datahub` 权限 → 门户拒绝启动。
- 错误 origin、错误 challenge、票据重放 → 登录拒绝。
- Integrate 退出或撤销应用权限 → 下一次治理 API 请求拒绝。
- 治理平台退出 → 子会话撤销；页面返回门户入口。
- 新员工登录后不能自动获得管理员权限。

## 界面

使用默认 `design-taste-frontend` 与完整 Impeccable **4.5.1** 文件。
院内工作台沿用 Integrate 绿色体系，保留上游核心组件和数据治理功能。
首页数据表、业务词汇表、质量规则、数据库服务数量来自真实接口；无权限或异常显示“不可用”，不伪造统计。
资产搜索进入原生目录，资产打开原生详情，术语与质量整改入口进入上游治理模块。
新增医院文案有中文、英文两套资源；其他语言下使用现有回退规则。

## 构建和测试

主项目构建遵循上游 Java 21 / Node 22+ / Yarn 1.22 流程。
完整源码发布包可使用根目录 Maven 的原生 distribution 流程。
本轮镜像在官方同版本发行包中替换两个改造 JAR，其他运行依赖保持该版本：

```powershell
# 先完成 UI 的 schema/grammar 生成、核心组件构建和 yarn vite build。
# Java 依赖模块已安装后：
mvn -pl openmetadata-service install -DskipTests -DonlyBackend
# UI 生产资源已生成后，仅打包现有资源，不重复触发前端构建：
mvn -pl openmetadata-ui resources:resources jar:jar install:install
./docker/hospital/build-image.ps1 -Push
```

快速外部身份协议测试：

```sh
mvn -f tools/hospital-contract-tests/pom.xml clean test
```

相关服务回归测试（依赖模块先按上游流程安装）：

```sh
mvn -pl openmetadata-service -am test -DonlyBackend \
  -Dtest=IntegrateIdentityClientTest,SessionServiceTest,JwtFilterTest,UserUtilTest,SocketAddressFilterTest,UserRepositoryUnitTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

相关 UI 测试在 `openmetadata-ui/src/main/resources/ui`：

```sh
yarn test src/utils/IntegrateSso.test.ts src/pages/LoginPage/SignInPage.test.tsx \
  src/pages/MyDataPage/HospitalWorkbench.test.tsx --runInBand
```

## Kubernetes 测试部署

命名空间 `datahub-open-test`；沿用门户已登记的测试地址 `http://172.16.120.211:30925`。
登录入口仍为 `https://integrate-dev.qcrmyy.local/s/portal`。
这个 HTTP 地址仅供当前测试：切换 TLS 时须同时调整门户 target-origin、治理平台 target-origin、
HTTP 测试开关和 Secure Cookie 设置。

- `hospital-openmetadata-config`：非敏感服务参数与身份服务地址。
- `hospital-openmetadata-runtime`：随机数据库密码和 Fernet 密钥，只在集群内生成。
- `integrate-datahub-sso`：已有共享 Secret，通过 `secretKeyRef` 注入客户端密钥。
- `openmetadata-jwt`：已有签名 Secret，只读挂载。
- `hospital-metadata-postgres` / `hospital-metadata-search`：PostgreSQL 17.10 与 OpenSearch 3.3.0，
  使用新的 PVC，不挂载旧平台的卷。版本符合 [OpenMetadata 2.0 部署要求](https://docs.open-metadata.org/v2.0.x/deployment/kubernetes/on-prem)。
- `hospital-openmetadata`：本分支改造镜像、迁移 initContainer、启动和就绪探针。
- `coop-dev/hospital-openmetadata-to-platform`：经用户批准，只允许该治理服务 Pod 访问
  测试身份服务的 TCP 8081；[范围及回退说明](integrate-network-access-proposal.md)。

```powershell
./deploy/hospital/k8s/provision-runtime.ps1
kubectl apply -k deploy/hospital/k8s
kubectl rollout status deployment/hospital-openmetadata -n datahub-open-test
```

重新应用清单会保留现有 Secret；不能通过重新生成 Fernet 密钥来更新配置。
ConfigMap 更新后，使用 `kubectl rollout restart deployment/hospital-openmetadata -n datahub-open-test` 生效。
本阶段不启用 Airflow；后续按实际采集需求配置采集机器人与管道服务。

首个治理管理员已由用户明确指定，并根据已核对的 Integrate 稳定 subject 配置。
`AUTHORIZER_ADMIN_PRINCIPALS` 通过现有 runtime Secret 注入，启动时使用原生管理员初始化机制；
不在源码中保存真实人员映射。其他首次登录的员工默认不会升为管理员。
部署到其他环境时，先核对人员工号对应的稳定 subject，再运行
`./deploy/hospital/k8s/configure-initial-admin.ps1 -Subject '<verified-subject>'` 并滚动启动。
这个脚本仅配置首个管理员；后续人员治理角色通过平台管理，撤权也应显式执行。
完整工号登录验收必须在已获得院内设备授权的浏览器中进行；界面截图使用的是独立的合成元数据验证工具。
本阶段没有连接真实 HIS/LIS、没有加载患者数据；后续采集配置应从测试数据源开始。

## 治理界面修复验证记录（2026-10-09，v4）

本轮完成并部署到已授权测试环境：

- 恢复管理员的工作流新建入口与可视化编辑。配置前保持本地草稿，首次保存调用原生创建接口；同名冲突保留画布，可修改技术名称后重试，并防止重复提交。
- 域类型在创建、编辑、详情、列表及筛选中显示“聚合、消费对齐、源对齐”，保留原生 API 枚举值。
- 内置工作流默认标题、说明、节点、任务表单与相关治理界面使用本地化资源；用户自定义元数据保留原样。补齐 214 条已有中文资源并修复 27 处插值或标记问题，新增键同步到全部 20 个语言文件。
- 永久移除 GitHub 点星弹窗的注册入口；窄屏编辑器操作、节点栏收纳及分页整改经独立复核全部解决。

运行版本为 `2.0.4-integrate-v4`，应用清单中的启动与迁移容器使用同一版本。镜像已构建推送，实际 Pod 镜像摘要核对一致，迁移退出码为 0，应用 rollout 成功。现有 Integrate 会话与管理员配置继续保留。

| 验证范围 | 结果 |
|---|---|
| 治理相关回归 | 13 个套件、85 项通过；响应式修改补跑 3 个套件、15 项通过 |
| UI checkstyle | 74 个源码与资源文件通过，按导入整理、ESLint、Prettier 顺序执行 |
| 生产 UI 构建与 JAR 打包 | 通过；包内入口、中文和工作流资源与最终构建一致 |
| 本地实际组件验证 | 8 张桌面/390px 截图有效、无页面错误；独立设计复核 disposition 为 ship，范围见设计记录 |
| K8s 服务端清单校验与实际更新 | 通过；仅应用治理服务清单进行此次更新 |
| 真实部署的门户与认证保护 | 17 项通过，含桌面和窄屏入口；真实 Integrate 后端拒绝不存在的票据 |
| 身份与权限保留 | 7 项只读检查通过，原 UUID、用户名、管理员权限、角色、团队和关系保留 |
| 线上发布资源 | 5 个 JS/CSS 文件 SHA-256 一致；首页经原生路径、随机 nonce 与换行转换后模板精确一致 |
| 全量 TypeScript | 仍有原有 550 条诊断，按文件与诊断类型比较无新增；全库类型检查未通过 |

桌面合成边界验证覆盖配置连线、首次创建冲突、改名和成功重试；390px 验证覆盖表单、顶部操作、节点栏和开始配置，完整图编辑验收限桌面。这些检查不替代授权设备上真实员工的门户登录、退出、撤权或真实医院工作流写入验收。

详见[实施记录](governance-chinese-plan.md)与[设计验证记录](governance-design-verification.md)。

## 上一版登录修复验证记录（2026-10-09，v3）

测试部署已实际应用：三个服务 Pod 均为 `1/1 Running`，数据库迁移成功，治理平台 rollout 成功。
运行镜像为 `harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v3`，摘要：
`sha256:ecd8dc5f426430db9ad8e10b39037360d8e16436477f7df66d5727bd2f4fd9bf`。

| 验证范围 | 结果 |
|---|---|
| 服务端构建、Spotless apply/check | 通过 |
| 身份协议、JWT、持久会话、用户工具、Socket 与真实用户更新器回归 | 6 个测试套件，134 项通过，无失败或跳过 |
| 邮箱修复与测试环境隔离 | 原生邮箱校验、JWT 身份解析、真实 PATCH/PUT 更新先复现失败再通过；最后补跑 11 项身份测试通过 |
| 登录、握手、工作台、认证、路由及旧首页回归 | 7 个测试套件，69 项通过 |
| Integrate 到期与组件卸载回归 | 新增 9 项（包含在上述 69 项中），先复现失败后通过；独立代码复核发现均已解决 |
| UI 生产构建与服务/UI JAR 打包 | 通过 |
| 完整 K8s 清单服务端校验 | 通过，Secret 不含在清单中 |
| 真实部署验收 | 17 项通过，含桌面 1440px、移动 390px，浏览器运行异常为 0 |
| 首个治理管理员 | 已核对指定人员的 Integrate subject、启用状态和门户权限；启动后只读查询确认对应本地身份 isAdmin=true、非机器人，内部邮箱匹配 |
| 账号迁移与冷启动 | 升级后 7 项只读检查通过；再重启一次，7 项检查和 17 项真实部署检查仍全部通过，原身份、管理员权限及角色关系保留 |
| 设计核验 | 10 张桌面/移动/明暗截图，检测器无发现；独立最终复核 verdict 为 ship |
| 全量 TypeScript 检查 | 未通过：基线与本分支均为 550 条诊断，无新增文件/诊断类型；不能视为全库类型检查通过 |

真实部署验收确认公开配置只返回 enabled、issuer、portalUrl；8 个原生人员密码/注册/重置接口
以及原生 auth/login 返回 403；错误或缺失 Origin 拒绝，畸形票据返回 400。
符合格式但不存在的票据经真实 Integrate 内网接口核验后返回 401。
真实入口页面只显示门户链接，没有邮箱或密码输入，手机与桌面没有横向溢出。

Integrate 会话最后 60 秒按 JWT 实际到期时间安排一次刷新，避免固定源会话到期时间
触发多标签互相立即刷新。跨标签通知只保留一个计时器；组件卸载后，尚未完成的令牌读取
及旧回调均不会重新创建计时器。仍保留超过缓冲期时的提前刷新及过期令牌的正常处理。

复现部署验收：

```powershell
# Chromium 可通过 HOSPITAL_QA_CHROMIUM 指向本机 Playwright 浏览器。
node tools/hospital-ui-preview/verify-deployment.cjs
```

合成界面预览与真实部署验收是不同范围。尚未完成授权设备上的真实员工登录、退出和权限撤回验收；
首个本地治理管理员已经指定并配置。以上结果不等同于完成这些人员流程或接入医院业务数据。
