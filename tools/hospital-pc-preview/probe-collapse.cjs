const {
  chromium,
} = require("../../openmetadata-ui/src/main/resources/ui/node_modules/@playwright/test");
(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath:
      "C:/Users/Administrator/AppData/Local/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-win64/chrome-headless-shell.exe",
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/v1/**", (route) => {
    const url = route.request().url();
    let data;
    if (url.endsWith("/status"))
      data = { enabled: true, reachable: true, engineVersion: "3.0.0" };
    else if (url.includes("/version")) data = { version: "2.0.4" };
    else data = { data: [], paging: { total: 0 } };
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(data),
    });
  });
  await page.goto("http://127.0.0.1:3002/hospital/integration/sources", {
    waitUntil: "networkidle",
    timeout: 90000,
  });
  await page.getByTestId("sidebar-toggle").click();
  await page.waitForFunction(
    () =>
      document
        .querySelector("[data-testid=left-sidebar]")
        .getBoundingClientRect().width === 72
  );
  await page.waitForFunction(() =>
    [...document.querySelectorAll(".left-sidebar-menu")].every((e) =>
      e.classList.contains("ant-menu-inline-collapsed")
    )
  );
  await page.waitForFunction(() =>
    [...document.querySelectorAll(".left-sidebar-menu")].every(
      (e) => Math.abs(e.getBoundingClientRect().width - 72) < 0.1
    )
  );
  const result = await page.evaluate(() => {
    const roots = [...document.querySelectorAll(".left-sidebar-menu")];
    const readElement = (element) => ({
      tag: element.tagName,
      classes: element.className,
      display: getComputedStyle(element).display,
      width: getComputedStyle(element).width,
      x: element.getBoundingClientRect().x,
    });
    const labels = roots.flatMap((root) => [
      ...root.querySelectorAll(
        ":scope > .ant-menu-item > .ant-menu-title-content, :scope > .ant-menu-submenu > .ant-menu-submenu-title > .ant-menu-title-content"
      ),
    ]);
    const icons = roots.flatMap((root) => [
      ...root.querySelectorAll(
        ":scope > .ant-menu-item > .anticon, :scope > .ant-menu-submenu > .ant-menu-submenu-title > .anticon"
      ),
    ]);
    const matchedStyles = [];
    const sheets = [...document.styleSheets].map((sheet) => {
      const owner = sheet.ownerNode;
      let rules = [];
      let error;
      try {
        rules = [...sheet.cssRules];
      } catch (e) {
        error = e.message;
      }
      const walk = (rules) =>
        rules.flatMap((rule) => [
          rule,
          ...(rule.cssRules ? walk([...rule.cssRules]) : []),
        ]);
      const allRules = walk(rules);
      for (const rule of allRules) {
        if (!rule.selectorText || !rule.style) continue;
        const targets = [
          ["root", roots[0]],
          ["label", labels[0]],
          ["icon", icons[0]],
          ["title", labels[0].parentElement],
        ].filter(([, element]) => element.matches(rule.selectorText));
        if (
          targets.length &&
          [
            "display",
            "width",
            "padding",
            "padding-inline",
            "margin",
            "margin-inline",
            "flex",
            "justify-content",
          ].some((property) => rule.style.getPropertyValue(property))
        ) {
          matchedStyles.push({
            targets: targets.map(([name]) => name),
            selector: rule.selectorText,
            css: rule.style.cssText,
            source: owner.getAttribute("data-vite-dev-id") || sheet.href,
          });
        }
      }
      const text = owner.textContent || "";
      const position = text.indexOf(
        ".left-sidebar-menu.ant-menu-inline-collapsed"
      );
      return {
        href: sheet.href,
        id: owner.id,
        viteId: owner.getAttribute("data-vite-dev-id"),
        ruleCount: allRules.length,
        error,
        source:
          position < 0 ? undefined : text.slice(position, position + 1250),
        collapsedRules: allRules
          .filter(
            (rule) =>
              rule.selectorText?.includes("left-sidebar-menu") &&
              rule.selectorText?.includes("inline-collapsed")
          )
          .map((rule) => ({
            selector: rule.selectorText,
            css: rule.style.cssText,
            matchingLabels: labels.filter((label) =>
              label.matches(rule.selectorText)
            ).length,
          })),
      };
    });
    return {
      roots: roots.map(readElement),
      labels: labels.map(readElement),
      icons: icons.map(readElement),
      sheets: sheets.filter(
        (sheet) => sheet.source || sheet.collapsedRules.length
      ),
      matchedStyles,
    };
  });
  const checks = {
    labelsHidden:
      result.labels.length > 0 &&
      result.labels.every((label) => label.display === "none"),
    iconsInsideSidebar:
      result.icons.length > 0 &&
      result.icons.every(
        (icon) => icon.x >= 0 && icon.x + Number.parseFloat(icon.width) <= 72
      ),
    menusWidth72: result.roots.every(
      (root) => Math.abs(Number.parseFloat(root.width) - 72) < 0.1
    ),
  };
  console.log(
    JSON.stringify(
      {
        checks,
        rootWidths: result.roots.map((root) => root.width),
        labelDisplays: result.labels.map((label) => label.display),
        iconPositions: result.icons.map((icon) => ({
          x: icon.x,
          width: icon.width,
        })),
        errors,
      },
      null,
      2
    )
  );
  if (process.env.HOSPITAL_COLLAPSE_DIAGNOSTICS === "1")
    console.log(JSON.stringify(result, null, 2));
  await browser.close();
  if (Object.values(checks).some((passed) => !passed) || errors.length)
    throw new Error("Collapsed sidebar browser checks failed");
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
