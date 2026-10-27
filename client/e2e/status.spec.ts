import { expect, test } from "@playwright/test";

import { API, backendAsleep, backendUp, trackHealth } from "./support";

/**
 * The waking banner and the status pill, driven by a backend that is made to behave.
 */

test.describe("backend status", () => {
  test("shows ONLINE when the server answers", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    await expect(page.getByRole("status").first()).toContainText("ONLINE");
    // No banner: nothing needs explaining when it just works.
    await expect(page.getByText("Waking up the server.")).toHaveCount(0);
  });

  test("shows nothing at all before the first answer", async ({ page }) => {
    // Hold the health check open so the page renders mid-check.
    await page.route(`${API}/actuator/health`, async () => {
      await new Promise(() => {});
    });

    await page.goto("/", { waitUntil: "commit" });

    // CHECKING must show no pill, so the bar never flashes a state before it is true.
    await expect(page.getByRole("status")).toHaveCount(0);
  });

  test("a sleeping server gets the pill and the banner", async ({ page }) => {
    await backendAsleep(page);
    await page.goto("/");

    // "STARTING UP…" on desktop, "STARTING" on a phone bar, where the full label does not fit.
    await expect(page.getByRole("status").first()).toContainText(/STARTING/, {
      timeout: 15_000,
    });
    await expect(page.getByText("Waking up the server.")).toBeVisible();
  });

  test("recovers by itself once the server wakes", async ({ page }) => {
    const health = trackHealth(page);
    await page.goto("/");

    await expect(page.getByText("Waking up the server.")).toBeVisible({ timeout: 15_000 });

    // The server finishes starting. Nothing tells the page; it has to notice.
    health.up = true;

    await expect(page.getByRole("status").first()).toContainText("ONLINE", { timeout: 20_000 });
    await expect(page.getByText("Waking up the server.")).toHaveCount(0);
  });

  test("the banner can be dismissed", async ({ page }) => {
    await backendAsleep(page);
    await page.goto("/");

    await expect(page.getByText("Waking up the server.")).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "Hide this message" }).click();

    await expect(page.getByText("Waking up the server.")).toHaveCount(0);
    // The pill stays: the state has not changed, only the explanation was waved away.
    await expect(page.getByRole("status").first()).toContainText("STARTING");
  });

  test("the banner's progress line is not announced", async ({ page }) => {
    await backendAsleep(page);
    await page.goto("/");

    await expect(page.getByText("Waking up the server.")).toBeVisible({ timeout: 15_000 });

    // It reports no real progress, so it must not claim to.
    const hidden = await page.locator(".ms-banner__progress").getAttribute("aria-hidden");
    expect(hidden).toBe("true");
  });

  test("no guest session is created just by loading a page", async ({ page }) => {
    let sessionCalls = 0;
    await page.route(`${API}/api/sessions`, (route) => {
      sessionCalls += 1;
      return route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({ token: "t", expiresAt: new Date(Date.now() + 86400000).toISOString() }),
      });
    });
    await backendUp(page);

    await page.goto("/");
    await page.goto("/privacy");
    await page.waitForTimeout(800);

    // Lazy, by design (contract 2.2): only a guest action creates one.
    expect(sessionCalls).toBe(0);
    expect(await page.evaluate(() => Object.keys(window.sessionStorage))).toEqual([]);
  });
});
