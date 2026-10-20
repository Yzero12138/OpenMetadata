# Doris test node prerequisite

Doris4.0.8 startup requires vm.max_map_count>=2000000. All inspected workers reported65530. The authorized test deployment selects k8s-worker01 only, with dedicated DorisFE/BE volumes. The one-off namespace Job in deploy/hospital/k8s/doris-node-prerequisite.yaml raises only this parameter on that node, verifies its previous value is65530 or already2000000, uses no host mounts, API token, network calls or application restart, and exits. Privileged access is necessary to write this non-namespaced kernel parameter; the completed helper is removed after evidence capture. The new limit does not preallocate memory. It is a volatile runtime setting and must be checked after node restart.

Before mutation, .cache/doris/before.json records38 existing workloads, ConfigMap/policy hashes and10PVC identities; node storage evidence records approximately189GiB available. No source database or production deployment is part of this operation.

Rollback: first stop the new Doris instances so their mapping requirement no longer applies. Execute the same bounded helper on k8s-worker01 with the write value65530, guarded by current value2000000; confirm65530 and remove the helper. Never delete DorisPVCs as part of node rollback. Do not lower this limit while Doris is running.
