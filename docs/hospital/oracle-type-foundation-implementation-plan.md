# Oracle type foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make a complete Oracle table containing DATE and CLOB/NCLOB safely representable in the existing Doris table designer and native task validator, without truncation or weakened validation of other targets.

**Architecture:** Carry trusted source/target dialect metadata on inspected column definitions. Apply Oracle LOB-to-Doris STRING compatibility explicitly, and retain Oracle DATE time-of-day when mapped through TIMESTAMP. All metadata returned by the server remains truthful; user-provided JSON cannot assert the trusted dialect marker.

**Tech Stack:** Java21, JUnit5, existing JDBC/SeaTunnel3.0.0/Doris4.0.8 integration.

This is the first independently testable sub-project of the approved oracle-sqlserver-cdc-adaptation-plan.md. The remaining approved work continues with engine packaging/runtime validation, CDC adapter/preflight APIs and PC UI; this plan does not treat type fixes alone as completing Oracle CDC.

Completion evidence: type foundation implemented and independently reviewed. Initial red41/46passed with5intendedfailures; extended green94/94passed. Quality review caught and corrected a legacy identical-LOB regression (red48/50, green50/50). Spec PASS, final quality APPROVE; independent reviewer13/13new foundation tests passed. Spotlessapply/check anddiffcheck passed. No DB/runtime mutation. Runtime oversize/CDC semantics remain in the next sub-project.

## Task 1: Oracle temporal and large-text mapping

**Files:**
- Modify IntegrationModels.java only as needed for trusted inspected dialect metadata, preserving existing constructors and JSON contracts.
- Modify JdbcInspector.java, DorisTableMetadata.java, DorisTableDdl.java, IntegrationValidator.java under openmetadata-service/src/main/java/org/openmetadata/service/integration/.
- Tests in the matching openmetadata-service/src/test/java/org/openmetadata/service/integration/ classes.

- [ ] Add failing tests for DATE with JDBC TIMESTAMP → actual Doris DATETIME(0), retaining source dataType DATE and target dataType DATETIME as reported. A real JDBC-dialect marker must distinguish Doris from narrow-range SQL Server datetime/smalldatetime; old SQL Server restrictions continue to hold.

```java
var source = new ColumnDefinition("VISIT_TIME", "DATE", true, false,
    Types.TIMESTAMP, 7, 0, false);
var target = DorisTableMetadata.column("visit_time", "DATETIME", true, false, false);
// validateTables on a task with this mapping plus its primary key must be valid.
```

- [ ] Add failing tests for CLOB and NCLOB generating STRING, including nullable/required behavior and preserved primary-key restrictions. Unknown LOB size must never be treated as a proven 4,000-character maximum.

```java
var source = new ColumnDefinition("NOTES", "CLOB", true, false,
    Types.CLOB, 4000, 0, false);
// DorisTableDdl.preview must generate `notes` STRING, not VARCHAR(4000) or SUBSTR.
// validateTables accepts the inspected Doris STRING receiver and rejects VARCHAR.
```

- [ ] Add negative cases: Oracle LOB → bounded VARCHAR rejected; required target not mapped rejected; SQL Server datetime narrowing rejected; precision/nullable/PK mismatches remain rejected; client JSON cannot manufacture trusted target metadata.
- [ ] Run only these regression tests and observe the intended failures before production edits. Use existing cached Java21/JUnit dependencies if Maven test lifecycle compiles unrelated tests; keep outputs in .cache/oracle-cdc/types.
- [ ] Implement the minimal changes. If adding a JsonIgnore dialect component to ColumnDefinition, retain the existing 8/10-argument constructors; inspect/query code supplies the actual dialect, and Doris metadata supplies Doris. Do not change original reported type strings solely to bypass the guard.
- [ ] Run the new tests and existing mapping/validator/designer suites; record exact passed/failed/skipped counts. Format Java with Maven Spotless and confirm unrelated tracked files unchanged.
- [ ] Submit changes for separate spec and quality review, fix findings, then commit the scoped files.

## Task 2: Verify native create-and-validate round trip

**Files:** Existing DorisTableApiTest.java / DorisTableDesignApiTest.java, with an Oracle-shaped source definition fixture.

- [ ] Exercise source metadata → target preview → inspected target metadata → native task validate using the 102-field shape (single NUMBER18 PK, 14 DATE fields and one CLOB). Fixture values remain synthetic and local; no HIS row content in tests.
- [ ] Cover exact CHAR/VARCHAR capacity, decimal precision, CLOB NULL and target length failure propagation. Runtime LOB reading and CDC placeholder behavior are separately verified in the engine sub-project; unit mapping tests cannot establish runtime support.
- [ ] Run the meaningful round-trip regression and source/target validation suites. A fixture reporting DATE without its time component is a failure.
- [ ] Confirm service API JSON remains backward compatible with existing tasks and no source or cluster mutations were performed. Record evidence, review and commit.

## Reproducible test path

Use Java21 at D:/Program Files/BellSoft/LibericaJDK-21. Maven is F:/dev/apache-maven-3.9.8/bin/mvn.cmd. Cached production and test dependency classpath is F:/datacenter/_scratch/doris-backend-classpath.txt; the previous lightweight JUnit launcher is .cache/sql-t1/source/SourceTestRunner.java with run-tests.ps1 as an example, not a script to edit in place. Compile only owned source/test changes against the existing target/classes and target/test-classes into a new .cache/oracle-cdc/types directory. The launcher must include all changed production classes before baseline classes.

Expected regression result before changes: LOB preview rejected and Oracle DATE→Doris DATETIME mapping invalid. Expected after changes: both pass while bounded-string/narrow-datetime negative cases remain rejected. No live integration test is counted as passed unless its explicitly opted-in fixture actually ran.
