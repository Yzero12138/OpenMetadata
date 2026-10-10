import path from 'node:path';
import fs from 'node:fs';
import baseConfig from '../../openmetadata-ui/src/main/resources/ui/vite.config';

const ui = path.resolve(__dirname, '../../openmetadata-ui/src/main/resources/ui');
const demoTables = [
  { id: 'synthetic-his', name: 'encounters', displayName: '模拟 · HIS 就诊记录',
    fullyQualifiedName: 'HIS_DEMO.hospital.public.encounters',
    service: { name: 'HIS_DEMO', displayName: 'HIS 测试库' }, owners: [{ name: '信息科' }] },
  { id: 'synthetic-lis', name: 'lab_results', displayName: '模拟 · LIS 检验结果',
    fullyQualifiedName: 'LIS_DEMO.hospital.public.lab_results',
    service: { name: 'LIS_DEMO', displayName: 'LIS 测试库' }, owners: [] },
];

// Local visual verification only. This tool is outside the shipped UI and has
// no employee accounts or production authentication bypass.
export default async (context: Parameters<typeof baseConfig>[0]) => {
  const config = await baseConfig(context);
  const workflowDirectory = path.resolve(__dirname, '../../openmetadata-service/src/main/resources/json/data/governance/workflows');
  const syntheticWorkflows = fs.readdirSync(workflowDirectory).filter(file => file.endsWith('.json')).map(file => {
    const workflow = JSON.parse(fs.readFileSync(path.join(workflowDirectory, file), 'utf8'));
    return { ...workflow, id: `synthetic-${workflow.name}`, fullyQualifiedName: workflow.name };
  });
  return {
    ...config,
    root: __dirname,
    publicDir: path.join(ui, 'public'),
    cacheDir: path.resolve(__dirname, '../../.cache/vite-hospital-preview'),
    resolve: { ...config.resolve, alias: {
      ...config.resolve?.alias,
      react: path.join(ui, 'node_modules/react'),
      'react-dom': path.join(ui, 'node_modules/react-dom'),
      'react-router-dom': path.join(ui, 'node_modules/react-router-dom'),
      'react-helmet-async': path.join(ui, 'node_modules/react-helmet-async'),
    } },
    plugins: [...(config.plugins || []), {
      name: 'hospital-synthetic-fixtures',
      configureServer(server) {
        server.middlewares.use((request, response, next) => {
          const url = new URL(request.url || '/', 'http://127.0.0.1:3001');
          if (!url.pathname.startsWith('/api/v1/')) { next(); return; }
          response.setHeader('Content-Type', 'application/json');
          response.setHeader('Cache-Control', 'no-store');
          if (request.method !== 'GET') {
            response.statusCode = 405;
            response.end(JSON.stringify({ error: 'synthetic_preview_read_only' })); return;
          }
          let data;
          if (url.pathname === '/api/v1/governance/workflowDefinitions') {
            data = { data: syntheticWorkflows, paging: { total: syntheticWorkflows.length } };
            response.end(JSON.stringify(data)); return;
          }
          if (url.pathname.startsWith('/api/v1/governance/workflowDefinitions/name/')) {
            data = syntheticWorkflows.find(workflow => workflow.name === decodeURIComponent(url.pathname.split('/').pop() || ''));
            response.end(JSON.stringify(data || {})); return;
          }
          if (url.pathname === '/api/v1/governance/workflowInstances') {
            response.end(JSON.stringify({ data: [], paging: { total: 0 } })); return;
          }
          switch (url.pathname) {
            case '/api/v1/integrate/auth/config':
              data = { enabled: true, issuer: 'http://127.0.0.1:3002', portalUrl: 'http://127.0.0.1:3002/s/portal' }; break;
            case '/api/v1/tables': data = { data: demoTables, paging: { total: 2 } }; break;
            case '/api/v1/glossaries': data = { data: [], paging: { total: 1 } }; break;
            case '/api/v1/dataQuality/testCases': data = { data: [], paging: { total: 6 } }; break;
            case '/api/v1/services/databaseServices': data = { data: [], paging: { total: 2 } }; break;
            default: response.statusCode = 404; data = { error: 'synthetic_preview_only' };
          }
          response.end(JSON.stringify(data));
        });
      },
    }],
    server: { host: '127.0.0.1', port: 3001, strictPort: true, open: false,
      proxy: {}, fs: { allow: [path.resolve(__dirname, '../..')] } },
  };
};
