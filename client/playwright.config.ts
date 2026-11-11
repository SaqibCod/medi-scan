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

/**
 * `e2e/live/` drives a real backend, a real database and a real model, which this config does
 * not start — it mocks nothing and would fail on every test. It runs on purpose, through
 * `npm run e2e:live` and playwright.live.config.ts.
 *
 * Repeated into the mobile project below, deliberately: a project's `testIgnore` *replaces*
 * the top-level one rather than adding to it, so a project that sets its own silently opts
 * back in to everything this list was holding back.
 */
const NEVER_RUN_HERE = /[\\/]live[\\/]/;

export default defineConfig({
  testDir: "./e2e",
  testIgnore: NEVER_RUN_HERE,
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
    {
      name: "mobile",
      use: { ...devices["Pixel 7"] },
      // The screenshot spec sets its own viewports and writes one file per name, so running
      // it here only duplicated the work — and because Pixel 7 is chromium with a device
      // scale factor of 2.625, it overwrote every desktop shot at 2.6x the size.
      testIgnore: [NEVER_RUN_HERE, /screenshots.*\.spec\.ts/],
    },
  ],

  webServer: {
    command: `npm run build && npx next start --port ${PORT}`,
    url: BASE_URL,
    /**
     * Sign-in is a build-time constant, so it has to be set for the build, not the test.
     *
     * A fixed fake client id rather than whatever is in `.env.local`: the suite replaces
     * Google's script with a stub and never contacts Google, and a run that quietly depended
     * on a developer having configured a real client would pass on one machine and fail on
     * the next. Process environment wins over `.env.local`, so this is what the build sees.
     */
    env: {
      NEXT_PUBLIC_GOOGLE_CLIENT_ID: "e2e-client-id.apps.googleusercontent.com",
      NEXT_PUBLIC_FEATURE_SIGNIN: "true",
    },
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
