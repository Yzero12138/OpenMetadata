# Data-source maintenance and JDBC collection

Administrators maintain business data sources from the hospital integration workbench. The application continues to use the existing Integrate identity and native administrator authorization. Every connection endpoint enforces that authorization before reading its input.

## Connection definition

Each definition has a unique lowercase technical name, display name, source or target role, database family and declared version, host, port, database, username, schema scope, TLS mode and enabled state. Oracle also requires a service-name or SID selection. The browser cannot submit an arbitrary JDBC URL, SQL query or driver properties.

Supported families are Oracle 11g/19c, SQL Server 2016/2019, MySQL 8 and PostgreSQL. Full JDBC collection is available for all four families. This phase advertises CDC only for numeric PostgreSQL versions from 10 onward with database names supported by the fixed upstream connector; a name containing a dot is full-only. A declared version is configuration, not proof that a particular server is reachable or compatible.

The two synthetic connections supplied by the environment remain visible and immutable. In the API, `managed=true` specifically identifies those server-managed definitions; user-maintained definitions have `managed=false`. Saving a definition does not mean its connection test succeeded.

## Credential storage and edits

User-maintained definitions are stored in the native entity-extension table under `hospital.integration.connection`. The password is encrypted with the already initialized native Fernet key. A missing key fails closed. API responses include only `passwordSet`; they never return a password or ciphertext. Back up the metadata database and its encryption key together according to the existing secret-management procedure.

An empty or omitted password during edit retains the previous credential. A nonempty password rotates it. Updates and deletion require the last observed version. A stale version returns `VERSION_CONFLICT` without overwriting the current definition. Environment-managed connections cannot be changed here. Connections referenced by active, uncertain or resumable tasks cannot be edited; any task reference prevents deletion.

The current controller runs as one application replica. Its mutation lock and version checks do not constitute coordination between multiple replicas.

## Table discovery and tasks

`GET /connections/{id}/table-options` returns up to 5,000 table names from the configured schema scope, without loading every table's columns. `GET /connections/{id}/tables/{schema}/{table}` inspects the selected table. The legacy rich `/tables` route remains bounded to 200 tables for compatibility. Narrow an overly broad schema scope rather than increasing those bounds.

Tasks refer to registered connection IDs. The server resolves credentials, validates source/target roles and allowed modes, and quotes each family’s identifiers. Collection uses read-only inspection and bounded database timeouts. Database accounts and network access must be provisioned separately for the intended extraction mode.

Mappings require actual primary keys, compatible nullability, sufficient target capacity, and supported data domains. Text receivers must have a verified Unicode encoding or national-character type. Conservative checks reject unverified text encodings, insufficient UTF-16 supplementary-character capacity, temporal precision loss, coarse SQL Server temporal receivers, and integer sign/range loss. A rejected mapping must be corrected in the target schema or mapping; the controller does not silently truncate or cast values.

Full collection inserts or updates by primary key. It does not empty a target or remove rows deleted from the source. The verified PostgreSQL CDC path preserves its existing slots, checkpoint recovery and explicit deletion behavior. Catalog registration remains separate from transfer, creates a distinct native database-service identity per maintained connection, and records the actual database family.

## JDBC runtime

`docker/hospital/seatunnel/jdbc-drivers.json` pins the Oracle, Microsoft SQL Server and MySQL drivers by version, official download URL and SHA-256. Both the application and SeaTunnel image builds consume this manifest. The engine build also verifies the fixed SeaTunnel distribution and connector artifacts. No runtime driver download is required.

The engine embeds a small explicit `HospitalOracle` dialect factory built against the pinned connector. It inherits the native Oracle SQL and type handling but uses the official direct-JDBC query-metadata fallback, because the 3.0.0 optional Oracle catalog rejects TNS descriptors. It does not replace an upstream class or change automatic dialect selection. Both application and engine retain the same bounded connection properties and use a GMT offset for the default session timezone to interoperate with older Oracle timezone files. See the [Oracle JDBC timezone property](https://docs.oracle.com/en/database/oracle/oracle-database/19/jajdb/oracle/jdbc/OracleConnection.html#CONNECTION_PROPERTY_TIMEZONE_AS_REGION).

TLS `VERIFY` requires the correct server name and trusted issuing certificate. Application and engine runtimes must both trust the server's certificate. MySQL uses identity verification and does not enable unrestricted public-key retrieval. PostgreSQL CDC passes the requested TLS mode to Debezium as well as the JDBC metadata connection.

## Verification boundaries

Synthetic fixtures and UI preview artifacts are isolated from hospital business systems. UI preview checks exercise the real React components against a declared synthetic HTTP boundary; they do not establish employee login or real database connectivity. A deployment does not grant outbound access to new business hosts. Configure the concrete source endpoints and then authorize narrowly scoped network access for both inspection and extraction.

## Verified synthetic matrix

| Source | Runtime verified | Transfer | Catalog |
| --- | --- | --- | --- |
| Oracle | Oracle XE 11g, pinned ojdbc8 | 100 exact Chinese rows to PostgreSQL | Native Oracle service, stable IDs, mapped column lineage |
| SQL Server | SQL Server 2019, pinned Microsoft driver | 100 exact Chinese rows to PostgreSQL | Native Mssql service, stable IDs, mapped column lineage |
| MySQL | MySQL 8.0.36, verified TLS identity | 100 exact Chinese rows to PostgreSQL | Native Mysql service, stable IDs, mapped column lineage |
| PostgreSQL | PostgreSQL 17.10 | 100 exact Chinese rows to PostgreSQL | Native Postgres service, stable IDs, mapped column lineage |

The four-family engine test reopens encrypted stored definitions, discovers scoped tables and primary keys, validates the mappings, submits actual SeaTunnel jobs and compares the target rows. The native catalog test uses a disposable migrated metadata database and OpenSearch, verifies idempotency after reopening, and checks that credentials never enter catalog service entities. Together with the controller regression suite, 56 distinct server tests passed in the two explicit fixture runs.

Oracle 19c, SQL Server 2016, real hospital endpoints, Oracle TCPS handshakes and source-specific operational limits still require acceptance against the configured business systems. They are not established by a family name or the synthetic UI examples.

## Building and applying images

Build the service and UI JARs before invoking `docker/hospital/build-image.ps1`. Both image scripts use local image names by default. Supply `-Image` with your own registry path and `-Push` to publish. The deployment templates contain portable local image names; resolve both application containers (migration and server) and the engine to your registry’s immutable image digests in a deployment overlay before applying. Preserve existing secrets, volumes, service identities and task state during an upgrade.
