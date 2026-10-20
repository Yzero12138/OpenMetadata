# Doris 应用与引擎镜像

Doris FE/BE 使用官方 4.0.8 的已验证原始 amd64 manifest，经院内 Harbor 转存；清单和启动前置条件见 ../../docs/hospital/doris-deployment.md。平台 PostgreSQL 继续存储元数据，独立 Doris 存储业务数据。

## 构建应用

在仓库根目录、JDK 21/Node/Docker 可用且已完成对应测试后执行：

```powershell
$revision = git rev-parse HEAD
./docker/hospital/build-doris-image.ps1 -Revision $revision -NodeExecutable node -JarExecutable jar -MavenExecutable mvn
```

脚本构建服务端 JAR 和生产 UI，按固定时间打包 UI，固定继承已验证的 Oracle NLS 基线，只替换这两个归档。构建时校验两个归档及 orai18n SHA-256。已经完成当前源码的构建时可传 -SkipBackendBuild 和 -SkipUiBuild。制品与来源记录位于忽略目录 .cache/doris/app-build；脚本不上传或修改 Kubernetes。

仅改 UI 的后续版本使用 build-pc-ui-image.ps1，其基础镜像已提升为本轮 Doris 后端。完整应用更新不能只替换 UI JAR。

## 引擎

seatunnel/Dockerfile.doris 只向当前 JDBC/NLS 基线增加固定哈希的 connector-doris-3.0.0.jar；完整引擎构建脚本也包含该连接器。实际发布摘要见 ../../docs/hospital/doris-verification.md。

## 页面操作

1. 打开“数据集成 → 数据源管理”，已配置的 `Doris 验证目标` 使用集群内 SQL 9030 / HTTP 8030、数据库 ods_validation 和独立 Secret。
2. 需要其他 Doris 时在右侧“新建数据源”抽屉选 Apache Doris、目标角色，填写 SQL 与 HTTP 端口和具备对应库 SELECT/LOAD/CREATE 权限的账号，再测试连接。
3. 在“集成任务”的右侧抽屉选择源表和 Doris 目标，展开“创建目标表”，调整表名、列名及包含列。源主键必须保留。
4. 先预览服务端生成的 DDL，再确认创建；创建后自动选择新表并填入映射。保存/校验后才启动任务。

目标库名和表名合计最多 67 字符，只能包含英文字母、数字和下划线；这是原生连接器扩展标签后的安全边界，超出时会在建表和运行前明确拒绝。第一批建表要求实际单列非空主键；不能安全映射的类型会被拒绝，已有目标不会被覆盖。全量重复同步执行主键更新，不自动删除源端已经消失的历史记录。实时删除需使用经过验证的 CDC 路径。验证库和单 FE/BE 配置用于测试，不构成生产高可用规格。
