import { expect, test, type Page } from "@playwright/test";

import { backendUp, seedTheme } from "./support";
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
 * Pictures of the three 7c pages, for the visual review against `docs/ui/screens/`.
 *
 * Not assertions, for the same reason as the 7a set: pixel-diffing a design that is still
 * being built fails on every intentional change and tells nobody anything. The fixtures are
 * chosen to match the mockups' own data, so the comparison is like for like.
 */

const OUT = "e2e/__screenshots__";

const WIDTHS = [
  { label: "1440", width: 1440, height: 1200 },
  { label: "390", width: 390, height: 900 },
];

/** The fourteen reports of `my-reports-page--01`, with its processing and failed rows. */
function fixtureRows() {
  const base = Date.now();
  return [
    listItem({
      id: "a0000000-0000-4000-8000-000000000000",
      status: "PROCESSING",
      sourceType: "IMAGE",
      counts: null,
      collectedOn: null,
      createdAt: "2026-10-12T09:40:00Z",
      expiresAt: new Date(base + 30 * 86_400_000 + 60_000).toISOString(),
    }),
    listItem({
      id: "a0000000-0000-4000-8000-000000000001",
      createdAt: "2026-10-12T09:31:00Z",
      collectedOn: "2026-09-28",
      counts: { total: 5, low: 0, normal: 3, high: 2, unknown: 0 },
      expiresAt: new Date(base + 30 * 86_400_000 + 60_000).toISOString(),
    }),
    listItem({
      id: "a0000000-0000-4000-8000-000000000002",
      status: "FAILED",
      sourceType: "IMAGE",
      counts: null,
      collectedOn: null,
      errorCode: "UNREADABLE",
      createdAt: "2026-10-11T18:12:00Z",
      expiresAt: new Date(base + 29 * 86_400_000 + 60_000).toISOString(),
    }),
    listItem({
      id: "a0000000-0000-4000-8000-000000000003",
      sourceType: "SAMPLE",
      collectedOn: null,
      createdAt: "2026-10-09T11:05:00Z",
      counts: { total: 3, low: 1, normal: 2, high: 0, unknown: 0 },
      expiresAt: new Date(base + 27 * 86_400_000 + 60_000).toISOString(),
    }),
    listItem({
      id: "a0000000-0000-4000-8000-000000000004",
      sourceType: "TEXT",
      collectedOn: "2026-09-14",
      createdAt: "2026-10-06T08:47:00Z",
      counts: { total: 12, low: 0, normal: 11, high: 1, unknown: 0 },
      expiresAt: new Date(base + 24 * 86_400_000 + 60_000).toISOString(),
    }),
    listItem({
      id: "a0000000-0000-4000-8000-000000000005",
      collectedOn: "2026-06-10",
      createdAt: "2026-10-04T20:03:00Z",
      counts: { total: 5, low: 0, normal: 4, high: 1, unknown: 0 },
      expiresAt: new Date(base + 22 * 86_400_000 + 60_000).toISOString(),
    }),
  ];
}

