// This verifier never creates an identity or supplies a human/bot credential.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const base = process.env.HOSPITAL_APP_URL || 'http://172.16.120.211:30925';
const task = '00000000-0000-4000-8000-000000000001';
const cases = [
  ['GET', '/status'],
  ['GET', '/connections'],
  ['POST', '/connections/synthetic-source/test', {}],
  ['GET', '/connections/synthetic-source/tables'],
  ['GET', '/tasks'],
  ['POST', '/tasks/validate', {}],
  ['POST', '/tasks', {}],
  ['GET', `/tasks/${task}`],
  ['PUT', `/tasks/${task}`, { version: 1 }],
  ['POST', `/tasks/${task}/run`, { resume: false }],
  ['POST', `/tasks/${task}/stop`, { savepoint: true }],
  ['POST', `/tasks/${task}/catalog`, {}],
];

(async () => {
  const checks = [];
  for (const [method, route, body] of cases) {
    const response = await fetch(`${base}/api/v1/hospital/integration${route}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-Admin': 'true' },
      ...(body ? { body: JSON.stringify(body) } : {}),
      redirect: 'manual',
      signal: AbortSignal.timeout(15000),
    });
    assert.equal(response.status, 401, `${method} ${route} must use native authentication`);
    const result = await response.text();
    assert.ok(!/jdbc:postgresql|HOSPITAL_SOURCE|HOSPITAL_TARGET|password\s*[=:]/i.test(result));
    checks.push({ name: `${method} ${route}`, status: response.status, passed: true });
  }
  const invalidBearer = await fetch(`${base}/api/v1/hospital/integration/status`, {
    headers: { Authorization: 'Bearer invalid-verification-token', 'X-Admin': 'true' },
    redirect: 'manual', signal: AbortSignal.timeout(15000),
  });
  assert.equal(invalidBearer.status, 401);
  checks.push({ name: 'invalid bearer and spoofed admin header rejected', status: 401, passed: true });
  const output = path.resolve(__dirname, '../../.logs/integration-api-boundary.json');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const report = { checkedAt: new Date().toISOString(), scope: 'deployed unauthenticated API boundary only', checks, passed: true };
  fs.writeFileSync(output, JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ checks: checks.length, passed: true }));
})().catch((error) => { console.error(error.message); process.exitCode = 1; });
