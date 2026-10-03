import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // Resolves the `@/*` alias from tsconfig.json, so tests import modules the same way the
    // app does. Native in Vite 7+, which is why vite-tsconfig-paths is not a dependency.
    tsconfigPaths: true,
  },
  test: {
    // jsdom supplies `sessionStorage`, which the API client's guest-session handling needs.
    environment: "jsdom",
    include: ["**/*.test.ts", "**/*.test.tsx"],
    exclude: ["node_modules/**", ".next/**"],
  },
});
