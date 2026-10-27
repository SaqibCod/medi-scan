import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against a production build, never the dev server.
 *
 * That is not pedantry: development adds `'unsafe-eval'` to the CSP and skips
 * `upgrade-insecure-requests`, so a dev-server run would pass while the thing we actually
 * ship stayed untested. The backend is intercepted per test rather than run, so the suite
 * needs nothing but the client.
 */

const PORT = Number(process.env.E2E_PORT ?? 3210);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],

  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
  },

  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],

  webServer: {
    command: `npm run build && npx next start --port ${PORT}`,
    url: BASE_URL,
    // Never reuse. The suite exists to test the build it just made, and silently attaching to
    // a server somebody left running — serving an older build — produces a page of failures
    // that have nothing to do with the code. It costs a rebuild and removes a whole class of
    // confusing results.
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
