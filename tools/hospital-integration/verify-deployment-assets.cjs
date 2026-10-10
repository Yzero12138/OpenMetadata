const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

(async () => {
  const origin = new URL(process.env.HOSPITAL_APP_URL || 'http://172.16.120.211:30925');
  const repository = path.resolve(__dirname, '../..');
  const dist = path.join(repository, 'openmetadata-ui/src/main/resources/ui/dist');
  const assets = fs.readdirSync(path.join(dist, 'assets'));
  const resources = ['index.html'];
  for (const prefix of ['index-', 'HospitalIntegrationPage-', 'WorkflowsPage-', 'WorkflowBuilder-']) {
    for (const extension of ['.js', '.css']) {
      const matches = assets.filter(name => name.startsWith(prefix) && name.endsWith(extension));
      if (prefix === 'index-') assert.ok(matches.length > 0, `Current index ${extension} assets`);
      else assert.equal(matches.length, 1, `One current ${prefix}*${extension} asset`);
      resources.push(...matches.map(name => `assets/${name}`));
    }
  }
  const hash = content => crypto.createHash('sha256').update(content).digest('hex');
  const report = { checkedAt: new Date().toISOString(), checks: [] };
  for (const resource of resources) {
    const response = await fetch(new URL(resource === 'index.html' ? '/' : `/${resource}`, origin), {
      headers: { 'Cache-Control': 'no-cache' }, signal: AbortSignal.timeout(20000),
    });
    assert.equal(response.status, 200, resource);
    const actual = Buffer.from(await response.arrayBuffer());
    const expected = fs.readFileSync(path.join(dist, resource));
    if (resource === 'index.html') {
      const html = actual.toString('utf8');
      const nonce = html.match(/nonce="([A-Za-z0-9+/=]+)"/)?.[1];
      assert.ok(nonce, 'Server CSP nonce');
      assert.equal(Buffer.from(nonce, 'base64').length, 16, 'Native CSP nonce length');
      const rendered = expected.toString('utf8').replace(/\r\n|\r/g, '\n').replace(/\n$/, '')
        .replaceAll('${basePath}', '/').replaceAll('${cspNonce}', nonce);
      assert.equal(html, rendered, 'Native index rendering uses the current build');
      report.checks.push({ resource, passed: true, templateMatches: true });
    } else {
      assert.equal(hash(actual), hash(expected), resource);
      report.checks.push({ resource, passed: true, sha256: hash(actual) });
    }
  }
  const reportFile = path.join(repository, '.logs/integration-deployed-assets.json');
  fs.mkdirSync(path.dirname(reportFile), { recursive: true });
  fs.writeFileSync(reportFile, JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
