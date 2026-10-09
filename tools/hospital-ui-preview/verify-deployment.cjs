const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test');

(async () => {
  const origin = process.env.HOSPITAL_METADATA_ORIGIN || 'http://172.16.120.211:30925';
  const portal = process.env.HOSPITAL_PORTAL_URL || 'https://integrate-dev.qcrmyy.local/s/portal';
  const report = { checkedAt: new Date().toISOString(), origin, checks: [] };
  const failures = [];
  const check = async (name, endpoint, options, expectedStatus) => {
    const response = await fetch(`${origin}${endpoint}`, { ...options, signal: AbortSignal.timeout(15000) });
    const content = await response.text();
    const passed = response.status === expectedStatus;
    report.checks.push({ name, status: response.status, expectedStatus, passed });
    if (!passed) failures.push(`${name}: expected ${expectedStatus}, received ${response.status}: ${content.slice(0, 300)}`);
    return { response, content };
  };
  const post = (requestOrigin, body = {}) => ({ method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(requestOrigin ? { Origin: requestOrigin } : {}) },
    body: JSON.stringify(body) });
  const { response: configResponse, content } = await check('public portal configuration',
    '/api/v1/integrate/auth/config', {}, 200);
  const configuration = JSON.parse(content);
  assert.deepEqual(Object.keys(configuration).sort(), ['enabled', 'issuer', 'portalUrl']);
  assert.equal(configuration.enabled, true);
  assert.equal(configuration.portalUrl, portal);
  assert.equal(configResponse.headers.get('cache-control'), 'no-store');
  for (const [endpoint, method] of [['login', 'POST'], ['refresh', 'POST'], ['signup', 'POST'],
    ['registrationConfirmation', 'PUT'], ['resendRegistrationToken', 'PUT'],
    ['generatePasswordResetLink', 'POST'], ['password/reset', 'POST'], ['changePassword', 'PUT']]) {
    await check(`native human endpoint closed: ${endpoint}`, `/api/v1/users/${endpoint}`,
      { ...post(origin, { email: 'verification@integrate.invalid', password: 'not-a-credential' }), method }, 403);
  }
  await check('native auth login closed', '/api/v1/auth/login', post(origin), 403);
  const ticket = { code: 'A'.repeat(43), challenge: 'B'.repeat(43) };
  await check('exchange rejects forged Origin', '/api/v1/integrate/auth/exchange',
    post('https://untrusted.invalid', ticket), 403);
  await check('exchange requires Origin', '/api/v1/integrate/auth/exchange', post(undefined, ticket), 403);
  await check('exchange rejects malformed ticket', '/api/v1/integrate/auth/exchange',
    post(origin, { code: 'short', challenge: 'short' }), 400);
  const inactive = await check('real Integrate backchannel rejects nonexistent ticket',
    '/api/v1/integrate/auth/exchange', post(origin, ticket), 401);
  if (inactive.response.status === 401) {
    assert.equal(JSON.parse(inactive.content).error, 'integrate_session_inactive');
  }
  await check('refresh requires same Origin', '/api/v1/auth/refresh',
    post('https://untrusted.invalid'), 400);
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.HOSPITAL_QA_CHROMIUM || undefined });
  try {
    for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
      const context = await browser.newContext({ viewport, locale: 'zh-CN' });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(`${origin}/signin`, { waitUntil: 'domcontentloaded' });
      const portalLink = page.getByRole('link', { name: '进入 Integrate', exact: true });
      await portalLink.waitFor({ timeout: 45000 });
      assert.equal(await portalLink.getAttribute('href'), portal);
      assert.equal(await page.locator('input').count(), 0);
      assert.equal(await page.getByRole('heading', { name: '通过院内门户登录' }).count(), 1);
      assert.equal(await portalLink.evaluate(element => getComputedStyle(element).backgroundColor), 'rgb(18, 99, 75)');
      const dimensions = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth }));
      assert.equal(dimensions.scrollWidth, dimensions.width);
      await page.goto(`${origin}/?integrate_connect=1`, { waitUntil: 'domcontentloaded' });
      await page.getByRole('alert').waitFor({ timeout: 30000 });
      assert.match(await page.getByRole('alert').innerText(), /Integrate/);
      assert.equal(await page.locator('input').count(), 0);
      assert.deepEqual(errors, []);
      report.checks.push({ name: `real deployed portal UI ${viewport.width}`, status: 'pass', passed: true, errors });
      await context.close();
    }
  } finally {
    await browser.close();
    fs.mkdirSync(path.resolve(__dirname, '../../.logs'), { recursive: true });
    fs.writeFileSync(path.resolve(__dirname, '../../.logs/live-deployment-checks.json'), JSON.stringify(report, null, 2));
  }
  console.log(JSON.stringify(report, null, 2));
  assert.deepEqual(failures, [], 'Deployment verification has unresolved failures');
})().catch(error => { console.error(error); process.exitCode = 1; });
