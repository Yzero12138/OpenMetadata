const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test');

(async () => {
  const folder = path.resolve(__dirname, '../../.impeccable/review');
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.HOSPITAL_QA_CHROMIUM || undefined });
  const report = { capturedAt: new Date().toISOString(), fixture: 'Synthetic visual verification only', captures: [], checks: [] };
  try {
    for (const surface of ['login', 'workbench']) {
      for (const theme of ['light', 'dark']) {
        for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
          const context = await browser.newContext({ viewport, colorScheme: theme, reducedMotion: 'reduce' });
          const page = await context.newPage();
          const errors = [];
          page.on('pageerror', error => errors.push(error.message));
          await page.goto(`http://127.0.0.1:3001/?surface=${surface}&theme=${theme}`, { waitUntil: 'networkidle' });
          if (surface === 'login') {
            await page.getByRole('link', { name: '进入 Integrate' }).waitFor();
            assert.equal(await page.locator('input').count(), 0);
            assert.equal(await page.getByRole('link', { name: '进入 Integrate' }).getAttribute('href'), 'http://127.0.0.1:3002/s/portal');
          } else {
            await page.getByRole('link', { name: '模拟 · HIS 就诊记录' }).waitFor();
            await page.getByRole('textbox', { name: /查找数据资产/ }).fill('HIS');
            assert.equal(await page.getByRole('link', { name: '模拟 · HIS 就诊记录' }).getAttribute('href'), '/table/HIS_DEMO.hospital.public.encounters');
          }
          await page.evaluate(() => document.fonts.ready);
          const layout = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
            scrollWidth: document.documentElement.scrollWidth,
            background: getComputedStyle(document.querySelector('main')).backgroundColor,
            text: getComputedStyle(document.querySelector('h1')).color }));
          const primary = surface === 'login'
            ? page.getByRole('link', { name: '进入 Integrate' })
            : page.getByRole('button', { name: '搜索', exact: true });
          assert.equal(await primary.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(18, 99, 75)');
          const file = `${surface}-${theme}-${viewport.width}.png`;
          await page.screenshot({ path: path.join(folder, file), fullPage: true });
          assert.equal(layout.scrollWidth, layout.width, `Overflow on ${file}`);
          assert.deepEqual(errors, [], `Page errors on ${file}`);
          report.captures.push({ file, url: page.url(), viewport, layout, errors, fullPage: true });
          if (surface === 'workbench' && viewport.width === 390) {
            const action = page.getByRole('link', { name: '查看质量规则与整改问题' });
            await action.scrollIntoViewIfNeeded();
            assert.equal(await action.getAttribute('href'), '/data-quality');
            const scrolledFile = `workbench-${theme}-390-actions.png`;
            await page.screenshot({path: path.join(folder, scrolledFile), fullPage: true});
            report.captures.push({file: scrolledFile, url: page.url(), viewport,
              note: 'Native page column scrolled to governance actions; document remains at top', errors, fullPage: true});
          }
          await context.close();
        }
      }
    }
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.route('**/api/v1/tables?*', route => route.fulfill({ status: 403, contentType: 'application/json', body: '{}' }));
    await page.goto('http://127.0.0.1:3001/', { waitUntil: 'networkidle' });
    await page.getByRole('alert').waitFor();
    report.checks.push('Forbidden assets display an error instead of fabricated data');
    await page.unroute('**/api/v1/tables?*');
    await page.getByRole('button', { name: '重新加载' }).click();
    await page.getByRole('link', { name: '模拟 · HIS 就诊记录' }).waitFor();
    report.checks.push('Reload recovers real endpoint state');
    await page.getByRole('textbox', { name: /查找数据资产/ }).fill('HIS');
    await page.getByRole('button', { name: '搜索', exact: true }).click();
    assert.match(page.url(), /\/explore\//);
    assert.match(page.url(), /HIS/);
    report.checks.push('Search uses the native catalog route');
    await context.close();
    fs.writeFileSync(path.join(folder, 'capture-report.json'), JSON.stringify(report, null, 2));
    console.log(`Verified ${report.captures.length} captures and ${report.checks.length} behavior checks.`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
