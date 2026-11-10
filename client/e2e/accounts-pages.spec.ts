import { expect, test, type Page } from "@playwright/test";

import { backendUp, guestSession, watchForProblems } from "./support";
import {
  authEndpoints,
  googleStub,
  listItem,
  reportList,
  startSignedIn,
  trendEndpoints,
  trendPoint,
} from "./support-auth";

/**
 * My reports and Trends against a production build (LLD 10, e2e 6 and 7).
 *
 * The header carries its own sign-in button and the toasts repeat page wording, so every
 * assertion names the page body.
 */
function main(page: Page) {
  return page.locator("#main");
}

/** The report rows, which the pagination's own list items would otherwise be counted with. */
function rows(page: Page) {
  return page.getByRole("list", { name: "Your reports" }).getByRole("listitem");
}

const DAY = 86_400_000;

function many(count: number, over: (index: number) => Record<string, unknown> = () => ({})) {
  return Array.from({ length: count }, (_, index) =>
    listItem({ id: `0000000${index}-0000-4000-8000-00000000000${index % 10}`, ...over(index) }),
  );
}

async function signedInPage(page: Page, options: Parameters<typeof authEndpoints>[1] = {}) {
  await backendUp(page);
  await googleStub(page);
  await authEndpoints(page, options);
  await startSignedIn(page);
}

