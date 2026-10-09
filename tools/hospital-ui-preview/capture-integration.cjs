const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test');

const columns = [
  { name: 'visit_id', dataType: 'BIGINT', nullable: false, primaryKey: true },
  { name: 'department_code', dataType: 'VARCHAR', nullable: false, primaryKey: false },
  { name: 'visit_type', dataType: 'VARCHAR', nullable: false, primaryKey: false },
  { name: 'updated_at', dataType: 'TIMESTAMP', nullable: false, primaryKey: false },
];
const exactJobId = '1791535876393000002';

(async () => {
  const folder = path.resolve(__dirname, '../../.impeccable/review/integration');
  fs.mkdirSync(folder, { recursive: true });
  const report = {
    capturedAt: new Date().toISOString(),
    fixture: 'Actual React components with an explicit local synthetic HTTP boundary; no employee authentication, engine execution, or real catalog writes. Real K8s engine verification is separate.',
    captures: [], checks: [],
  };
  const browser = await chromium.launch({ headless: true, executablePath: process.env.HOSPITAL_QA_CHROMIUM || undefined });
  try {
    for (const viewport of [{ width: 1440, height: 1000 }, { width: 1920, height: 1080 }]) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      const errors = [], writes = [];
      let task, createAttempts = 0, catalogAttempts = 0;
      let engineReachable = true;
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/api/v1/hospital/integration/**', async route => {
        const request = route.request();
        const endpoint = new URL(request.url()).pathname.replace('/api/v1/hospital/integration', '');
        const method = request.method();
        const body = method === 'GET' ? undefined : request.postData() ? request.postDataJSON() : undefined;
        if (method !== 'GET') writes.push({ endpoint, method, body });
        let data, status = 200;
        if (endpoint === '/status') data = { enabled: true, reachable: engineReachable, engineVersion: engineReachable ? '3.0.0' : undefined, errorCode: engineReachable ? undefined : 'ENGINE_UNAVAILABLE' };
        else if (endpoint === '/connections') data = { data: [
          { id: 'synthetic-source', displayName: '合成业务源', role: 'SOURCE', databaseType: 'Postgres', synthetic: true },
          { id: 'synthetic-ods', displayName: '合成 ODS 目标', role: 'TARGET', databaseType: 'Postgres', synthetic: true },
        ] };
        else if (endpoint.endsWith('/tables')) data = { data: [{ schema: 'public', name: endpoint.includes('synthetic-source') ? 'visit_events' : 'visit_events_full', columns }] };
        else if (endpoint.endsWith('/test')) data = { connected: true };
        else if (endpoint === '/tasks' && method === 'GET') data = { data: task ? [task] : [] };
        else if (endpoint === '/tasks/validate') data = { valid: true, errors: [] };
        else if (endpoint === '/tasks' && method === 'POST') {
          createAttempts += 1;
          if (createAttempts === 1) { status = 409; data = { errorCode: 'DUPLICATE_TASK', message: 'Synthetic duplicate technical name' }; }
          else {
            task = { ...body, id: '00000000-0000-4000-8000-000000000101', version: 1, createdAt: Date.now(), updatedAt: Date.now(), updatedBy: 'synthetic-admin', runs: [], catalog: { status: 'PENDING' } };
            status = 201; data = task;
          }
        } else if (task && endpoint === `/tasks/${task.id}`) data = task;
        else if (task && endpoint === `/tasks/${task.id}/run`) {
          task = { ...task, latestRun: { jobId: exactJobId, status: body?.resume ? 'RUNNING' : 'FINISHED', submittedAt: Date.now(), finishedAt: body?.resume ? undefined : Date.now(), canResume: false }, updatedAt: Date.now() };
          task.runs = [task.latestRun]; data = task;
        } else if (task && endpoint === `/tasks/${task.id}/stop`) {
          task = { ...task, latestRun: { ...task.latestRun, status: 'SAVEPOINT_DONE', savepointRequested: true, canResume: true, errorCode: undefined, errorMessage: undefined } };
          task.runs = [task.latestRun]; data = task;
        } else if (task && endpoint === `/tasks/${task.id}/catalog`) {
          catalogAttempts += 1;
          task = { ...task, catalog: catalogAttempts === 1 ? { status: 'FAILED', errorCode: 'CATALOG_SYNC_FAILED' } : { status: 'SYNCED', syncedAt: Date.now(), sourceFqn: 'synthetic-source.synthetic_source.public.visit_events', targetFqn: 'synthetic-ods.synthetic_ods.public.visit_events_full', pipelineFqn: 'synthetic-seatunnel.qa_hospital_copy' } }; data = task;
        } else { status = 404; data = { code: 'SYNTHETIC_PREVIEW_ONLY' }; }
        await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
      });
      const capture = async surface => {
        await page.evaluate(() => document.fonts.ready);
        await page.evaluate(() => window.scrollTo(0, 0));
        const layout = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }));
        assert.ok(layout.scrollWidth <= layout.width, `${surface}: document must not overflow horizontally`);
        const compiledStyles = await page.evaluate(() => {
          const facts = document.querySelector('.hospital-integration__facts');
          const job = document.querySelector('.hospital-integration__job-id');
          return {
            factColumns: facts ? getComputedStyle(facts).gridTemplateColumns : undefined,
            jobColumn: job ? getComputedStyle(job).gridColumn : undefined,
            semanticSurfaces: [...document.querySelectorAll('.hospital-integration__empty, .hospital-integration__alert')].map(element => ({
              className: element.className,
              background: getComputedStyle(element).backgroundColor,
              color: getComputedStyle(element).color,
            })),
          };
        });
        for (const element of compiledStyles.semanticSurfaces) {
          assert.notEqual(element.background, 'rgba(0, 0, 0, 0)', `${surface}: ${element.className} must resolve its semantic background`);
        }
        if (compiledStyles.factColumns) {
          assert.equal(compiledStyles.factColumns.split(' ').length, 2, `${surface}: run facts must retain two desktop columns`);
          assert.equal(compiledStyles.jobColumn, '1 / -1', `${surface}: the exact job ID must span the desktop facts row`);
        }
        const file = `${surface}-${viewport.width}.png`;
        await page.screenshot({ path: path.join(folder, file), fullPage: true, animations: 'disabled' });
        report.captures.push({ file, url: page.url(), viewport, layout, compiledStyles, errors: [...errors] });
      };
      await page.goto('http://127.0.0.1:3001/hospital/integration', { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: '新建任务', exact: true }).waitFor();
      await capture('empty');
      await page.getByRole('button', { name: '新建任务', exact: true }).click();
      fs.writeFileSync(path.join(folder, `form-dom-${viewport.width}.txt`), await page.locator('body').innerText());
      await page.getByRole('textbox', { name: /^名称/ }).fill('qa_hospital_copy');
      await page.getByRole('textbox', { name: /^显示名称/ }).fill('合成就诊事件同步');
      const chooseTable = async (label, option) => {
        await page.getByRole('button', { name: new RegExp(label) }).click();
        await page.getByRole('option', { name: option, exact: true }).click();
      };
      await chooseTable('来源表', 'public.visit_events');
      await chooseTable('目标表', 'public.visit_events_full');
      await capture('configure');
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await page.getByRole('alert').first().waitFor();
      assert.equal(await page.getByRole('textbox', { name: /^名称/ }).inputValue(), 'qa_hospital_copy');
      await capture('conflict');
      await page.getByText('名称已存在，请修改名称后保存。', { exact: true }).waitFor();
      await page.getByRole('textbox', { name: /^名称/ }).fill('qa_hospital_copy_2');
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await page.getByRole('button', { name: '运行', exact: true }).waitFor();
      await page.getByRole('button', { name: '运行', exact: true }).click();
      await page.getByTestId('integration-job-id').waitFor();
      assert.equal(await page.getByTestId('integration-job-id').innerText(), exactJobId);
      assert.notEqual(await page.getByTestId('integration-source-count').innerText(), '0');
      await page.getByRole('button', { name: '登记到目录', exact: true }).click();
      await page.getByText('CATALOG_SYNC_FAILED', { exact: true }).waitFor();
      assert.equal(task.latestRun.status, 'FINISHED');
      await capture('run-catalog-error');
      task = { ...task, mode: 'CDC', latestRun: { jobId: exactJobId, status: 'STOP_FAILED', submittedAt: Date.now(), errorCode: 'ENGINE_UNAVAILABLE', canResume: false } };
      task.runs = [task.latestRun];
      await page.reload({ waitUntil: 'networkidle' });
      await page.getByRole('button', { name: '合成就诊事件同步', exact: true }).click();
      await page.getByText('停止失败', { exact: true }).first().waitFor();
      const retryStop = page.getByRole('button', { name: '保存检查点并停止', exact: true });
      assert.equal(await retryStop.isEnabled(), true);
      assert.equal(await page.getByRole('button', { name: '运行', exact: true }).isEnabled(), false);
      assert.equal(await page.getByRole('button', { name: '编辑', exact: true }).isEnabled(), false);
      await capture('stop-failed');
      await retryStop.click();
      assert.equal(task.latestRun.status, 'SAVEPOINT_DONE');
      task = { ...task, mode: 'CDC', latestRun: { jobId: exactJobId, status: 'SAVEPOINT_DONE', submittedAt: Date.now(), savepointRequested: true, canResume: true } };
      task.runs = [task.latestRun];
      await page.reload({ waitUntil: 'networkidle' });
      await page.getByRole('button', { name: '合成就诊事件同步', exact: true }).click();
      const resume = page.getByRole('button', { name: '恢复', exact: true });
      await resume.waitFor();
      assert.equal(await resume.isEnabled(), true);
      assert.equal(await page.getByRole('button', { name: '运行', exact: true }).isEnabled(), false);
      await capture('paused');
      await resume.click();
      const stop = page.getByRole('button', { name: '保存检查点并停止', exact: true });
      await stop.waitFor();
      assert.equal(await stop.isEnabled(), true);
      await stop.click();
      assert.equal(task.latestRun.status, 'SAVEPOINT_DONE');
      await page.goto('http://127.0.0.1:3001/hospital/integration?role=employee', { waitUntil: 'networkidle' });
      assert.equal(await page.getByRole('button', { name: '新建任务', exact: true }).count(), 0);
      await capture('permission');
      engineReachable = false; task = undefined;
      await page.goto('http://127.0.0.1:3001/hospital/integration', { waitUntil: 'networkidle' });
      await page.getByText('暂不可用', { exact: true }).waitFor();
      await capture('engine-unavailable');
      assert.deepEqual(errors, []);
      assert.equal(writes.filter(write => write.endpoint === '/tasks' && write.method === 'POST').length, 2);
      report.checks.push({ viewport: viewport.width, name: 'PC real-component synthetic-boundary creation/conflict/string-ID/unavailable-counter/catalog-failure/stop-retry/pause-resume/permission', passed: true });
      await context.close();
    }
  } finally {
    fs.writeFileSync(path.join(folder, 'report.json'), JSON.stringify(report, null, 2));
    await browser.close();
  }
  console.log(JSON.stringify({ captures: report.captures.length, checks: report.checks, errors: report.captures.flatMap(capture => capture.errors) }));
})().catch(error => { console.error(error); process.exit(1); });
