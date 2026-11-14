# Hospital PC UI verification fixture

This fixture mounts the actual production sidebar, top bar, route components and right drawers. It uses a synthetic administrator/employee and intercepts HTTP requests inside Playwright. All connection hosts use example.invalid. It does not log in an employee, contact a business database, launch SeaTunnel jobs or write catalog assets.

Install the main UI dependencies first. From the repository root, start the fixture in a terminal:

```powershell
node openmetadata-ui/src/main/resources/ui/node_modules/vite/bin/vite.js --config tools/hospital-pc-preview/vite.config.ts
```

The server listens only on 127.0.0.1:3002. To use a different installed Chromium executable, set HOSPITAL_QA_CHROMIUM. The default matches the Windows verification host. Run the browser helpers from a second terminal:

```powershell
node tools/hospital-pc-preview/capture.cjs
node tools/hospital-pc-preview/verify-navigation.cjs
node tools/hospital-pc-preview/probe-collapse.cjs
```

Outputs go to the ignored .impeccable/review/pc-navigation directory. report.json lists the 44 screenshots and four viewport geometry checks: 1366×900, 1440×1000, 1920×1080 and 1093×720 (reduced usable width for PC scaling). navigation-behavior.json records actual native Chromium Back/Forward, draft protection, null-history entries with/without Navigation API and consecutive traversal. The legacy fallback coverage is adjacent unknown entries, not arbitrary multi-entry legacy-browser jumps.

Capture helpers fulfill only synthetic reads; mutations are not a live integration acceptance test. See docs/hospital/ui-redesign-phase1-verification.md for the delivered scope and evidence boundaries.

For a directed finish-review correction only, HOSPITAL_QA_CAPTURE_ONLY accepts comma-separated capture names. The script then captures the named states at1440×1000 into finish-fix and asserts4.5:1 minimum semantic status/error text contrast. Leave it unset for the full matrix.
