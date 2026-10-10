import { realpathSync } from "node:fs";
import path from "node:path";
import baseConfig from "../../openmetadata-ui/src/main/resources/ui/vite.config";
const ui = path.resolve(
  __dirname,
  "../../openmetadata-ui/src/main/resources/ui"
);
export default async (context: Parameters<typeof baseConfig>[0]) => {
  const config = await baseConfig(context);
  return {
    ...config,
    root: __dirname,
    publicDir: path.join(ui, "public"),
    cacheDir: path.resolve(__dirname, "../../.cache/vite-hospital-pc-preview"),
    resolve: {
      ...config.resolve,
      alias: {
        ...config.resolve?.alias,
        react: path.join(ui, "node_modules/react"),
        "react-dom": path.join(ui, "node_modules/react-dom"),
        "react-router-dom": path.join(ui, "node_modules/react-router-dom"),
        "react-helmet-async": path.join(ui, "node_modules/react-helmet-async"),
      },
    },
    server: {
      host: "127.0.0.1",
      port: 3002,
      strictPort: true,
      open: false,
      proxy: {},
      fs: {
        allow: [
          path.resolve(__dirname, "../.."),
          path.resolve(
            realpathSync(path.join(ui, "node_modules")),
            "../../../../../.."
          ),
        ],
      },
    },
  };
};
