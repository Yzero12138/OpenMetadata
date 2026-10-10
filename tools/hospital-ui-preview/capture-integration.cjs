const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test');
const locale = require('../../openmetadata-ui/src/main/resources/ui/src/locale/languages/zh-cn.json');
const copy = locale.hospitalIntegration;
const exactJobId = '1791535876393000002';
const columns = [{ name: 'visit_id', dataType: 'BIGINT', nullable: false, primaryKey: true }, ...Array.from({ length: 29 }, (_, i) => ({ name: `field_${String(i + 1).padStart(2, '0')}`, dataType: 'VARCHAR', nullable: true, primaryKey: false }))];
const connection = (id, databaseType, role, displayName, extra = {}) => ({ id, name: id.replaceAll('-', '_'), displayName, databaseType, databaseVersion: databaseType === 'Oracle' ? '11g' : databaseType === 'Mssql' ? '2019' : databaseType === 'Mysql' ? '8' : '17', role, host: `${id}.example.invalid`, port: databaseType === 'Oracle' ? 1521 : databaseType === 'Mssql' ? 1433 : databaseType === 'Mysql' ? 3306 : 5432, database: databaseType === 'Oracle' ? 'XE' : 'business', username: 'synthetic_reader', schemas: databaseType === 'Oracle' ? ['HIS'] : databaseType === 'Mssql' ? ['dbo'] : databaseType === 'Mysql' ? ['business'] : ['public'], tlsMode: 'VERIFY', enabled: true, version: 1, managed: false, synthetic: true, passwordSet: true, supportedModes: ['FULL'], createdAt: 1791590400000, updatedAt: 1791590400000, ...extra });

