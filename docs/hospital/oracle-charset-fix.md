# Oracle 测试连接字符集修复

日期：2026-10-20。HIS Oracle11g 测试数据源的服务名和模式名已正确保存，但平台仍显示通用连接失败。实际应用容器 TCP 连接1521成功，已有 ojdbc8 19.32.0.0，应用与采集引擎均缺少 Oracle NLS 字符集库。

在容器中以已保存的加密连接配置调用与服务相同的 JdbcInspector.open/isValid，首次得到 SQLException 17056、SQLState99999；只在诊断进程类路径补入 orai18n 19.32.0.0 后，同一配置打开连接及 isValid 均成功，确认服务端版本11、驱动主版本19。[Oracle17056说明](https://docs.oracle.com/en/error-help/db/ora-17056/)明确指出需要字符集支持库。

## 构建

服务POM与共用 jdbc-drivers.json 均加入与 JDBC 匹配的 orai18n19.32.0.0，SHA-256为 b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56。官方 Maven Central 下载与 .sha256 校验通过，许可证为 Oracle Free Use Terms and Conditions。正常应用及引擎构建均从共用清单复制驱动，避免后续完整构建遗漏。

本次最小修复使用 docker/hospital/Dockerfile.oracle-nls 的 openmetadata/seatunnel 两个目标，继承文件中指定的当前固定镜像摘要；独立构建上下文仅含该 Dockerfile 和已校验的字符集JAR。构建时传入实际源码 SOURCE_REVISION。应用目标只新增 libs/orai18n-19.32.0.0.jar，引擎目标只新增 lib/orai18n-19.32.0.0.jar。完成后将UI专用构建的固定基础镜像更新为修正后的应用摘要，防止下一次界面发布丢掉字符集支持。

## 验证范围

修复前后只测试既有保存配置，不导出密码、Fernet密钥或JWT，不读取业务表行，不修改源库参数、账号或密码。引擎V2 /running-jobs 返回 data空数组和total0；只有空闲时才升级引擎。发布用 UID/resourceVersion/容器名/旧image 的 JSON Patch test 保护，仅更新测试命名空间的应用及引擎镜像，保留Secret、配置、数据卷及检查点。网络策略保持原状，本轮未启动真实采集任务。

本地忽略目录 .cache/oracle-connection 保存脱敏诊断、校验、部署摘要及受保护的回退补丁；发布后已用同一服务端诊断和真实HTTP检查确认。

## 发布结果

2026-10-20 15:46（北京时间）验证通过，测试环境固定镜像：

| 运行容器 | 新镜像摘要 |
| --- | --- |
| hospital-openmetadata/openmetadata | `harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:287323f97a2643a6c009f977eca3d438a83dcde0fa6c28b922c09287fcb1a2e1` |
| hospital-seatunnel/seatunnel | `harbor.qcrmyy.local/coop/hospital-seatunnel@sha256:ec98ba379aa824bc897ead8fbb861b0b5702ef53233ceda3b26451e3896212a7` |

两个镜像均由源码提交 `0125c7f9` 构建，并已上传院内 Harbor。完整比对原镜像应用目录的924个文件及引擎目录的230个文件，原文件内容全部保持一致，分别只新增一个校验通过的 NLS JAR。

新应用容器仅使用镜像内的 `/opt/openmetadata/libs/*` 类路径，对原有 HIS 测试库保存配置验证 `oracle_open=true`、`oracle_valid=true`，不依赖临时补入的字符集JAR。Oracle服务器主版本11、驱动主版本19。该验证执行与页面“测试连接”相同的连接打开和有效性检查；本轮未使用或模拟员工登录态调用受保护API。

两个运行容器均就绪且重启次数0，SeaTunnel版本3.0.0、运行任务0。跨 coop-dev、coop-prod、datahub-open-test 比对38个工作负载，仅测试应用及引擎的运行容器镜像改变，其余36个工作负载、原初始化容器、10个PVC的UID与绑定均未改变。原服务端JAR和UI归档SHA-256保持不变。

真实首页、登录页和数据源路由的入口与既有构建完全一致，活动资源仍为 `index-DnMCRwMe.js`，两个端口标签均为“端口”。Integrate认证配置仍启用，匿名集成API和无效票据均返回401；门户设备访问状态仍为 `enforced=false/NOT_ENFORCED`，正常账号鉴权仍生效。

持久化修复同时覆盖服务POM、共享驱动清单、两个最小修复镜像、UI专用构建基线及其产物记录，以及K8s源模板。模板中初始化与运行镜像统一固定到新摘要；实际发布使用仅运行镜像的受保护JSON Patch，未重新应用完整模板。后续只发布UI时仍会继承字符集支持。POM/XML、驱动JSON、PowerShell语法、K8s客户端解析及所有固定摘要检查通过，最终只读复审无P0/P1/P2问题。
