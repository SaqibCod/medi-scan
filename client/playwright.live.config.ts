import { defineConfig, devices } from "@playwright/test";

/**
 * The live run: the real backend, the real database, the real model.
 *
 * Separate from `playwright.config.ts` because it must NOT start its own web server — it
 * drives a stack that is already running — and because it needs minutes per test rather
 * than seconds. Run on purpose:
 *
 *   docker compose up -d
 *   cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
 *   cd client && npx next start --port 3000
 *   npx playwright test --config=playwright.live.config.ts
 */
export default defineConfig({
  testDir: "./e2e/live",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  timeout: 180_000,

  use: {
    baseURL: process.env.LIVE_SITE ?? "http://localhost:3000",
    trace: "retain-on-failure",
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } },
    },
  ],
});
