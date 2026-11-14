# 医院 PC 界面资源镜像

本打包路径固定继承已验证、包含 Doris 后端和 Oracle 字符集库的 Integrate 服务端镜像，只替换 /opt/openmetadata/libs/openmetadata-ui-2.0.4.jar。无需编译服务端 Java，不改身份、Secret、数据卷或 SeaTunnel。

要求：主 UI 依赖已安装、Node 可运行 Vite、JDK21 提供 jar、Docker 中已有固定基线镜像。确认源码、测试和生产构建后，在仓库根目录执行：

```powershell
$revision = git rev-parse HEAD
./docker/hospital/build-pc-ui-image.ps1 -Revision $revision -NodeExecutable node -JarExecutable jar
```

默认生成 hospital-openmetadata:2.0.4-integrate-v12-pc。脚本默认重新构建 UI；只有已完成并核对当前源码生产包时才使用 -SkipUiBuild。脚本在.cache/hospital-pc-ui 下创建独立目录，不清理共享依赖。image-manifest.json 记录来源提交、固定基线、UI 归档校验和和镜像 ID；不自动上传或发布。

发布前比对基线和新镜像：服务端 JAR 校验和应保持 25a92ed738a25c9e4757d8ee3955b2622aaa5c7dcd6a5cf21a37f2aa86a4420c，UI 资源 JAR 应改变，orai18n-19.32.0.0.jar 校验和应保持 b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56。上传后使用 Harbor 返回的固定摘要升级 datahub-open-test 中 hospital-openmetadata 部署的 openmetadata 容器，沿用原部署策略和配置。

升级后检查应用可用副本、Integrate 认证配置响应、SPA 入口及静态资源与本地产物一致，并确认未登录的集成 API 仍拒绝访问。实际员工正向门户登录需要授权现场账号，合成 UI 身份不用于线上验收。记录 SeaTunnel 镜像及持久卷 UID，确认没有因界面升级改变。

仅回退本次 UI 镜像时，将同一部署的 openmetadata 容器恢复为：

```text
harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:54e4d883d86b901cb1180a9ace36dbeaa1e465b5a81060cc6974a4843dad6baa
```

不要删除命名空间、Secret、数据库、持久卷或检查点。准确的新摘要和升级结果见 docs/hospital/doris-verification.md。

后续 UI 镜像必须继承此 Doris 基线；已有 Doris 连接定义时，旧的仅支持四种数据库的后端不能读取新类型。服务端和 UI 同时改动时使用 `build-doris-image.ps1`，具体构建方法见 `README.doris.md`。