test.describe("My reports", () => {
  test("filters by status, and the Processing tab covers both unfinished kinds", async ({ page }) => {
    await signedInPage(page);
    await trendEndpoints(page, { markers: [] });
    await reportList(page, {
      all: many(14),
      DONE: many(11),
      PENDING: [listItem({ id: "p1", status: "PENDING", counts: null, collectedOn: null })],
      PROCESSING: [listItem({ id: "p2", status: "PROCESSING", counts: null, collectedOn: null })],
      FAILED: many(2, () => ({ status: "FAILED", counts: null, errorCode: "UNREADABLE" })),
    });

    await page.goto("/reports");

    await expect(page.getByRole("tab", { name: "All 14" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Done 11" })).toBeVisible();
    // One PENDING and one PROCESSING, counted together.
    await expect(page.getByRole("tab", { name: "Processing 2" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Failed 2" })).toBeVisible();

    await page.getByRole("tab", { name: /Processing/ }).click();
    await expect(page).toHaveURL(/status=processing/);
    // Both lists, merged into one.
    await expect(rows(page)).toHaveCount(2);
  });

  test("pages through the list, keeping the page in the URL", async ({ page }) => {
    await signedInPage(page);
    await trendEndpoints(page, { markers: [] });
    await reportList(page, { all: many(14), DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });

    await page.goto("/reports");

    await expect(rows(page)).toHaveCount(6);
    await expect(page.getByText("Showing 1–6 of 14")).toBeVisible();

    // Exact: every row has a delete button whose label happens to contain "2" (the year).
    await page.getByRole("navigation", { name: "Pages" }).getByRole("button", { name: "2", exact: true }).click();
    await expect(page).toHaveURL(/page=1/);
    await expect(page.getByText("Showing 7–12 of 14")).toBeVisible();

    // Back works, because the page is a URL and not a piece of component state.
    await page.goBack();
    await expect(page.getByText("Showing 1–6 of 14")).toBeVisible();
  });

  test("deletes a report from the list, after asking", async ({ page }) => {
    await signedInPage(page);
    await trendEndpoints(page, { markers: [] });

    let deleted = false;
    await page.route("**/api/reports/*", (route) => {
      if (route.request().method() === "DELETE") {
        deleted = true;
        return route.fulfill({ status: 204, body: "" });
      }
      return route.fallback();
    });
    await reportList(page, { all: many(1), DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });

    await page.goto("/reports");
    await main(page).getByRole("button", { name: /^Delete the report/ }).click();

    await expect(page.getByText("Delete this report?")).toBeVisible();
    await page.getByRole("button", { name: "Delete report" }).click();

    await expect.poll(() => deleted).toBe(true);
  });

  test("a guest is shown the banner and the 24-hour wording", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    await authEndpoints(page);
    await guestSession(page);
    // A guest session already exists, so the list is fetched rather than skipped.
    await page.addInitScript(() => {
      window.sessionStorage.setItem(
        "ms.guest",
        JSON.stringify({ token: "e2e-guest", expiresAt: new Date(Date.now() + 86_400_000).toISOString() }),
      );
    });
    await reportList(page, {
      all: [listItem({ expiresAt: new Date(Date.now() + 23 * 3_600_000 + 60_000).toISOString() })],
      DONE: [],
      PENDING: [],
      PROCESSING: [],
      FAILED: [],
    });

    await page.goto("/reports");

    await expect(page.getByText(/live only in this browser tab/)).toBeVisible();
    await expect(page.getByText("Deleted in 23 h")).toBeVisible();
    await expect(
      page.getByText("Guest reports are deleted automatically 24 hours after your session started."),
    ).toBeVisible();
  });

  test("an empty list offers a way to start rather than a filter", async ({ page }) => {
    await signedInPage(page);
    await trendEndpoints(page, { markers: [] });
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });

    await page.goto("/reports");

    await expect(page.getByText("No reports yet.")).toBeVisible();
    await expect(page.getByRole("tab", { name: /All/ })).toBeHidden();
  });
});

test.describe("Trends", () => {
  test("charts a marker, and the table carries the same values", async ({ page }) => {
    await signedInPage(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page);

    await page.goto("/trends");

    await expect(main(page).getByText("LDL Cholesterol").first()).toBeVisible();
    await expect(page.getByText("In MG/DL", { exact: true })).toBeVisible();
    await expect(page.getByText("+14 mg/dL since 14 Mar 2026")).toBeVisible();

    // The chart really drew: a line and three dots.
    await expect(page.locator(".ms-chart svg")).toBeVisible();
    await expect(page.locator(".ms-chart circle")).toHaveCount(3);

    await page.getByRole("tab", { name: "Table" }).click();

    const table = page.locator("#main table").first();
    await expect(table.getByText("28 Sep 2026")).toBeVisible();
    await expect(table.getByText("<100").first()).toBeVisible();
  });

  test("gives each unit its own chart and never converts between them", async ({ page }) => {
    await signedInPage(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, {
      markers: [{ marker: "glucose", displayName: "Glucose", reportCount: 4 }],
      trends: {
        glucose: {
          marker: "glucose",
          displayName: "Glucose",
          series: [
            {
              unit: "mg/dL",
              points: [
                trendPoint({ date: "2026-05-11", value: 92, refLow: 70, refHigh: 99, flag: "NORMAL" }),
                trendPoint({ date: "2026-09-02", value: 97, refLow: 70, refHigh: 99, flag: "NORMAL" }),
              ],
            },
            {
              unit: "mmol/L",
              points: [
                trendPoint({
                  date: "2026-10-09",
                  value: 5.4,
                  dateSource: "UPLOADED",
                  refLow: 3.9,
                  refHigh: 5.5,
                  flag: "NORMAL",
                }),
              ],
            },
          ],
        },
      },
    });

    await page.goto("/trends?marker=glucose");

    await expect(page.getByText("In MG/DL", { exact: true })).toBeVisible();
    await expect(page.getByText("In MMOL/L", { exact: true })).toBeVisible();
    await expect(
      page.getByText(
        "Glucose was reported in 2 different units. Medi-Scan never converts between units, so each one gets its own chart.",
      ),
    ).toBeVisible();

    // The single-point series says why it has no line, and explains the hollow dot.
    await expect(
      page.getByText("Only one value in mmol/L so far. A line appears once there are two."),
    ).toBeVisible();
    await expect(page.getByText("Hollow dot: dated by upload")).toBeVisible();
  });

  test("says nothing to compare yet when no marker repeats", async ({ page }) => {
    await signedInPage(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    await page.goto("/trends");

    await expect(page.getByText("Nothing to compare yet.")).toBeVisible();
  });

  test("offers a guest sign-in rather than an empty chart", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    await authEndpoints(page);

    await page.goto("/trends");

    await expect(page.getByText("Sign in to see trends.")).toBeVisible();
  });

  test("never says whether a direction is good or bad", async ({ page }) => {
    await signedInPage(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page);

    await page.goto("/trends");
    await expect(page.getByText("+14 mg/dL since 14 Mar 2026")).toBeVisible();

    const body = (await main(page).textContent()) ?? "";
    for (const word of ["improv", "worse", "better", "target", "ideal", "should be", "healthy range"]) {
      expect(body.toLowerCase()).not.toContain(word);
    }

    // And it does say where the band came from.
    expect(body).toContain("The shaded band is the range printed on your most recent report.");
  });

  test("raises no policy violation on either page", async ({ page }) => {
    const problems = watchForProblems(page);

    await signedInPage(page);
    await reportList(page, { all: many(3), DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page);

    await page.goto("/reports");
    await expect(rows(page).first()).toBeVisible();

    await page.goto("/trends");
    await expect(page.locator(".ms-chart svg")).toBeVisible();

    expect(problems).toEqual([]);
  });
});

test.describe("expiry wording", () => {
  test("a signed-in reader is told in days", async ({ page }) => {
    await signedInPage(page);
    await trendEndpoints(page, { markers: [] });
    await reportList(page, {
      all: [listItem({ expiresAt: new Date(Date.now() + 30 * DAY + 60_000).toISOString() })],
      DONE: [],
      PENDING: [],
      PROCESSING: [],
      FAILED: [],
    });

    await page.goto("/reports");
    await expect(page.getByText("Expires in 30 days")).toBeVisible();
  });
});
