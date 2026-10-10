# 医院 PC 界面资源镜像

本打包路径固定继承已验证的 Integrate 服务端镜像，只替换 /opt/openmetadata/libs/openmetadata-ui-2.0.4.jar。无需编译服务端 Java，不改身份、Secret、数据卷或 SeaTunnel。

要求：主 UI 依赖已安装、Node 可运行 Vite、JDK21 提供 jar、Docker 中已有固定基线镜像。确认源码、测试和生产构建后，在仓库根目录执行：

```powershell
$revision = git rev-parse HEAD
./docker/hospital/build-pc-ui-image.ps1 -Revision $revision -NodeExecutable node -JarExecutable jar
```

默认生成 hospital-openmetadata:2.0.4-integrate-v7-pc。脚本默认重新构建 UI；只有已完成并核对当前源码生产包时才使用 -SkipUiBuild。脚本在.cache/hospital-pc-ui 下创建独立目录，不清理共享依赖。image-manifest.json 记录来源提交、固定基线、UI 归档校验和和镜像 ID；不自动上传或发布。

发布前比对基线和新镜像：服务端 JAR 校验和应保持 97c22e4d7729a6df5bb7901d0033b5414a5e849677fe47f41b3e4d7502acfdcb，UI 资源 JAR 应改变。上传后使用 Harbor 返回的固定摘要升级 datahub-open-test 中 hospital-openmetadata 部署的 openmetadata 容器，沿用原部署策略和配置。

升级后检查应用可用副本、Integrate 认证配置响应、SPA 入口及静态资源与本地产物一致，并确认未登录的集成 API 仍拒绝访问。实际员工正向门户登录需要授权现场账号，合成 UI 身份不用于线上验收。记录 SeaTunnel 镜像及持久卷 UID，确认没有因界面升级改变。

仅回退本次 UI 镜像时，将同一部署的 openmetadata 容器恢复为：

```text
harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:934be97369255e2b08bd803fb719b0de0112ff8ba0c739e90ee3d2212f41a473
```

不要删除命名空间、Secret、数据库、持久卷或检查点。准确的新摘要和升级结果见 docs/hospital/ui-redesign-phase1-verification.md。
