const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test');

(async () => {
  const folder = path.resolve(__dirname, '../../.impeccable/review/governance');
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.HOSPITAL_QA_CHROMIUM || undefined });
  const report = { capturedAt: new Date().toISOString(), fixture: 'Local synthetic HTTP boundary only; no real hospital authentication or metadata writes', captures: [], checks: [] };
  try {
    for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      const errors = [], writes = [];
      let saved;
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/api/v1/governance/workflowDefinitions**', async route => {
        const request = route.request();
        if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/workflowDefinitions')) {
          writes.push(request.postDataJSON());
          if (writes.length === 1) {
            await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: '合成验证：此工作流名称已存在' }) });
          } else {
            saved = { ...request.postDataJSON(), id: '00000000-0000-4000-8000-000000000001', fullyQualifiedName: request.postDataJSON().name };
            await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(saved) });
          }
        } else if (saved && request.method() === 'GET' && request.url().includes(`/name/${saved.name}`)) {
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(saved) });
        } else await route.continue();
      });
      const capture = async surface => {
        await page.evaluate(() => document.fonts.ready);
        await page.evaluate(() => window.scrollTo(0, 0));
        const layout = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }));
        const file = `${surface}-${viewport.width}.png`;
        await page.screenshot({ path: path.join(folder, file), fullPage: true, animations: 'disabled' });
        report.captures.push({ file, url: page.url(), viewport, layout, errors: [...errors] });
      };
      await page.goto('http://127.0.0.1:3001/workflows', { waitUntil: 'networkidle' });
      await page.getByTestId('create-workflow-button').waitFor();
      assert.equal(await page.getByText('Glossary Approval Workflow', { exact: true }).count(), 0);
      if (viewport.width < 768) {
        const overlaps = await page.getByTestId('pagination').evaluate(root => {
          const boxes = [...root.children].map(node => node.getBoundingClientRect());
          return boxes.some((a, i) => boxes.slice(i + 1).some(b => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top));
        });
        assert.equal(overlaps, false, 'Pagination controls must not overlap');
      }
      await capture('workflow-list');
      await page.getByTestId('create-workflow-button').click();
      await page.getByRole('textbox', { name: /^工作流名称/ }).fill('qa_local_review');
      await page.getByRole('textbox', { name: /^显示名称/ }).fill(viewport.width < 768 ? '数据质量复核工作流（模拟长中文名称验证）' : '数据质量复核');
      await page.getByRole('textbox', { name: /^描述/ }).fill('模拟流程，仅用于界面验证');
      await capture('workflow-create');
      await page.getByTestId('submit-workflow-button').click();
      if (viewport.width < 768) {
        await page.getByTestId('workflow-node-sidebar').waitFor({ state: 'hidden' });
        await page.getByTestId('toggle-workflow-palette').click();
      }
      await page.getByTestId('workflow-node-sidebar').waitFor();
      assert.equal(writes.length, 0);
      assert.equal(await page.getByTestId('workflow-task-item-policyAgentTask').count(), 0);
      const transfer = await page.evaluateHandle(() => new DataTransfer());
      const dropNode = async (node, x, y) => {
        await page.getByTestId(`workflow-${node}-node-draggable`).dispatchEvent('dragstart', { dataTransfer: transfer });
        await page.getByTestId('workflow-canvas').locator('.react-flow').dispatchEvent('drop', { dataTransfer: transfer, clientX: x, clientY: y });
      };
      await dropNode('start', Math.min(viewport.width - 150, 650), 400);
      await page.getByTestId('workflow-config-form-v1').waitFor();
      await page.getByRole('combobox', { name: /^数据资产/ }).click();
      await page.getByRole('option', { name: '数据表', exact: true }).click();
      await page.getByTestId('save-node-configuration-button').click();
      await page.getByTestId('workflow-config-form-v1').waitFor({ state: 'hidden' });
      assert.equal(writes.length, 0);
      await dropNode('end', Math.min(viewport.width - 25, 1000), 500);
      if (viewport.width < 768) {
        await page.getByTestId('workflow-task-item-userApprovalTask').scrollIntoViewIfNeeded();
        await page.getByTestId('toggle-workflow-palette').click();
        await page.getByTestId('workflow-node-sidebar').waitFor({ state: 'hidden' });
        await page.getByTestId('fit-view-button').click();
        for (const id of ['cancel-workflow-button', 'test-workflow-button', 'save-workflow-button', 'workflow-controls']) {
          const box = await page.getByTestId(id).boundingBox();
          assert.ok(box && box.x >= 0 && box.x + box.width <= viewport.width, `${id} must fit the narrow viewport`);
        }
        await capture('workflow-editor');
        report.checks.push({ viewport: viewport.width, result: 'Narrow-screen long Chinese title, all header actions and canvas toolbar fit; palette opens, scrolls to last task and closes; start configuration passes; full graph connect/save/recovery tested at desktop width' });
        page.once('dialog', dialog => dialog.accept());
      } else {
      const from = page.getByTestId('workflow-start-node').locator('.react-flow__handle.source');
      const to = page.getByTestId('workflow-end-node').locator('.react-flow__handle.target');
      const a = await from.boundingBox(), b = await to.boundingBox();
      assert.ok(a && b);
      await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
      await page.mouse.down();
      await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 15 });
      await page.mouse.up();
      await page.locator('.react-flow__edge').waitFor({ state: 'attached' });
      await capture('workflow-editor');
      await Promise.all([
        page.waitForResponse(response => response.request().method() === 'POST' && response.status() === 409),
        page.getByTestId('save-workflow-button').click(),
      ]);
      assert.equal(writes.length, 1);
      await page.getByTestId('edit-workflow-title-button').click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox', { name: /^名称/ }).fill('qa_local_review_retry');
      await dialog.getByRole('textbox', { name: /^显示名称/ }).fill('数据质量复核 · 重试');
      await dialog.getByTestId('save-button').click();
      await dialog.waitFor({ state: 'hidden' });
      assert.equal(writes.length, 1);
      assert.equal(await page.locator('.react-flow__edge').count(), 1);
      await page.getByTestId('save-workflow-button').click();
      await page.waitForURL('**/workflows/qa_local_review_retry/workflow?mode=view');
      assert.equal(writes.length, 2);
      assert.equal(saved.name, 'qa_local_review_retry');
      assert.equal(saved.displayName, '数据质量复核 · 重试');
      assert.deepEqual(saved.trigger.config.entityTypes, ['table']);
      assert.equal(saved.nodes.length, 2);
      assert.equal(saved.edges.length, 1);
      assert.equal(saved.edges[0].from, 'start');
      report.checks.push({ viewport: viewport.width, result: 'Draft stays local; configured graph POST; duplicate retains graph; local rename and retry succeeds; policy-agent node absent' });
      }
      await page.goto('http://127.0.0.1:3001/domains-preview', { waitUntil: 'networkidle' });
      await page.getByRole('combobox').click();
      for (const label of ['聚合', '消费对齐', '源对齐']) {
        await page.locator('.ant-select-item-option-content').filter({ hasText: new RegExp(`^${label}$`) }).waitFor();
      }
      await capture('domain-types');
      assert.deepEqual(errors, []);
      await context.close();
    }
    fs.writeFileSync(path.join(folder, 'report.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
