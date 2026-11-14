const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  chromium,
  expect,
} = require("../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test");
const locale = require("../../openmetadata-ui/src/main/resources/ui/src/locale/languages/zh-cn.json");
const copy = locale.hospitalIntegration;
const nav = locale.hospitalNavigation;
const base = "http://127.0.0.1:3002";
const sourcePath = "/hospital/integration/sources";
const taskPath = "/hospital/integration/tasks";
const folder = path.resolve(
  __dirname,
  "../../.impeccable/review/pc-navigation"
);
const source = (id, type, role, name, extra = {}) => ({
  id,
  name: id.replaceAll("-", "_"),
  displayName: name,
  databaseType: type,
  databaseVersion:
    type === "Oracle"
      ? "19c"
      : type === "Mssql"
      ? "2019"
      : type === "Mysql"
      ? "8"
      : "17",
  role,
  host: id + ".example.invalid",
  port:
    type === "Oracle"
      ? 1521
      : type === "Mssql"
      ? 1433
      : type === "Mysql"
      ? 3306
      : 5432,
  database: "synthetic_business",
  username: "synthetic_reader",
  schemas:
    type === "Oracle" ? ["HIS"] : type === "Mssql" ? ["dbo"] : ["public"],
  tlsMode: "VERIFY",
  enabled: true,
  version: 1,
  managed: false,
  synthetic: true,
  passwordSet: true,
  supportedModes: ["FULL"],
  ...extra,
});
const connections = [
  source("synthetic-source", "Postgres", "SOURCE", "合成业务源", {
    managed: true,
    supportedModes: ["FULL", "CDC"],
  }),
  source("synthetic-ods", "Postgres", "TARGET", "合成 ODS 目标", {
    managed: true,
    schemas: ["ods"],
  }),
  source("oracle-his", "Oracle", "SOURCE", "Oracle HIS 采集库（示例）"),
  source("mssql-lis", "Mssql", "SOURCE", "SQL Server LIS 采集库（示例）"),
  source("mysql-ops", "Mysql", "SOURCE", "MySQL 运营库（示例）"),
];
const columns = [
  { name: "visit_id", dataType: "BIGINT", nullable: false, primaryKey: true },
  ...Array.from({ length: 29 }, (_, i) => ({
    name: "field_" + String(i + 1).padStart(2, "0"),
    dataType: "VARCHAR",
    nullable: true,
    primaryKey: false,
  })),
];
const task = {
  id: "00000000-0000-4000-8000-000000000101",
  name: "synthetic_encounter_full",
  displayName: "合成就诊数据采集",
  version: 7,
  sourceConnectionId: "oracle-his",
  targetConnectionId: "synthetic-ods",
  sourceSchema: "HIS",
  sourceTable: "synthetic_encounter",
  targetSchema: "ods",
  targetTable: "synthetic_encounter",
  mode: "FULL",
  primaryKey: "visit_id",
  fieldMappings: columns.map((c) => ({ source: c.name, target: c.name })),
  createdAt: 1791590400000,
  updatedAt: 1791590400000,
  updatedBy: "synthetic-ui-review",
  runs: [],
  latestRun: {
    jobId: "9223372036854775807",
    status: "FINISHED",
    submittedAt: 1791590400000,
    finishedAt: 1791590410000,
  },
  catalog: { status: "SYNCED", syncedAt: 1791590410000 },
};

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.HOSPITAL_QA_CHROMIUM ||
      "C:/Users/Administrator/AppData/Local/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-win64/chrome-headless-shell.exe",
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    reducedMotion: "reduce",
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  try {
    await page.route("**/api/v1/**", async (route) => {
      const p = new URL(route.request().url()).pathname;
      const endpoint = p.replace("/api/v1/hospital/integration", "");
      let data = { data: [], paging: { total: 0 } };
      if (endpoint === "/connections") data = { data: connections };
      else if (endpoint === "/tasks") data = { data: [task] };
      else if (endpoint === "/tasks/" + task.id) data = task;
      else if (endpoint === "/status")
        data = { enabled: true, reachable: true, engineVersion: "3.0.0" };
      else if (endpoint.endsWith("/table-options")) {
        const id = endpoint.split("/")[2];
        const c = connections.find((c) => c.id === id);
        data = {
          data: [
            {
              schema: c?.schemas[0] || "public",
              name: "synthetic_encounter",
              columns: [],
            },
          ],
        };
      } else if (/\/tables\/[^/]+\/[^/]+$/.test(endpoint)) {
        const parts = endpoint.split("/");
        data = {
          schema: decodeURIComponent(parts.at(-2)),
          name: decodeURIComponent(parts.at(-1)),
          columns,
        };
      } else if (p.includes("/version")) data = { version: "2.0.4" };
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    });
    const historyStep = async (delta) => {
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve))
          )
      );
      await page.evaluate((d) => history.go(d), delta);
      await expect(
        page.getByRole("button", {
          name: locale.label["continue-editing"],
          exact: true,
        })
      ).toBeVisible();
    };
    const stay = async () =>
      page
        .getByRole("button", {
          name: locale.label["continue-editing"],
          exact: true,
        })
        .click();
    const discard = async () =>
      page
        .getByRole("button", { name: locale.label.discard, exact: true })
        .click();
    await page.goto(base + sourcePath, { waitUntil: "networkidle" });
    await page.getByTestId("app-bar-item-hospital-integration-tasks").click();
    await page
      .getByRole("button", { name: task.displayName, exact: true })
      .click();
    await page.getByTestId("integration-task-detail").waitFor();
    const taskURL = page.url();
    const detailIndex = await page.evaluate(() => history.state.idx);
    await page
      .getByRole("button", { name: locale.label.edit, exact: true })
      .click();
    await page
      .locator("#integration-task-displayName")
      .fill("任务导航保护（合成验证）");
    await historyStep(-1);
    await expect(page).toHaveURL(taskURL);
    assert.equal(await page.evaluate(() => history.state.idx), detailIndex);
    await stay();
    await expect(page.locator("#integration-task-displayName")).toHaveValue(
      "任务导航保护（合成验证）"
    );
    await historyStep(-1);
    await discard();
    await expect(page).toHaveURL(base + taskPath);
    await expect(page.getByTestId("integration-task-drawer")).toHaveCount(0);
    await page
      .getByRole("button", { name: copy.createTask, exact: true })
      .click();
    await page
      .locator("#integration-task-displayName")
      .fill("前进保护（合成验证）");
    await historyStep(1);
    await expect(page).toHaveURL(base + taskPath);
    await stay();
    await expect(page.locator("#integration-task-displayName")).toHaveValue(
      "前进保护（合成验证）"
    );
    await historyStep(1);
    await discard();
    await expect(page).toHaveURL(taskURL);
    await page.getByTestId("integration-task-detail").waitFor();
    await page.getByTestId("app-bar-item-hospital-integration-sources").click();
    await page
      .getByTestId("integration-connection-row-oracle-his")
      .getByRole("button", { name: locale.label.edit, exact: true })
      .click();
    await page
      .locator("#connection-displayName")
      .fill("数据源导航保护（合成验证）");
    const sourceURL = page.url();
    await historyStep(-1);
    await expect(page).toHaveURL(sourceURL);
    await stay();
    await expect(page.locator("#connection-displayName")).toHaveValue(
      "数据源导航保护（合成验证）"
    );
    await historyStep(-1);
    await discard();
    await expect(page).toHaveURL(taskURL);
    await page.getByTestId("integration-task-detail").waitFor();
    const legacyChecks = [];
    const verifyLegacyEntries = async (navigationAPI) => {
      await page.goto(base + taskPath, { waitUntil: "networkidle" });
      if (!navigationAPI)
        assert.equal(await page.evaluate(() => window.navigation), undefined);
      await page.evaluate(() => history.replaceState(null, "", location.href));
      await page
        .getByTestId("app-bar-item-hospital-integration-sources")
        .click();
      const length = await page.evaluate(() => history.length);
      await page
        .getByTestId("integration-connection-row-oracle-his")
        .getByRole("button", { name: locale.label.edit, exact: true })
        .click();
      await page.locator("#connection-displayName").fill("旧历史兼容验证");
      await historyStep(-1);
      await expect(page).toHaveURL(base + sourcePath);
      assert.equal(await page.evaluate(() => history.length), length);
      await stay();
      await expect(page.locator("#connection-displayName")).toHaveValue(
        "旧历史兼容验证"
      );
      await historyStep(-1);
      await discard();
      await expect(page).toHaveURL(base + taskPath);
      assert.equal(await page.evaluate(() => history.state), null);
      await page
        .getByRole("button", { name: copy.createTask, exact: true })
        .click();
      await page
        .locator("#integration-task-displayName")
        .fill("旧历史前进验证");
      await historyStep(1);
      await expect(page).toHaveURL(base + taskPath);
      assert.equal(await page.evaluate(() => history.state), null);
      await stay();
      await expect(page.locator("#integration-task-displayName")).toHaveValue(
        "旧历史前进验证"
      );
      await historyStep(1);
      await discard();
      await expect(page).toHaveURL(base + sourcePath);
      assert.equal(await page.evaluate(() => history.length), length);
      legacyChecks.push(
        navigationAPI
          ? "native Navigation API: null prior/forward entry retains URL, drafts and original history"
          : "legacy fallback without Navigation API: adjacent null back/forward and stack-end restore without rewriting history"
      );
    };
    await verifyLegacyEntries(true);
    await page.addInitScript(() =>
      Object.defineProperty(window, "navigation", {
        value: undefined,
        configurable: true,
      })
    );
    await verifyLegacyEntries(false);
    // A second native Back starts while the first restoration is still queued.
    await page.addInitScript(() => {
      window.__rapidBackRequested = false;
      window.addEventListener("popstate", () => {
        if (window.__rapidBackRequested) {
          window.__rapidBackRequested = false;
          history.go(-1);
        }
      });
    });
    await page.goto(base + sourcePath, { waitUntil: "networkidle" });
    await page.getByTestId("app-bar-item-hospital-integration-tasks").click();
    await page
      .getByRole("button", { name: task.displayName, exact: true })
      .click();
    await page.getByTestId("integration-task-detail").waitFor();
    const rapidURL = page.url();
    const rapidIndex = await page.evaluate(() => history.state.idx);
    await page.evaluate(() => {
      window.__rapidBackRequested = true;
    });
    await page
      .getByRole("button", { name: locale.label.edit, exact: true })
      .click();
    await page
      .locator("#integration-task-displayName")
      .fill("连续后退保护验证");
    await historyStep(-1);
    await expect(page).toHaveURL(rapidURL);
    assert.equal(await page.evaluate(() => history.state.idx), rapidIndex);
    await stay();
    await expect(page.locator("#integration-task-displayName")).toHaveValue(
      "连续后退保护验证"
    );
    legacyChecks.push(
      "rapid consecutive native Back: restore original entry before confirmation and retain task draft"
    );
    assert.deepEqual(errors, []);
    const result = {
      passed: true,
      capturedAt: new Date().toISOString(),
      checks: [
        "task browser back: restore URL/index, retain draft, discard then leave",
        "task browser forward: retain draft, discard then proceed",
        "source browser back: retain draft, discard then proceed",
        "no added history guard entries",
        "no browser exceptions",
        ...legacyChecks,
      ],
      fixture:
        "Isolated synthetic data; no employee auth or database operations",
    };
    fs.writeFileSync(
      path.join(folder, "navigation-behavior.json"),
      JSON.stringify(result, null, 2)
    );
    console.log(JSON.stringify(result));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
