# SQL T+1 测试环境发布（2026-11-12）

## 入口与版本

- 院内入口：[Integrate 应用门户](https://integrate-dev.qcrmyy.local/s/portal) → 数据中台 → 数据集成 → 集成任务。
- 应用地址：`http://172.16.120.211:30925`，继续使用 Integrate 工号统一登录。
- 源码提交：`719ea83d4cce3bdc38c9f2c96a72ae7af442e43f`。
- 新标签：`harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v12-sql-t1-20261112`。
- 部署镜像：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:53a696d608db57de47d8f0e09e6a45376dfe34da4249b168a18d7adca36b4e93`。
- 上一镜像：`harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:54e4d883d86b901cb1180a9ace36dbeaa1e465b5a81060cc6974a4843dad6baa`。

通过 Harbor API 复用原镜像全部 Linux amd64 层，仅追加服务/UI 两个 JAR；构建和发布没有启动本地 Docker。部署前进行了服务端 dry-run，测试环境仅替换应用镜像。

| 运行文件 | SHA-256 |
| --- | --- |
| `openmetadata-service-2.0.4.jar` | `ced21ea4b50965a3fbb7f3ee40ea5122bd2d3c46589a007a99655ad73d142570` |
| `openmetadata-ui-2.0.4.jar` | `7adaef9b72eb5c8662a19803f40354fd60dc650fcb6cae08982a7c8c265800f7` |
| 保留的 `orai18n-19.32.0.0.jar` | `b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56` |

新 Pod 就绪后核对了实际文件摘要。`datahub-open-test` 的 8 个 Deployment/StatefulSet 中只有应用容器镜像发生变化，40 个 ConfigMap/Secret/PVC 引用对象的 UID、资源版本及卷绑定保持一致；迁移镜像、SeaTunnel、身份和检查点配置保持原值。

## 发布后验证

- 14 项 HTTP/认证检查、138 个静态资源字节比对通过；新 `/query/schema` 接口对匿名畸形请求先返回 401。
- 院内 SSO issuer 仍为 `https://integrate-dev.qcrmyy.local`。
- 本地相关监听端口为空，Docker Desktop/后端进程数量为 0。

### 实际 Quartz 定时执行

辅助程序仅加载已发布 `/opt/openmetadata/libs/openmetadata-service-2.0.4.jar`，没有覆盖服务类，没有调用手动运行、调度分发或刷新接口。

- 确认原生 PostgreSQL 集群 Quartz、`HOSPITAL_INTEGRATION` 调度组及 60 秒检查触发器有效。
- 在隔离合成库创建 2 行数据，临时任务 `c4a2e22a-f037-4d0b-807a-9346bea9c7b8` 定时设为当日 `12:02 Asia/Shanghai`。
- 实际定时器以 `system:sql-t1` 提交引擎任务 `3847830608856840549`，业务日期固定为 `2026-11-11`，窗口为 `[2026-11-11 00:00:00, 2026-11-12 00:00:00)`。
- 原生引擎完成读取/写入 `2/2`；下一次真实 Quartz 检查将状态持久化为 `FINISHED`，游标推进至 `2026-11-13 12:02 Asia/Shanghai`，待处理日期和错误清空，历史中只有一次运行。
- 验收后先停用再移除该临时任务，确认原生任务记录数量为 0，源测试表已删除。Doris 写入账号没有 DROP 权限，保留 `ods_validation.sql_t1_qz_2c469c3624cf`，其中两行均为合成数据。

配置方法及语义见 [使用说明](sql-t1-sync.md)，代码测试和其他原生引擎验证见 [验证记录](sql-t1-verification.md)。本轮没有采集真实 HIS 数据。

## 回退注意事项

当前两个 JAR 不涉及数据库结构迁移。新 SQL 任务包含旧版程序不认识的 JSON 字段，Quartz 还会保存新调度类。因此，有真实 SQL 任务后不能直接只替换旧镜像。

回退前应停止新增 SQL 任务、停用定时执行、确认引擎没有活动任务，备份相关任务 JSON 和原生 Quartz 调度组状态，再将 SQL 任务从旧版扫描范围安全迁出并暂停/移除该调度组。所有用户任务和运行历史必须保留，禁止通过清空元数据库解决兼容问题；最后以固定的上一镜像恢复应用。没有新 SQL 记录时也应先核对并处理该 Quartz 调度组。现有 Secret、PVC、Oracle NLS 和引擎检查点无需回退。
