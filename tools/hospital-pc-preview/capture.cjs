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
const captureOnly = process.env.HOSPITAL_QA_CAPTURE_ONLY?.split(",");
const folder = path.resolve(
  __dirname,
  "../../.impeccable/review/pc-navigation" + (captureOnly ? "/finish-fix" : "")
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
const report = {
  capturedAt: new Date().toISOString(),
  fixture:
    "Actual LeftSidebar, NavBar, data source/task/detail React components and native drawers. Explicit synthetic HTTP boundary; example.invalid hosts. This validates UI navigation and interaction only, not employee SSO, JDBC extraction or live catalog writes.",
  captures: [],
  checks: [],
};
(async () => {
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.HOSPITAL_QA_CHROMIUM ||
      "C:/Users/Administrator/AppData/Local/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-win64/chrome-headless-shell.exe",
  });
  try {
    for (const viewport of captureOnly
      ? [{ width: 1440, height: 1000 }]
      : [
          { width: 1366, height: 900 },
          { width: 1440, height: 1000 },
          { width: 1920, height: 1080 },
          { width: 1093, height: 720 },
        ]) {
      const context = await browser.newContext({
        viewport,
        reducedMotion: "reduce",
      });
      const page = await context.newPage();
      const errors = [];
      const requests = [];
      let scenario = "normal";
      let currentSources = [...connections];
      page.on("pageerror", (e) => errors.push(e.message));
      await page.route("**/api/v1/**", async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const endpoint = url.pathname.replace(
          "/api/v1/hospital/integration",
          ""
        );
        let data,
          status = 200;
        requests.push({ path: url.pathname, method: request.method() });
        if (!url.pathname.startsWith("/api/v1/hospital/integration"))
          data = url.pathname.includes("/version")
            ? { version: "2.0.4" }
            : { data: [], paging: { total: 0 } };
        else if (endpoint === "/status")
          data = { enabled: true, reachable: true, engineVersion: "3.0.0" };
        else if (endpoint === "/connections") {
          if (scenario === "source-error") {
            status = 503;
            data = { errorCode: "CONNECTION_UNAVAILABLE" };
          } else data = { data: currentSources };
        } else if (endpoint === "/tasks") data = { data: [task] };
        else if (endpoint === "/tasks/" + task.id) data = task;
        else if (endpoint.startsWith("/tasks/")) {
          status = 404;
          data = { errorCode: "TASK_NOT_FOUND" };
        } else if (
          endpoint.endsWith("/table-options") ||
          endpoint.endsWith("/tables")
        ) {
          const id = endpoint.split("/")[2];
          const conn = currentSources.find((c) => c.id === id);
          data = {
            data: [
              {
                schema: conn?.schemas[0] || "public",
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
        } else if (endpoint.endsWith("/test")) data = { connected: true };
        else {
          status = 404;
          data = { errorCode: "SYNTHETIC_PREVIEW_ONLY" };
        }
        await route.fulfill({
          status,
          contentType: "application/json",
          body: JSON.stringify(data),
        });
      });
      const capture = async (name) => {
        if (captureOnly && !captureOnly.includes(name)) return;
        await page.evaluate(() => document.fonts.ready);
        await page.waitForFunction(
          () =>
            [...document.querySelectorAll(".form-drawer__dialog")].every(
              (element) => {
                const r = element.getBoundingClientRect();
                return (
                  r.width > 0 &&
                  Math.abs(r.right - window.innerWidth) < 2 &&
                  Math.abs(r.height - window.innerHeight) < 2
                );
              }
            ),
          undefined,
          { timeout: 5000 }
        );
        const geometry = await page.evaluate(() => {
          const box = (e) => {
            if (!e) return null;
            const r = e.getBoundingClientRect();
            return {
              x: r.x,
              y: r.y,
              width: r.width,
              height: r.height,
              right: r.right,
              bottom: r.bottom,
            };
          };
          const drawer = document.querySelector(".form-drawer__dialog");
          const body = drawer?.querySelector(".form-drawer__body");
          const content = document.querySelector(
            ".hospital-workspace__content"
          );
          return {
            document: {
              clientWidth: document.documentElement.clientWidth,
              scrollWidth: document.documentElement.scrollWidth,
            },
            sidebar: box(
              document.querySelector('[data-testid="left-sidebar"]')
            ),
            content: content
              ? {
                  ...box(content),
                  scrollWidth: content.scrollWidth,
                  clientWidth: content.clientWidth,
                }
              : null,
            drawer: drawer
              ? {
                  ...box(drawer),
                  header: box(drawer.querySelector(".form-drawer__header")),
                  footer: box(drawer.querySelector(".form-drawer__footer")),
                  body: {
                    ...box(body),
                    clientWidth: body.clientWidth,
                    scrollWidth: body.scrollWidth,
                    scrollTop: body.scrollTop,
                    scrollHeight: body.scrollHeight,
                    clientHeight: body.clientHeight,
                  },
                }
              : null,
          };
        });
        assert.ok(
          geometry.document.scrollWidth <= geometry.document.clientWidth + 1,
          name + ": no document overflow"
        );
        assert.ok(
          !geometry.content ||
            geometry.content.scrollWidth <= geometry.content.clientWidth + 1,
          name + ": no content overflow"
        );
        if (geometry.drawer) {
          assert.ok(
            Math.abs(geometry.drawer.right - viewport.width) < 2,
            name + ": right drawer"
          );
          assert.ok(
            geometry.drawer.footer.bottom <= viewport.height + 2,
            name + ": pinned footer visible"
          );
          assert.ok(
            geometry.drawer.body.scrollWidth <=
              geometry.drawer.body.clientWidth + 1,
            name + ": no drawer horizontal overflow"
          );
        }
        if (captureOnly) {
          geometry.colorContrast = await page.evaluate(() =>
            [
              ...document.querySelectorAll(
                '.hospital-integration__status[data-status="FINISHED"],.hospital-integration__status[data-status="SYNCED"],.hospital-integration__alert'
              ),
            ]
              .filter((e) => e.getClientRects().length)
              .map((e) => {
                let current = e,
                  background;
                while (current) {
                  const value = getComputedStyle(current).backgroundColor;
                  if (value !== "rgba(0, 0, 0, 0)" && value !== "transparent") {
                    background = value;
                    break;
                  }
                  current = current.parentElement;
                }
                return {
                  text: e.textContent,
                  color: getComputedStyle(e).color,
                  background: background || "rgb(255, 255, 255)",
                };
              })
          );
          const luminance = (color) => {
            const channels = color
              .match(/\d+(?:\.\d+)?/g)
              .slice(0, 3)
              .map(Number)
              .map((v) => {
                const c = v / 255;
                return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
              });
            return (
              channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
            );
          };
          for (const sample of geometry.colorContrast) {
            const f = luminance(sample.color),
              b = luminance(sample.background);
            sample.ratio = (Math.max(f, b) + 0.05) / (Math.min(f, b) + 0.05);
            assert.ok(
              sample.ratio >= 4.5,
              name + ": semantic state text contrast " + sample.ratio
            );
          }
        }
        assert.deepEqual(errors, [], name + ": no browser exceptions");
        const file = name + "-" + viewport.width + ".png";
        await page.screenshot({
          path: path.join(folder, file),
          animations: "disabled",
          fullPage: true,
        });
        report.captures.push({
          file,
          viewport,
          url: page.url(),
          geometry,
          errors: [...errors],
        });
      };
      await page.goto(base + sourcePath, { waitUntil: "networkidle" });
      await page
        .getByRole("heading", { level: 1, name: nav.sources, exact: true })
        .waitFor();
      assert.equal(
        await page.getByRole("tab").count(),
        0,
        "object pages do not use tabs"
      );
      assert.equal(
        await page
          .getByTestId("integration-connections-table")
          .locator("tbody tr")
          .count(),
        5
      );
      assert.equal(
        requests.filter(
          (r) => r.path.endsWith("/tasks") || r.path.endsWith("/status")
        ).length,
        0,
        "source page has its own request boundary"
      );
      await capture("sources");
      await page
        .getByRole("button", { name: copy.createConnection, exact: true })
        .click();
      await page.getByTestId("integration-connection-form").waitFor();
      await capture("source-create");
      await page
        .getByRole("button", { name: locale.label.cancel, exact: true })
        .click();
      await page
        .getByTestId("integration-connection-row-oracle-his")
        .getByRole("button", { name: locale.label.edit, exact: true })
        .click();
      await capture("source-edit");
      assert.equal(
        await page.locator("#connection-password").inputValue(),
        "",
        "stored secret is not shown"
      );
      await page
        .getByRole("button", { name: locale.label.cancel, exact: true })
        .click();
      await page.getByTestId("app-bar-item-hospital-integration-tasks").click();
      await page
        .getByRole("heading", { level: 1, name: nav.tasks, exact: true })
        .waitFor();
      assert.equal(new URL(page.url()).pathname, taskPath);
      await capture("tasks");
      await page
        .getByRole("button", { name: copy.createTask, exact: true })
        .click();
      await page.getByTestId("integration-task-form").waitFor();
      await capture("task-create");
      await page
        .getByRole("button", { name: locale.label.cancel, exact: true })
        .click();
      await page.locator("#integration-collection-search").fill("synthetic");
      await page
        .getByRole("button", { name: task.displayName, exact: true })
        .click();
      await page.getByTestId("integration-task-detail").waitFor();
      assert.ok(page.url().includes("/tasks/" + task.id + "?q=synthetic"));
      await capture("task-detail");
      await page
        .getByRole("button", { name: locale.label.edit, exact: true })
        .click();
      await page.getByTestId("integration-task-form").waitFor();
      await capture("task-edit");
      await page
        .locator(".form-drawer__body")
        .evaluate((e) => (e.scrollTop = e.scrollHeight));
      await capture("task-edit-mapping-end");
      await page
        .getByRole("button", { name: locale.label.cancel, exact: true })
        .click();
      await page
        .getByRole("button", { name: copy.backToTasks, exact: true })
        .click();
      assert.equal(
        await page.locator("#integration-collection-search").inputValue(),
        "synthetic",
        "list filters survive detail navigation"
      );
      if (viewport.width === 1440) {
        await page
          .getByTestId("app-bar-item-hospital-integration-sources")
          .click();
        await page.locator("#integration-collection-search").fill("Oracle");
        await expect(
          page.getByTestId("integration-connections-table").locator("tbody tr")
        ).toHaveCount(1);
        await capture("source-filtered");
        await page
          .getByRole("button", { name: locale.label.reset, exact: true })
          .click();
        await page.getByTestId("sidebar-toggle").click();
        await page.waitForFunction(
          () =>
            document
              .querySelector('[data-testid="left-sidebar"]')
              .getBoundingClientRect().width < 73
        );
        await page.waitForFunction(() =>
          [
            ...document.querySelectorAll(
              ".ant-menu-inline-collapsed > .ant-menu-item > .ant-menu-item-icon, .ant-menu-inline-collapsed > .ant-menu-submenu > .ant-menu-submenu-title > .ant-menu-item-icon"
            ),
          ].every((e) => {
            const r = e.getBoundingClientRect();
            return r.x >= 0 && r.right <= 72;
          })
        );
        await capture("sidebar-collapsed");
        await page.getByTestId("sidebar-toggle").click();
        for (const key of ["assets", "quality", "governance", "knowledge"]) {
          await page.getByText(nav[key], { exact: true }).click();
          await capture("menu-" + key);
        }
        await page.goto(base + sourcePath, { waitUntil: "networkidle" });
        await page
          .getByTestId("integration-connection-row-oracle-his")
          .getByRole("button", { name: locale.label.edit, exact: true })
          .click();
        await page
          .locator("#connection-displayName")
          .fill("Oracle HIS 采集库（修改示例）");
        await page
          .getByRole("button", { name: locale.label.cancel, exact: true })
          .click();
        await page
          .getByRole("button", {
            name: locale.label["continue-editing"],
            exact: true,
          })
          .waitFor();
        await capture("source-discard-confirm");
        await page
          .getByRole("button", { name: locale.label.discard, exact: true })
          .click();
        await page.goto(base + taskPath + "/" + task.id, {
          waitUntil: "networkidle",
        });
        await page.getByTestId("integration-task-detail").waitFor();
        await page
          .getByRole("button", { name: locale.label.edit, exact: true })
          .click();
        await page
          .locator("#integration-task-displayName")
          .fill("合成就诊数据采集（修改示例）");
        await page
          .getByRole("button", { name: locale.label.cancel, exact: true })
          .click();
        await page
          .getByRole("button", {
            name: locale.label["continue-editing"],
            exact: true,
          })
          .waitFor();
        await capture("task-discard-confirm");
        await page
          .getByRole("button", { name: locale.label.discard, exact: true })
          .click();
        scenario = "source-error";
        await page.goto(base + sourcePath, { waitUntil: "networkidle" });
        await page.getByRole("alert").waitFor();
        await expect(
          page.getByRole("button", { name: copy.createConnection, exact: true })
        ).toBeDisabled();
        await capture("source-error");
        scenario = "normal";
        await page.goto(base + taskPath + "/missing", {
          waitUntil: "networkidle",
        });
        await page.getByRole("alert").waitFor();
        await capture("task-missing");
        await page.goto(base + sourcePath + "?role=employee", {
          waitUntil: "networkidle",
        });
        await page
          .getByRole("heading", { name: copy.adminOnly, exact: true })
          .waitFor();
        await capture("source-employee");
        await page.goto(base + taskPath + "?role=employee", {
          waitUntil: "networkidle",
        });
        await page
          .getByRole("heading", { name: copy.adminOnly, exact: true })
          .waitFor();
        await capture("task-employee");
        await page.goto(
          base +
            "/hospital/integration?mode=FULL&ticket=synthetic-ui-test&integrate_connect=1",
          { waitUntil: "networkidle" }
        );
        assert.equal(new URL(page.url()).pathname, taskPath);
        assert.equal(
          new URL(page.url()).search,
          "?mode=FULL",
          "legacy path drops ticket"
        );
      }
      report.checks.push({
        viewport,
        passed: true,
        note:
          viewport.width === 1093
            ? "Reduced CSS viewport equivalent to a 1366px desktop at 125% scaling; no mobile layout."
            : "PC desktop layout",
        requests: requests.length,
      });
      await context.close();
    }
  } finally {
    fs.writeFileSync(
      path.join(folder, "report.json"),
      JSON.stringify(report, null, 2)
    );
    await browser.close();
  }
  console.log(
    JSON.stringify({
      captures: report.captures.length,
      checks: report.checks,
      errors: report.captures.flatMap((c) => c.errors),
    })
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