const GLUCOSE = {
  marker: "glucose",
  displayName: "Glucose",
  series: [
    {
      unit: "mg/dL",
      points: [
        trendPoint({ date: "2026-05-11", value: 92, refLow: 70, refHigh: 99, flag: "NORMAL" }),
        trendPoint({ date: "2026-08-02", value: 104, refLow: 70, refHigh: 99, flag: "HIGH" }),
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
};

async function fixtures(page: Page, options: { signedIn: boolean; empty?: boolean }) {
  await backendUp(page);
  await googleStub(page);
  await authEndpoints(page, { reportCount: options.empty ? 0 : 4 });

  if (options.signedIn) await startSignedIn(page);
  else {
    await page.addInitScript(() => {
      window.sessionStorage.setItem(
        "ms.guest",
        JSON.stringify({ token: "e2e-guest", expiresAt: new Date(Date.now() + 86_400_000).toISOString() }),
      );
    });
  }

  const rows = options.empty ? [] : fixtureRows();
  await reportList(page, {
    all: options.signedIn ? rows : rows.slice(1, 3),
    DONE: rows.filter((row) => row.status === "DONE"),
    PENDING: [],
    PROCESSING: rows.filter((row) => row.status === "PROCESSING"),
    FAILED: rows.filter((row) => row.status === "FAILED"),
  });

  await trendEndpoints(page, {
    markers: options.empty
      ? []
      : [
          { marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 },
          { marker: "total-cholesterol", displayName: "Total Cholesterol", reportCount: 3 },
          { marker: "glucose", displayName: "Glucose", reportCount: 4 },
          { marker: "tsh", displayName: "TSH", reportCount: 2 },
        ],
    trends: { glucose: GLUCOSE },
  });
}

async function shoot(page: Page, name: string, label: string, theme: string) {
  await page.screenshot({ path: `${OUT}/${name}-${label}-${theme}.png`, fullPage: true });
}

test.describe("visual review: accounts", () => {
  for (const size of WIDTHS) {
    for (const theme of ["dark", "light"] as const) {
      test(`my reports at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: true });
        await page.setViewportSize(size);

        await page.goto("/reports");
        await expect(page.getByRole("list", { name: "Your reports" })).toBeVisible();
        await page.waitForTimeout(600);

        await shoot(page, "my-reports-signed-in", size.label, theme);
      });

      test(`my reports as a guest at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: false });
        await page.setViewportSize(size);

        await page.goto("/reports");
        await expect(page.getByRole("list", { name: "Your reports" })).toBeVisible();
        await page.waitForTimeout(600);

        await shoot(page, "my-reports-guest", size.label, theme);
      });

      test(`my reports empty at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: true, empty: true });
        await page.setViewportSize(size);

        await page.goto("/reports");
        await expect(page.getByText("No reports yet.")).toBeVisible();
        await page.waitForTimeout(900);

        await shoot(page, "my-reports-empty", size.label, theme);
      });

      test(`account at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: true });
        await page.setViewportSize(size);

        await page.goto("/account");
        await expect(page.getByText("What Medi-Scan keeps about you")).toBeVisible();
        await page.waitForTimeout(400);

        await shoot(page, "account", size.label, theme);

        await page.getByRole("button", { name: /Delete account/ }).click();
        await expect(page.getByRole("checkbox")).toBeVisible();
        await shoot(page, "account-delete", size.label, theme);
      });

      test(`account signed out at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: false });
        await page.setViewportSize(size);

        await page.goto("/account");
        await expect(page.getByText("Sign in to see")).toBeVisible();
        await page.waitForTimeout(900);

        await shoot(page, "account-signed-out", size.label, theme);
      });

      test(`trends at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: true });
        await page.setViewportSize(size);

        await page.goto("/trends");
        await expect(page.locator(".ms-chart svg")).toBeVisible();
        await page.waitForTimeout(700);

        await shoot(page, "trends-ldl", size.label, theme);

        // The two-unit case, with its single undated point.
        await page.goto("/trends?marker=glucose");
        await expect(page.getByText("In MMOL/L", { exact: true })).toBeVisible();
        await page.waitForTimeout(700);
        await shoot(page, "trends-two-units", size.label, theme);

        await page.getByRole("tab", { name: "Table" }).click();
        await expect(page.locator("#main table").first()).toBeVisible();
        await shoot(page, "trends-table", size.label, theme);
      });

      test(`trends with nothing to compare at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: true, empty: true });
        await page.setViewportSize(size);

        await page.goto("/trends");
        await expect(page.getByText("Nothing to compare yet.")).toBeVisible();
        await page.waitForTimeout(400);

        await shoot(page, "trends-not-enough", size.label, theme);
      });

      test(`trends signed out at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await fixtures(page, { signedIn: false });
        await page.setViewportSize(size);

        await page.goto("/trends");
        await expect(page.getByText("Sign in to see trends.")).toBeVisible();
        await page.waitForTimeout(900);

        await shoot(page, "trends-signed-out", size.label, theme);
      });
    }
  }
});