(async () => {
  const folder = path.resolve(__dirname, '../../.impeccable/review/integration');
  fs.mkdirSync(folder, { recursive: true });
  const report = { capturedAt: new Date().toISOString(), fixture: 'Actual React components with an explicit local synthetic HTTP boundary. Example.invalid endpoints and synthetic credentials; no employee authentication, engine execution, or live catalog writes. Actual four-database engine/catalog verification is recorded separately.', captures: [], checks: [] };
  const previousPacket = process.env.HOSPITAL_QA_REUSE_CAPTURE_PACKET === '1' ? JSON.parse(fs.readFileSync(path.join(folder, 'report.json'), 'utf8')) : undefined;
  const reusable = new Map((previousPacket?.captures || []).map(capture => [capture.file, capture]));
  if (previousPacket) {
    const targets = ['openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage', 'openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer', 'openmetadata-ui/src/main/resources/ui/src/locale/languages'];
    const walk = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(directory, entry.name)) : [path.join(directory, entry.name)]);
    const files = [...targets.flatMap(target => walk(path.resolve(__dirname, '../..', target))), path.resolve(__dirname, '../../openmetadata-ui/src/main/resources/ui/src/rest/hospitalIntegrationAPI.ts')];
    assert.ok(files.every(file => fs.statSync(file).mtimeMs <= Date.parse(previousPacket.capturedAt)), 'Existing captures require unchanged UI source');
  }
  const browser = await chromium.launch({ headless: true, executablePath: process.env.HOSPITAL_QA_CHROMIUM || undefined });
  try {
    for (const viewport of [{ width: 1440, height: 1000 }, { width: 1920, height: 1080 }]) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      const errors = [], writes = [];
      let connections = [connection('synthetic-source', 'Postgres', 'SOURCE', '合成业务源', { managed: true, supportedModes: ['FULL', 'CDC'] }), connection('synthetic-ods', 'Postgres', 'TARGET', '合成 ODS 目标', { managed: true, supportedModes: ['FULL', 'CDC'] }), connection('oracle-his', 'Oracle', 'SOURCE', 'Oracle HIS 采集库（示例）', { oracleConnectionType: 'SID' }), connection('mssql-lis', 'Mssql', 'SOURCE', 'SQL Server LIS 采集库（示例）'), connection('mysql-ops', 'Mysql', 'SOURCE', 'MySQL 运营库（示例）')];
      let task, createAttempts = 0, connectionCreates = 0, connectionUpdates = 0, catalogAttempts = 0, engineReachable = true, releaseSave, notifySave;
      const saveStarted = new Promise(resolve => { notifySave = resolve; });
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/api/v1/hospital/integration/**', async route => {
        const request = route.request();
        const endpoint = new URL(request.url()).pathname.replace('/api/v1/hospital/integration', '');
        const method = request.method();
        const body = method === 'GET' ? undefined : request.postData() ? request.postDataJSON() : undefined;
        if (method !== 'GET') { const { password, ...redacted } = body || {}; writes.push({ endpoint, method, body: redacted, hasPassword: password !== undefined }); }
        let data, status = 200;
        if (endpoint === '/status') data = { enabled: true, reachable: engineReachable, engineVersion: engineReachable ? '3.0.0' : undefined, errorCode: engineReachable ? undefined : 'ENGINE_UNAVAILABLE' };
        else if (endpoint === '/connections' && method === 'GET') data = { data: connections };
        else if (endpoint === '/connections' && method === 'POST') {
          connectionCreates += 1;
          if (connectionCreates === 1) { status = 409; data = { errorCode: 'DUPLICATE_CONNECTION' }; }
          else { await new Promise(resolve => { releaseSave = resolve; notifySave(); }); const { password, ...definition } = body; const created = { ...connection('created-source', body.databaseType, body.role, body.displayName), ...definition, id: 'created-source', synthetic: true, passwordSet: true }; connections.push(created); status = 201; data = created; }
        } else if (/^\/connections\/[^/]+$/.test(endpoint)) {
          const id = endpoint.split('/')[2];
          const current = connections.find(item => item.id === id);
          if (method === 'GET') data = current;
          else if (method === 'PUT') {
            connectionUpdates += 1;
            if (connectionUpdates === 1) { connections = connections.map(item => item.id === id ? { ...item, displayName: '服务端当前名称（示例）', version: 2 } : item); status = 409; data = { errorCode: 'VERSION_CONFLICT' }; }
            else { const { password, ...definition } = body; data = { ...current, ...definition, version: current.version + 1 }; connections = connections.map(item => item.id === id ? data : item); }
          } else if (method === 'DELETE') { connections = connections.filter(item => item.id !== id); status = 204; }
        } else if (endpoint.endsWith('/table-options')) {
          const selected = connections.find(item => item.id === endpoint.split('/')[2]);
          data = { data: [{ schema: selected.schemas[0], name: selected.role === 'SOURCE' ? 'visit_events' : 'visit_events_full', columns: [] }] };
        } else if (endpoint.includes('/tables/')) {
          const parts = endpoint.split('/'); data = { schema: decodeURIComponent(parts[4]), name: decodeURIComponent(parts[5]), columns };
        } else if (endpoint.endsWith('/test')) data = endpoint.includes('created-source') ? { connected: false, errorCode: 'CONNECTION_UNAVAILABLE' } : { connected: true };
        else if (endpoint === '/tasks' && method === 'GET') data = { data: task ? [task] : [] };
        else if (endpoint === '/tasks/validate') data = { valid: true, errors: [] };
        else if (endpoint === '/tasks' && method === 'POST') {
          createAttempts += 1;
          if (createAttempts === 1) { status = 409; data = { errorCode: 'DUPLICATE_TASK' }; }
          else { task = { ...body, id: '00000000-0000-4000-8000-000000000101', version: 1, createdAt: Date.now(), updatedAt: Date.now(), updatedBy: 'synthetic-admin', runs: [], catalog: { status: 'PENDING' } }; status = 201; data = task; }
        } else if (task && endpoint === `/tasks/${task.id}`) { if (method === 'PUT') task = { ...task, ...body, version: task.version + 1 }; data = task; }
        else if (task && endpoint === `/tasks/${task.id}/run`) { task = { ...task, latestRun: { jobId: exactJobId, status: body?.resume ? 'RUNNING' : 'FINISHED', submittedAt: Date.now(), canResume: false } }; task.runs = [task.latestRun]; data = task; }
        else if (task && endpoint === `/tasks/${task.id}/stop`) { task = { ...task, latestRun: { ...task.latestRun, status: 'SAVEPOINT_DONE', savepointRequested: true, canResume: true, errorCode: undefined } }; task.runs = [task.latestRun]; data = task; }
        else if (task && endpoint === `/tasks/${task.id}/catalog`) { catalogAttempts += 1; task = { ...task, catalog: catalogAttempts === 1 ? { status: 'FAILED', errorCode: 'CATALOG_SYNC_FAILED' } : { status: 'SYNCED', syncedAt: Date.now() } }; data = task; }
        else { status = 404; data = { code: 'SYNTHETIC_PREVIEW_ONLY' }; }
        await route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(data) });
      });
      const choose = async (id, label) => { await page.locator('#' + id).click(); await page.getByRole('option', { name: label, exact: true }).click(); };
      const assertAlertInView = async drawer => {
        const alert = drawer.getByRole('alert').first();
        await page.waitForFunction(() => {
          const dialog = document.querySelector('.form-drawer__dialog');
          const alert = dialog?.querySelector('[role="alert"]');
          const body = dialog?.querySelector('.form-drawer__body');
          if (!alert || !body) return false;
          const a = alert.getBoundingClientRect(), b = body.getBoundingClientRect();
          return a.top >= b.top - 1 && a.bottom <= b.bottom + 1;
        });
        assert.equal(await alert.evaluate(element => element === document.activeElement), true, 'failure summary receives focus');
      };
      const capture = async surface => {
        await page.evaluate(() => document.fonts.ready);
        await page.waitForFunction(() => [...document.querySelectorAll('.form-drawer__dialog')].every(element => {
          const rect = element.getBoundingClientRect();
          return rect.width > 0 && Math.abs(rect.right - window.innerWidth) < 2 && Math.abs(rect.height - window.innerHeight) < 2;
        }), undefined, { timeout: 5000 });
        const layout = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }));
        assert.ok(layout.scrollWidth <= layout.width, `${surface}: no document horizontal overflow`);
        const geometry = await page.evaluate(() => {
          const dialog = document.querySelector('.form-drawer__dialog');
          if (!dialog) return undefined;
          const box = element => { const rect = element.getBoundingClientRect(); return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, right: rect.right, bottom: rect.bottom }; };
          const body = dialog.querySelector('.form-drawer__body');
          return { dialog: box(dialog), header: box(dialog.querySelector('.form-drawer__header')), footer: box(dialog.querySelector('.form-drawer__footer')), body: { ...box(body), scrollHeight: body.scrollHeight, clientHeight: body.clientHeight, scrollTop: body.scrollTop, scrollWidth: body.scrollWidth, clientWidth: body.clientWidth } };
        });
        if (geometry) { assert.ok(Math.abs(geometry.dialog.right - viewport.width) < 2, 'drawer right edge'); assert.ok(geometry.footer.bottom <= viewport.height + 2, 'fixed footer visible'); assert.ok(geometry.body.scrollWidth <= geometry.body.clientWidth + 1, 'drawer no horizontal overflow'); }
        const file = `${surface}-${viewport.width}.png`;
        if (!reusable.has(file)) await page.screenshot({ path: path.join(folder, file), fullPage: false, animations: 'disabled' });
        else assert.ok(fs.existsSync(path.join(folder, file)), 'Previous valid capture exists');
        report.captures.push({ file, url: page.url(), viewport, layout, geometry, errors: [...errors] });
      };
      await page.goto('http://127.0.0.1:3001/hospital/integration', { waitUntil: 'networkidle' });
      await page.getByTestId('integration-create-action').waitFor();
      await capture('empty');
      await page.getByTestId('integration-connections-tab').click();
      await page.getByTestId('integration-connections-table').waitFor();
      const seed = page.getByTestId('integration-connection-row-synthetic-source');
      assert.equal(await seed.getByRole('button', { name: locale.label.edit, exact: true }).count(), 0);
      await capture('sources');
      await page.getByTestId('integration-create-action').click();
      await page.getByTestId('integration-connection-form').waitFor();
      await page.locator('#connection-name').fill('outpatient_source');
      await page.locator('#connection-displayName').fill('门诊采集库（示例）');
      await choose('connection-databaseType', 'Oracle');
      await page.locator('#connection-databaseVersion').fill('11g');
      await page.locator('#connection-host').fill('outpatient.example.invalid');
      await page.locator('#connection-database').fill('XE');
      await choose('connection-oracleConnectionType', copy.oracleSid);
      await page.locator('#connection-schemas').fill('HIS');
      await page.locator('#connection-username').fill('synthetic_reader');
      await page.locator('#connection-password').fill('synthetic-preview-only');
      await page.locator('.form-drawer__body').evaluate(element => { element.scrollTop = 0; });
      await capture('source-create');
      const sourceDrawer = page.getByTestId('integration-connection-drawer');
      await page.locator('.form-drawer__body').evaluate(element => { element.scrollTop = element.scrollHeight; });
      await sourceDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await sourceDrawer.getByRole('alert').first().waitFor();
      assert.equal(await page.locator('#connection-name').inputValue(), 'outpatient_source');
      await assertAlertInView(sourceDrawer);
      await capture('source-save-error');
      await sourceDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await saveStarted;
      assert.equal(await sourceDrawer.getByRole('button', { name: locale.label.close, exact: true }).isDisabled(), true);
      await page.keyboard.press('Escape');
      assert.equal(await page.getByTestId('integration-connection-form').isVisible(), true);
      assert.equal(connectionCreates, 2);
      await capture('source-pending');
      releaseSave();
      await page.getByTestId('integration-connection-form').waitFor({ state: 'detached' });
      let createdRow = page.getByTestId('integration-connection-row-created-source');
      await createdRow.waitFor();
      assert.equal(await createdRow.getByText(copy.connectionUntested, { exact: true }).count(), 1);
      await createdRow.getByRole('button', { name: copy.testConnection, exact: true }).click();
      await createdRow.getByText(copy.connectionFailed, { exact: true }).waitFor();
      await createdRow.scrollIntoViewIfNeeded();
      await capture('source-connection-error');
      await createdRow.getByRole('button', { name: locale.label.edit, exact: true }).click();
      assert.equal(await page.locator('#connection-password').inputValue(), '');
      await page.locator('#connection-displayName').fill('本地编辑草稿（示例）');
      await sourceDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await page.getByTestId('integration-connection-reload').waitFor();
      assert.equal(await page.locator('#connection-displayName').inputValue(), '本地编辑草稿（示例）');
      await assertAlertInView(sourceDrawer);
      await capture('source-version-conflict');
      await page.getByTestId('integration-connection-reload').click();
      await page.locator('#connection-displayName').waitFor();
      await page.waitForFunction(() => document.getElementById('connection-displayName').value === '服务端当前名称（示例）');
      await capture('source-edit');
      await sourceDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await page.getByTestId('integration-connection-form').waitFor({ state: 'detached' });
      assert.ok(writes.filter(write => write.method === 'PUT' && write.endpoint.startsWith('/connections/')).every(write => !write.hasPassword), 'blank password never sent');
      createdRow = page.getByTestId('integration-connection-row-created-source');
      await createdRow.getByRole('button', { name: locale.label.delete, exact: true }).click();
      await createdRow.getByRole('button', { name: copy.confirmDeleteConnection, exact: true }).click();
      await createdRow.waitFor({ state: 'detached' });
      await page.getByTestId('integration-tasks-tab').click();
      await page.getByTestId('integration-create-action').click();
      await page.getByTestId('integration-task-form').waitFor();
      assert.equal(await page.getByTestId('integration-tasks-list').count(), 1);
      await page.locator('#integration-task-name').fill('qa_hospital_copy');
      await page.locator('#integration-task-displayName').fill('合成就诊事件同步');
      await choose('integration-source-connection', 'Oracle HIS 采集库（示例）');
      await page.locator('#integration-mode').click();
      assert.equal(await page.getByRole('option').count(), 1, 'Oracle advertises only FULL');
      await page.keyboard.press('Escape');
      assert.equal(await page.getByTestId('integration-task-form').isVisible(), true, 'Escape closes nested select first');
      await choose('integration-source-table', 'HIS.visit_events');
      await choose('integration-target-table', 'public.visit_events_full');
      await page.getByTestId('integration-field-mappings').locator('strong').filter({ hasText: /^field_29$/ }).waitFor();
      await page.locator('.form-drawer__body').evaluate(element => { element.scrollTop = 0; });
      await capture('task-create');
      const fixedBefore = await page.locator('.form-drawer__footer').boundingBox();
      await page.locator('.form-drawer__body').evaluate(element => { element.scrollTop = element.scrollHeight; });
      const fixedAfter = await page.locator('.form-drawer__footer').boundingBox();
      assert.equal(Math.round(fixedBefore.y), Math.round(fixedAfter.y), 'long mapping footer stays fixed');
      await capture('task-long-mapping');
      const taskDrawer = page.getByTestId('integration-task-drawer');
      await taskDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await taskDrawer.getByRole('alert').first().waitFor();
      assert.equal(await page.locator('#integration-task-name').inputValue(), 'qa_hospital_copy');
      await assertAlertInView(taskDrawer);
      await capture('task-conflict');
      await page.locator('#integration-task-name').fill('qa_hospital_copy_2');
      await taskDrawer.getByRole('button', { name: locale.label.save, exact: true }).click();
      await page.getByTestId('integration-task-form').waitFor({ state: 'detached' });
      await page.getByRole('button', { name: locale.label.edit, exact: true }).click();
      assert.equal(await page.getByTestId('integration-task-detail').count(), 1);
      await page.locator('#integration-task-displayName').fill('合成就诊事件同步（已编辑）');
      await capture('task-edit');
      await page.getByTestId('integration-task-drawer').getByRole('button', { name: locale.label.save, exact: true }).click();
      await page.getByTestId('integration-task-form').waitFor({ state: 'detached' });
      await page.getByRole('button', { name: locale.label.run, exact: true }).click();
      await page.getByTestId('integration-job-id').waitFor();
      assert.equal(await page.getByTestId('integration-job-id').innerText(), exactJobId);
      assert.notEqual(await page.getByTestId('integration-source-count').innerText(), '0');
      await page.getByRole('button', { name: copy.syncCatalog, exact: true }).click();
      await page.getByText('CATALOG_SYNC_FAILED', { exact: true }).waitFor();
      assert.equal(task.latestRun.status, 'FINISHED');
      await page.getByRole('button', { name: copy.retryCatalog, exact: true }).scrollIntoViewIfNeeded();
      await capture('run-catalog-error');
      await page.goto('http://127.0.0.1:3001/hospital/integration?role=employee', { waitUntil: 'networkidle' });
      assert.equal(await page.getByTestId('integration-create-action').count(), 0);
      await capture('permission');
      engineReachable = false; task = undefined;
      await page.goto('http://127.0.0.1:3001/hospital/integration', { waitUntil: 'networkidle' });
      await page.getByText(copy.unreachable, { exact: true }).waitFor();
      await capture('engine-unavailable');
      assert.deepEqual(errors, []);
      report.checks.push({ viewport: viewport.width, name: 'source CRUD/right drawers/pending-close protection/error draft retention/blank password omission/explicit conflict reload/selected source/FULL-only mode/long mapping/fixed footer/nested keyboard/list-detail preservation/exact string job ID/unavailable counter/catalog failure/permission', passed: true, requests: writes });
      await context.close();
    }
  } finally { fs.writeFileSync(path.join(folder, 'report.json'), JSON.stringify(report, null, 2)); await browser.close(); }
  console.log(JSON.stringify({ captures: report.captures.length, checks: report.checks.map(({ requests, ...check }) => check), errors: report.captures.flatMap(capture => capture.errors) }));
})().catch(error => { console.error(error); process.exit(1); });