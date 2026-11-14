/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
import { IntegrationTask } from '../../rest/hospitalIntegrationAPI';
import {
  EMPTY_INTEGRATION_TASK,
  toIntegrationTaskInput,
} from './HospitalIntegrationUtils';
import {
  cdcCheckMessageKey,
  cdcErrorKey,
  cdcPreflightReady,
  changeIntegrationMode,
  integrationTaskPreflightKey,
  parseRuntimeInteger,
  runtimeDefaults,
  validateIntegrationRuntime,
} from './IntegrationCdcUtils';

const task: IntegrationTask = {
  ...EMPTY_INTEGRATION_TASK,
  sourceConnectionId: 'source',
  mode: 'CDC',
  id: 'one',
  version: 2,
  createdAt: 1,
  updatedAt: 1,
  updatedBy: 'synthetic-admin',
  runs: [],
  catalog: { status: 'PENDING' },
  fieldMappings: [{ source: 'ID', target: 'visit_id' }],
  cdc: { serverTimeZone: 'Asia/Shanghai' },
  runtime: { sinkBatchSize: 1000 },
};

describe('CDC options and inspection identity', () => {
  it.each(
    (
      [
        ['mssqlRuntime', ['MSSQL_CDC_RUNTIME_UNVERIFIED']],
        [
          'mssqlRoute',
          ['MSSQL_CDC_ROUTE_PROVEN', 'MSSQL_CDC_ROUTE_UNSUPPORTED'],
        ],
        [
          'mssqlVersion',
          [
            'MSSQL_CDC_2019_BASELINE',
            'MSSQL_CDC_EDITION_UNSUPPORTED',
            'MSSQL_CDC_VERSION_UNCERTIFIED',
          ],
        ],
        [
          'mssqlDatabaseHint',
          [
            'MSSQL_DB_NAME_PROVEN',
            'MSSQL_DB_NAME_MISMATCH',
            'MSSQL_DB_NAME_UNVERIFIED',
          ],
        ],
        [
          'timeZoneHint',
          ['MSSQL_TIME_ZONE_CONFIGURED', 'MSSQL_TIME_ZONE_REQUIRED'],
        ],
        [
          'mssqlDatabaseCdc',
          [
            'MSSQL_DATABASE_CDC_ENABLED',
            'MSSQL_DATABASE_CDC_DISABLED',
            'MSSQL_DATABASE_CDC_UNVERIFIED',
          ],
        ],
        [
          'mssqlTopology',
          [
            'MSSQL_STANDALONE_METADATA_PROVEN',
            'MSSQL_CDC_TOPOLOGY_UNSUPPORTED',
            'MSSQL_TOPOLOGY_UNVERIFIED',
          ],
        ],
        [
          'mssqlAdr',
          [
            'MSSQL_ADR_DISABLED',
            'MSSQL_CDC_ADR_INCOMPATIBLE',
            'MSSQL_ADR_UNVERIFIED',
          ],
        ],
        [
          'mssqlDatabaseCollation',
          [
            'MSSQL_CDC_DATABASE_COLLATION_PROVEN',
            'MSSQL_CDC_DATABASE_COLLATION_UNSUPPORTED',
            'MSSQL_CDC_DATABASE_COLLATION_UNVERIFIED',
          ],
        ],
        [
          'mssqlNativeMetadata',
          [
            'MSSQL_CDC_NATIVE_METADATA_PROVEN',
            'MSSQL_CDC_NATIVE_METADATA_DENIED',
            'MSSQL_CDC_NATIVE_METADATA_UNVERIFIED',
          ],
        ],
        [
          'mssqlTable',
          [
            'MSSQL_TABLE_CDC_ENABLED',
            'MSSQL_TABLE_CDC_DISABLED',
            'MSSQL_TABLE_CDC_UNVERIFIED',
            'MSSQL_CDC_TABLE_KIND_PROVEN',
            'MSSQL_CDC_TABLE_KIND_UNSUPPORTED',
            'MSSQL_CDC_TABLE_KIND_UNVERIFIED',
          ],
        ],
        [
          'mssqlIdentifiers',
          ['MSSQL_CDC_IDENTIFIERS_PROVEN', 'MSSQL_CDC_IDENTIFIER_UNSUPPORTED'],
        ],
        ['columns', ['MSSQL_CDC_COLUMNS_UNVERIFIED']],
        ['primaryKey', ['MSSQL_PRIMARY_KEY_UNVERIFIED']],
        [
          'mssqlCaptureInstance',
          [
            'MSSQL_CAPTURE_INSTANCE_PROVEN',
            'MSSQL_CAPTURE_INSTANCE_UNSUPPORTED',
            'MSSQL_CAPTURE_DROP_PENDING',
            'MSSQL_CAPTURE_INSTANCE_UNVERIFIED',
            'MSSQL_CAPTURE_IDENTITY_PROVEN',
            'MSSQL_CAPTURE_IDENTITY_UNVERIFIED',
          ],
        ],
        [
          'mssqlCapturedColumns',
          [
            'MSSQL_CAPTURED_COLUMNS_PROVEN',
            'MSSQL_CAPTURED_COLUMNS_INCOMPLETE',
            'MSSQL_CAPTURED_COLUMNS_UNVERIFIED',
          ],
        ],
        [
          'mssqlTypes',
          [
            'MSSQL_CDC_TYPES_PROVEN',
            'MSSQL_CDC_TYPE_UNSUPPORTED',
            'MSSQL_CDC_TYPES_UNVERIFIED',
          ],
        ],
        [
          'mssqlColumnCollation',
          [
            'MSSQL_CDC_COLLATION_PROVEN',
            'MSSQL_CDC_COLLATION_RISK',
            'MSSQL_CDC_COLLATION_UNVERIFIED',
          ],
        ],
        [
          'mssqlPermissions',
          [
            'MSSQL_CDC_SELECT_PROVEN',
            'MSSQL_CDC_SELECT_DENIED',
            'MSSQL_CDC_SELECT_UNVERIFIED',
          ],
        ],
        [
          'mssqlStatePermissions',
          [
            'MSSQL_CDC_STATE_PROVEN',
            'MSSQL_CDC_STATE_DENIED',
            'MSSQL_CDC_STATE_UNVERIFIED',
          ],
        ],
        [
          'mssqlAgent',
          [
            'MSSQL_AGENT_RUNNING',
            'MSSQL_AGENT_NOT_RUNNING',
            'MSSQL_AGENT_STATE_UNVERIFIED',
          ],
        ],
        [
          'mssqlCaptureJob',
          [
            'MSSQL_CAPTURE_JOB_RUNNING',
            'MSSQL_CAPTURE_JOB_UNAVAILABLE',
            'MSSQL_CAPTURE_JOB_UNVERIFIED',
          ],
        ],
        [
          'mssqlCleanup',
          [
            'MSSQL_CLEANUP_RETENTION_CONFIGURED',
            'MSSQL_CLEANUP_RETENTION_INVALID',
            'MSSQL_CLEANUP_RETENTION_UNVERIFIED',
          ],
        ],
        [
          'mssqlLsnRange',
          ['MSSQL_CDC_LSN_RANGE_AVAILABLE', 'MSSQL_CDC_LSN_RANGE_UNAVAILABLE'],
        ],
        [
          'mssqlHealth',
          [
            'MSSQL_CDC_CURRENT_SCAN_NO_ERRORS',
            'MSSQL_CDC_CURRENT_SCAN_ERRORS',
            'MSSQL_CDC_CAPTURE_HEALTH_UNVERIFIED',
          ],
        ],
        ['targetMapping', ['MSSQL_CDC_TARGET_UNSUPPORTED']],
        ['targetProjection', ['MSSQL_CDC_TARGET_PROJECTION_INCOMPLETE']],
      ] as const
    ).flatMap(([key, codes]) => codes.map((code) => [code, key]))
  )(
    'explains native SQL Server check %s without Oracle-specific guidance',
    (code, key) => {
      expect(cdcCheckMessageKey(code)).toBe('hospitalCdc.' + key);
    }
  );

  it.each([
    ['MSSQL_CDC_OPTIONS_INVALID', 'mssqlOptions'],
    ['MSSQL_CDC_ROUTE_UNSUPPORTED', 'mssqlRoute'],
    ['MSSQL_DB_NAME_MISMATCH', 'mssqlDatabaseHint'],
    ['MSSQL_CDC_IDENTIFIER_UNSUPPORTED', 'mssqlIdentifiers'],
    ['MSSQL_CDC_TYPE_UNSUPPORTED', 'mssqlTypes'],
    ['MSSQL_CDC_TARGET_PROJECTION_INCOMPLETE', 'targetProjection'],
    ['CDC_TRUSTED_INSPECTION_REQUIRED', 'requiredBlocked'],
    ['CDC_PREFLIGHT_FAILED', 'requiredBlocked'],
    ['CDC_CONFIGURATION_CHANGED', 'configurationChanged'],
    ['CDC_LEGACY_RESUME_UNSAFE', 'legacyResume'],
  ])('explains SQL Server action rejection %s safely', (code, key) => {
    expect(cdcErrorKey(code)).toBe('hospitalCdc.' + key);
  });

  it('keeps an unknown SQL Server code behind the safe fallback', () => {
    expect(cdcCheckMessageKey('MSSQL_FUTURE_SQL_ERROR')).toBe(
      'hospitalCdc.unknownCheck'
    );
    expect(cdcErrorKey('MSSQL_FUTURE_SQL_ERROR')).toBeUndefined();
  });

  it.each([
    ['EXECUTE_GRANT_PROVEN', 'packageExecute'],
    ['EXECUTE_GRANT_UNVERIFIED', 'packageExecute'],
    ['ORACLE_MINING_VIEW_ACCESS_PROVEN', 'miningViews'],
    ['ORACLE_MINING_VIEW_ACCESS_UNVERIFIED', 'miningViews'],
    ['ORACLE_MINING_PRIVILEGES_PROVEN', 'miningPrivileges'],
    ['ORACLE_MINING_PRIVILEGES_UNVERIFIED', 'miningPrivileges'],
  ])('explains %s with its specific permission requirement', (code, key) => {
    expect(cdcCheckMessageKey(code)).toBe('hospitalCdc.' + key);
  });

  it('preserves nullable inherited runtime fields through copying and validation', () => {
    const nullable = { ...task, runtime: { fetchSize: null } };

    expect(validateIntegrationRuntime(nullable)).toEqual([]);

    const copy = toIntegrationTaskInput(nullable);

    expect(copy.runtime).toEqual(nullable.runtime);
    expect(copy.runtime).not.toBe(nullable.runtime);
    expect(
      validateIntegrationRuntime({
        ...nullable,
        mode: 'SQL_T1',
        runtime: {
          ...nullable.runtime,
          splitSize: null,
          sinkBatchSize: null,
          checkpointIntervalSeconds: null,
          checkpointTimeoutSeconds: null,
        },
      })
    ).toEqual([]);
  });

  it('copies deliberate options without migrating missing legacy PostgreSQL fields', () => {
    const copy = toIntegrationTaskInput(task);

    expect(copy).toMatchObject({ cdc: task.cdc, runtime: task.runtime });
    expect(copy.cdc).not.toBe(task.cdc);
    expect(copy.runtime).not.toBe(task.runtime);

    const { cdc: _cdc, runtime: _runtime, ...legacy } = task;

    expect(toIntegrationTaskInput(legacy)).not.toHaveProperty('cdc');
    expect(toIntegrationTaskInput(legacy)).not.toHaveProperty('runtime');
  });

  it('ignores observational task versions and checkpoints but includes ordered mapping and connection revisions', () => {
    const key = (value = task, version = 3) =>
      integrationTaskPreflightKey(
        value,
        [{ id: 'source', version }],
        'route:session',
        0
      );

    expect(
      key({
        ...task,
        version: 90,
        latestRun: {
          jobId: '1',
          status: 'RUNNING',
          submittedAt: 0,
          checkpointsCheckedAt: 5,
        },
      })
    ).toBe(key());
    expect(
      key({ ...task, fieldMappings: [{ source: 'ID', target: 'another' }] })
    ).not.toBe(key());
    expect(key(task, 4)).not.toBe(key());
    expect(key({ ...task, cdc: { serverTimeZone: 'UTC' } })).not.toBe(key());
  });

  it('keeps placeholders separate from persisted options and validates effective checkpoint limits', () => {
    expect(runtimeDefaults('FULL')).toMatchObject({
      sinkBatchSize: 10000,
      checkpointIntervalSeconds: 60,
      checkpointTimeoutSeconds: 900,
    });
    expect(runtimeDefaults('CDC')).toMatchObject({
      sinkBatchSize: 1000,
      checkpointIntervalSeconds: 30,
      checkpointTimeoutSeconds: 300,
    });
    expect(runtimeDefaults('SQL_T1')).not.toHaveProperty('splitSize');
    expect(validateIntegrationRuntime({ ...task, runtime: undefined })).toEqual(
      []
    );
    expect(
      validateIntegrationRuntime({
        ...task,
        runtime: { checkpointIntervalSeconds: 200 },
      })
    ).toContain('runtimeTimeoutInvalid');
    expect(
      validateIntegrationRuntime({ ...task, runtime: { fetchSize: 99 } })
    ).toContain('runtimeRangeInvalid');
    expect(
      validateIntegrationRuntime({ ...task, runtime: { fetchSize: 100.2 } })
    ).toContain('runtimeRangeInvalid');
  });

  it.each(['1.1', '1000x', '1e3', '-100', 'NaN', ' 1000'])(
    'rejects coercive integer text %s',
    (text) => expect(parseRuntimeInteger(text)).toEqual({ valid: false })
  );

  it('parses blank as inherited and strict safe integers as deliberate values', () => {
    expect(parseRuntimeInteger('')).toEqual({ valid: true, value: undefined });
    expect(parseRuntimeInteger('1000')).toEqual({ valid: true, value: 1000 });
  });

  it('cleans Oracle and SQL-specific settings on mode changes without filling defaults', () => {
    expect(changeIntegrationMode(task, 'FULL')).not.toHaveProperty('cdc');
    expect(
      changeIntegrationMode(
        { ...task, runtime: { fetchSize: 2000, splitSize: 8192 } },
        'SQL_T1'
      ).runtime
    ).toEqual({ fetchSize: 2000 });
  });

  it('blocks required unknown and failures but permits optional runtime and network warnings', () => {
    const base = {
      connectionId: 'source',
      databaseType: 'Oracle' as const,
      implemented: true,
      ready: true,
    };

    expect(
      cdcPreflightReady({
        ...base,
        checks: [
          {
            id: 'network',
            code: 'ENGINE_SOURCE_NETWORK_UNVERIFIED',
            status: 'UNKNOWN',
            required: false,
          },
        ],
      })
    ).toBe(true);

    for (const code of [
      'ORACLE_CDC_LOB_UNVERIFIED',
      'ORACLE_CDC_STRING_BYTES_UNVERIFIED',
      'ORACLE_CDC_EXTENDED_STRING_UNVERIFIED',
      'ORACLE_CDC_IDENTIFIER_UNSUPPORTED',
      'ORACLE_CDC_TARGET_PROJECTION_INCOMPLETE',
      'ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED',
    ]) {
      expect(
        cdcPreflightReady({
          ...base,
          checks: [{ id: 'rule', code, status: 'UNKNOWN', required: true }],
        })
      ).toBe(false);
    }

    expect(cdcPreflightReady({ ...base, ready: false, checks: [] })).toBe(
      false
    );
    expect(
      cdcPreflightReady({
        ...base,
        checks: [
          {
            id: 'inconsistent',
            code: 'UNKNOWN_RULE',
            status: 'NOT_APPLICABLE',
            required: true,
          },
        ],
      })
    ).toBe(false);
  });
});
