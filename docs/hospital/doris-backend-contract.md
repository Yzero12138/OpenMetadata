# Doris target backend contract

Doris connections use `databaseType: "Doris"` and `role: "TARGET"`. The SQL port and required `dorisHttpPort` are independent. `schemas` must contain exactly the configured database. The first isolated deployment supports `tlsMode: "DISABLED"`; `VERIFY` fails closed. Other database families reject `dorisHttpPort`.

Connection responses omit passwords. Testing a Doris connection requires both JDBC validation and FE `GET /api/health`: no credentials, no redirects, 2-second connect timeout, a 3-second total HTTP deadline, and a 4 KiB response limit. Success requires HTTP 200 and FE JSON `code: 0`, `msg: "success"`. This verifies the SQL/HTTP endpoints; it does not prove load privileges or backend capacity.

## Managed validation connection

When all optional Doris settings are valid, the service exposes a third immutable, synthetic connection:

| Setting | Value or environment variable |
| --- | --- |
| ID | `doris-ods-validation` |
| Display name | `Doris 验证目标` |
| Type / role / version | `Doris` / `TARGET` / `4.0.8` |
| Supported modes | `FULL` |
| Enable | `HOSPITAL_DORIS_ENABLED=true` |
| FE host | `HOSPITAL_DORIS_HOST` |
| SQL / HTTP ports | `HOSPITAL_DORIS_SQL_PORT` / `HOSPITAL_DORIS_HTTP_PORT` |
| Database | `HOSPITAL_DORIS_DATABASE` |
| User / password | `HOSPITAL_DORIS_USER` / `HOSPITAL_DORIS_PASSWORD` |

The deployment uses database `ods_validation`. Missing, disabled, or incomplete optional Doris settings leave the existing two PostgreSQL seeds available. No connection record is inserted into the metadata database for this managed seed.

## Preview and create

Both routes require native administrator authorization before reading the bounded JSON body. Unknown properties, SQL/DDL properties, duplicate JSON keys, trailing documents, and scalar coercions are rejected.

`POST /v1/hospital/integration/connections/{targetId}/tables/preview` returns HTTP 200:

```json
{
  "sourceConnectionId": "synthetic-source",
  "sourceSchema": "public",
  "sourceTable": "visits",
  "targetSchema": "ods_validation",
  "targetTable": "new_visits",
  "fieldMappings": [
    {"source": "id", "target": "visit_id"},
    {"source": "patient_name", "target": "patient_name"}
  ]
}
```

The response contains `targetConnectionId`, `previewHash`, generated `ddl`, the proposed `table`, and `fieldMappings`. The source table is inspected live. It must have one actual, nonnullable primary key, which must be mapped. There must be 1–128 mappings with unique source and target names; target duplicates are checked without case distinctions. The target database must be in scope and must equal its connection database.

`POST /v1/hospital/integration/connections/{targetId}/tables` accepts the same body with the returned `previewHash`, and returns the created `TableDefinition` with HTTP 201. It recomputes the preview from current metadata before executing DDL. The SHA-256 hash covers canonical generated DDL, both connection versions and nonsecret endpoint properties, all source columns including hidden JDBC precision/scale and capability metadata, and selected mappings.

The executor checks for existing target tables or views, then executes one server-generated `CREATE TABLE`. It uses a 5-second existence-query timeout and 15-second DDL timeout. Concurrent table creation also returns a conflict. It never accepts caller SQL, overwrites a table, or issues `ALTER`, `DROP`, or `TRUNCATE`.

## Supported generated types

| Source | Doris target / restrictions |
| --- | --- |
| Signed integer JDBC types | `BIGINT`; unsigned `BIGINT` is rejected |
| Decimal / numeric | `DECIMAL(p,s)`, `1 <= p <= 38`, `0 <= s <= p` |
| Bounded character types | `VARCHAR(4 * sourceLength)`; length 1–16383 |
| Boolean / one-bit value | `BOOLEAN` |
| Date | `DATEV2`; ambiguous Oracle `DATE` reported as JDBC DATE is rejected |
| Local timestamp | `DATETIMEV2(s)`, scale 0–6; timezone-bearing type names are rejected |

Unbounded strings, floating-point types, time-only values, timezone-bearing timestamps, binary/LOB/complex types, and unsupported or unknown precision are rejected. UTF-8 targets reserve four bytes per source character; inspection exposes a conservative character capacity of `varcharBytes / 4` to mapping validation.

Generated tables use `ENGINE=OLAP`, a single `UNIQUE KEY` as the first column, merge-on-write, and hash distribution on that key. `HOSPITAL_DORIS_REPLICATION_NUM` accepts 1–9, defaults to 3, and is set to 1 for the isolated single-BE validation deployment. Logical primary keys are read from safe `SHOW CREATE TABLE` plus `DESCRIBE`, without relying on JDBC `getPrimaryKeys`; duplicate/aggregate model keys are not reported as primary keys.

## Errors

| Code | HTTP |
| --- | --- |
| `TABLE_ALREADY_EXISTS` | 409 |
| `TABLE_PREVIEW_STALE` | 409 |
| `UNSUPPORTED_TARGET_TYPE` | 400 |
| `TARGET_TABLE_CREATE_FAILED` | 503 |

Existing configuration, role, scope, missing-table, connection, and native authorization errors also apply. Error messages do not include credentials, SQL exceptions, or server response bodies.

## SeaTunnel and catalog

Doris targets use the native `Doris` sink: `fenodes` is FE host plus HTTP port, `query-port` is the SQL port, and `database`/`table` use the target names. JSON stream load sets `read_json_by_line`, strict mode, and zero filter tolerance. Schema handling is `IGNORE` and data handling is `APPEND_DATA`; table creation is controlled by the preview/create API.

`sink.enable-2pc=false`; there is no exactly-once claim. Unique Key merge-on-write provides idempotent upserts. A fresh job ID changes the label prefix, while checkpoint resume keeps the same prefix. PostgreSQL CDC config enables delete handling; Oracle CDC is not supported. Catalog projection uses native Doris database-service type and does not export runtime credentials.

## Verification

Unit and HTTP-boundary tests live beside the existing hospital integration suites. `DorisJdbcTest` is opt-in with `HOSPITAL_DORIS_TEST_JDBC_URL`, `HOSPITAL_DORIS_TEST_USER`, `HOSPITAL_DORIS_TEST_PASSWORD`, and optional `HOSPITAL_DORIS_TEST_HTTP_PORT` (8030 by default). The JDBC URL must use `127.0.0.1` and database `ods_validation` or `integration_doris_test`. It creates new UUID-prefixed Doris tables and leaves them available for inspection; it needs no target DROP privilege.

The live source-drift/version-conflict case additionally requires `HOSPITAL_DORIS_TEST_SOURCE_JDBC_URL`, `HOSPITAL_DORIS_TEST_SOURCE_USER`, and `HOSPITAL_DORIS_TEST_SOURCE_PASSWORD`. Its source must be loopback PostgreSQL database `integration_test`; it creates and removes only its own UUID-prefixed source fixture. Engine end-to-end sync and catalog lineage are validated separately during deployment.

Implementation references: [SeaTunnel 3.0 Doris sink](https://seatunnel.apache.org/docs/3.0.0/connectors/sink/Doris/), [Doris 4 CREATE TABLE](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/), [Doris VARCHAR](https://doris.apache.org/docs/4.x/sql-manual/basic-element/sql-data-types/string-type/VARCHAR/), [Doris DESCRIBE](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/DESC-TABLE/), [FE health API](https://doris.apache.org/docs/4.x/admin-manual/open-api/fe-http/health-action/).
