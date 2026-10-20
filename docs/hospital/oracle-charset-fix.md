# Oracle 测试连接字符集修复

日期：2026-10-20。HIS Oracle11g 测试数据源的服务名和模式名已正确保存，但平台仍显示通用连接失败。实际应用容器 TCP 连接1521成功，已有 ojdbc8 19.32.0.0，应用与采集引擎均缺少 Oracle NLS 字符集库。

在容器中以已保存的加密连接配置调用与服务相同的 JdbcInspector.open/isValid，首次得到 SQLException 17056、SQLState99999；只在诊断进程类路径补入 orai18n 19.32.0.0 后，同一配置打开连接及 isValid 均成功，确认服务端版本11、驱动主版本19。[Oracle17056说明](https://docs.oracle.com/en/error-help/db/ora-17056/)明确指出需要字符集支持库。

## 构建

服务POM与共用 jdbc-drivers.json 均加入与 JDBC 匹配的 orai18n19.32.0.0，SHA-256为 b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56。官方 Maven Central 下载与 .sha256 校验通过，许可证为 Oracle Free Use Terms and Conditions。正常应用及引擎构建均从共用清单复制驱动，避免后续完整构建遗漏。

本次最小修复使用 docker/hospital/Dockerfile.oracle-nls 的 openmetadata/seatunnel 两个目标，继承文件中指定的当前固定镜像摘要；独立构建上下文仅含该 Dockerfile 和已校验的字符集JAR。构建时传入实际源码 SOURCE_REVISION。应用目标只新增 libs/orai18n-19.32.0.0.jar，引擎目标只新增 lib/orai18n-19.32.0.0.jar。完成后将UI专用构建的固定基础镜像更新为修正后的应用摘要，防止下一次界面发布丢掉字符集支持。

## 验证范围

修复前后只测试既有保存配置，不导出密码、Fernet密钥或JWT，不读取业务表行，不修改源库参数、账号或密码。引擎V2 /running-jobs 返回 data空数组和total0；只有空闲时才升级引擎。发布用 UID/resourceVersion/容器名/旧image 的 JSON Patch test 保护，仅更新测试命名空间的应用及引擎镜像，保留Secret、配置、数据卷及检查点。网络策略保持原状，本轮未启动真实采集任务。

本地忽略目录 .cache/oracle-connection 保存脱敏诊断、校验、部署摘要及受保护的回退补丁；发布后再用同一服务端诊断和真实HTTP检查确认。
