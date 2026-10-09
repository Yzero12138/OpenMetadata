# Integrate 测试认证通道变更提案

实际部署的 OpenMetadata 已就绪，但从其 Pod 访问
`platform.coop-dev.svc.cluster.local:8081` 会连接超时，票据交换因此返回 503。
`coop-dev` 现有 `datahub-sso-to-platform` 网络策略只接受
`datahub-open-test` 内标签为 `app=controlplane` 的旧应用。
本次 OpenMetadata 使用 `app.kubernetes.io/name=hospital-openmetadata`，不满足该规则。

用户已在本任务中明确批准，并已在 **coop-dev 测试命名空间**应用一条独立 NetworkPolicy，不修改已有策略：

| 范围 | 已批准的允许值 |
|---|---|
| 策略名 | `hospital-openmetadata-to-platform` |
| 目标 Pod | `app=platform` |
| 来源命名空间 | `kubernetes.io/metadata.name=datahub-open-test` |
| 来源 Pod | `app.kubernetes.io/name=hospital-openmetadata` |
| 目标端口 | TCP 8081 |
| 方向 | Ingress |

命名空间和来源 Pod 条件必须在同一个 `from` 条目中，按 AND 匹配。
这会为新治理服务增加对共享测试平台 8081 端口的连通权限；网络策略无法按 HTTP 路径限制。
Integrate 的 Basic 客户端验证、一次性票据校验和人员授权仍在应用层执行。
不增加其他来源，不涉及生产命名空间。

回退方法是删除这条新增策略，已有策略和平台工作负载不变。
该变更最初因共享服务网络边界的授权不明确被自动审批拒绝；用户随后明确批准了上述限定范围。
2026-10-09 应用后，Pod 到平台的 TCP 连接检查通过，真实后端票据交换对不存在的票据返回 401，
没有再出现连通超时导致的 503。完整部署清单的 Kubernetes 服务端校验通过。
