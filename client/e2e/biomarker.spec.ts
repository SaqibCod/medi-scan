import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

import {
  REPORT_ID,
  doneReport,
  landingReady,
  reportIs,
  seedTheme,
  watchForProblems,
} from "./support";

test.describe("a biomarker page", () => {
  test("reached from a results row, it shows the reader's own value", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());

    await page.goto(`/reports/${REPORT_ID}`);
    await page.getByRole("link", { name: "LDL Cholesterol" }).first().click();

    await expect(page).toHaveURL(/\/biomarkers\/ldl-cholesterol\?report=.+&b=b2$/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("LDL Cholesterol");

    const box = page.getByText("On your report", { exact: false }).first();
    await expect(box).toBeVisible();
    await expect(page.getByText("162", { exact: true })).toBeVisible();
    await expect(page.getByText(/Your lab's range is <100\./)).toBeVisible();
    await expect(page.getByRole("link", { name: /Back to report/ })).toBeVisible();
  });

  test("opened directly, there is no box and no error about it", async ({ page }) => {
    await landingReady(page);
    await page.goto("/biomarkers/ldl-cholesterol");

    await expect(page.getByRole("heading", { level: 1 })).toContainText("LDL Cholesterol");

    // Someone arriving from a search engine gets a clean explainer, not an empty frame or a
    // complaint about a report they never had.
    //
    // Keyed on the box's own controls rather than its heading text: the body legitimately
    // says "the range printed on your report", and `getByText` matches substrings.
    await expect(page.getByRole("link", { name: /Back to report/ })).toHaveCount(0);
    await expect(page.getByText("ON YOUR REPORT", { exact: true })).toHaveCount(0);
    await expect(page.getByText(/couldn't load/i)).toHaveCount(0);
  });

  test("the body carries the five sections and no reference range", async ({ page }) => {
    await landingReady(page);
    await page.goto("/biomarkers/ldl-cholesterol");

    for (const heading of [
      "What it measures",
      "What high LDL means",
      "What low LDL means",
      "What can affect the result",
      "Questions to ask your doctor",
    ]) {
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    }

    // The explainer itself prints no numbers. The only one on the page is the reader's own,
    // and that box is absent here.
    const article = await page.locator("main").innerText();
    expect(article).not.toMatch(/\d+\s*mg\/dL/);
  });

  test("related tests link to pages that exist", async ({ page }) => {
    await landingReady(page);
    await page.goto("/biomarkers/ldl-cholesterol");

    const related = page.getByRole("link", { name: /HDL Cholesterol/ }).last();
    await related.click();

    await expect(page.getByRole("heading", { level: 1 })).toContainText("HDL Cholesterol");
  });

  test("an unknown slug is the 404 page", async ({ page }) => {
    await landingReady(page);

    const response = await page.goto("/biomarkers/unobtainium");

    expect(response?.status()).toBe(404);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("This page doesn't exist.");
  });

  test("the index lists every page, grouped by panel", async ({ page }) => {
    await landingReady(page);
    await page.goto("/biomarkers");

    await expect(page.getByRole("heading", { name: "Lipid panel" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Complete blood count" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Thyroid panel" })).toBeVisible();

    const links = page.locator('a[href^="/biomarkers/"]');
    await expect(links).toHaveCount(21);
  });

  test("says it has not been reviewed by a person", async ({ page }) => {
    await landingReady(page);
    await page.goto("/biomarkers/tsh");

    await expect(page.getByText("Not yet reviewed by a person.")).toBeVisible();
    await expect(page.getByRole("link", { name: /MedlinePlus/ }).first()).toBeVisible();
  });
});

test.describe("accessibility and security", () => {
  const PAGES = [
    { name: "landing", path: "/" },
    { name: "done report", path: `/reports/${REPORT_ID}` },
    { name: "biomarker", path: "/biomarkers/ldl-cholesterol" },
    { name: "biomarker index", path: "/biomarkers" },
  ];

  for (const { name, path } of PAGES) {
    for (const theme of ["dark", "light"] as const) {
      test(`${name} has no serious axe violations in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await landingReady(page);
        await reportIs(page, doneReport());

        await page.goto(path);
        await page.waitForTimeout(500);

        const results = await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
          .analyze();

        const serious = results.violations.filter(
          (v) => v.impact === "serious" || v.impact === "critical",
        );

        expect(
          serious.map((v) => `${v.id}: ${v.nodes.length} node(s) — ${v.help}`),
        ).toEqual([]);
      });
    }
  }

  for (const { name, path } of PAGES) {
    test(`${name} raises no CSP violation and no page error`, async ({ page }) => {
      const problems = watchForProblems(page);

      await landingReady(page);
      await reportIs(page, doneReport());

      await page.goto(path);
      await page.waitForTimeout(700);

      expect(problems).toEqual([]);
    });
  }

  test("a failed report is accessible too", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, { ...doneReport(), status: "FAILED", result: null, error: { code: "UNREADABLE", message: "x" } });

    await page.goto(`/reports/${REPORT_ID}`);
    await page.waitForTimeout(400);

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();

    expect(
      results.violations
        .filter((v) => v.impact === "serious" || v.impact === "critical")
        .map((v) => v.id),
    ).toEqual([]);
  });
});

test.describe("layout on the new pages", () => {
  for (const width of [320, 390, 900, 1440]) {
    test(`no sideways scroll at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await landingReady(page);
      await reportIs(page, doneReport());

      for (const path of [`/reports/${REPORT_ID}`, "/biomarkers", "/biomarkers/ldl-cholesterol"]) {
        await page.goto(path);
        await page.waitForTimeout(300);

        const overflows = await page.evaluate(
          () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
        );
        expect(overflows, `${path} at ${width}px`).toBe(false);
      }
    });
  }
});
