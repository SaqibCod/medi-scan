import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // Resolves the `@/*` alias from tsconfig.json, so tests import modules the same way the
    // app does. Native in Vite 7+, which is why vite-tsconfig-paths is not a dependency.
    tsconfigPaths: true,
  },
  test: {
    // jsdom supplies `sessionStorage`, `localStorage` and a DOM, which the API client, the
    // guest session and every component test need.
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    include: ["**/*.test.ts", "**/*.test.tsx"],
    exclude: ["node_modules/**", ".next/**", "e2e/**"],
  },
});
