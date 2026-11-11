import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { backendUp, seedTheme } from "./support";
import {
  authEndpoints,
  googleStub,
  listItem,
  reportList,
  startSignedIn,
  trendEndpoints,
} from "./support-auth";

/**
 * axe on the three 7c pages, in both themes, signed in (LLD 10, e2e 9).
 *
 * Signed in because that is where the content is: a signed-out Account is one card, while the
 * signed-in one carries the profile, the stat cards, the table of what is kept and the delete
 * confirm. Trends is run in both views, since the chart and the table are different trees.
 */

async function signedIn(page: Page) {
  await backendUp(page);
  await googleStub(page);
  await authEndpoints(page, { reportCount: 14 });
  await startSignedIn(page);
  await reportList(page, {
    all: Array.from({ length: 6 }, (_, index) =>
      listItem({
        id: `0000000${index}-0000-4000-8000-00000000000${index}`,
        ...(index === 1 ? { status: "PROCESSING", counts: null, collectedOn: null } : {}),
        ...(index === 2 ? { status: "FAILED", counts: null, errorCode: "UNREADABLE" } : {}),
      }),
    ),
    DONE: [],
    PENDING: [],
    PROCESSING: [],
    FAILED: [],
  });
  await trendEndpoints(page);
}

async function noSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();

  const bad = results.violations.filter(
    (violation) => violation.impact === "serious" || violation.impact === "critical",
  );

  // The failing selector and its colours, so a failure says what to fix rather than that
  // something, somewhere, is wrong.
  expect(
    bad.flatMap((violation) =>
      violation.nodes.map((node) => `${violation.id}: ${node.target.join(" ")} — ${node.failureSummary?.replace(/\s+/g, " ")}`),
    ),
  ).toEqual([]);
}

for (const theme of ["dark", "light"] as const) {
  test(`My reports has no serious or critical axe violations in ${theme}`, async ({ context, page }) => {
    await seedTheme(context, theme);
    await signedIn(page);

    await page.goto("/reports");
    await expect(page.getByRole("list", { name: "Your reports" })).toBeVisible();

    await noSeriousViolations(page);
  });

  test(`Account has no serious or critical axe violations in ${theme}`, async ({ context, page }) => {
    await seedTheme(context, theme);
    await signedIn(page);

    await page.goto("/account");
    await expect(page.getByText("What Medi-Scan keeps about you")).toBeVisible();

    // With the delete confirm open, which is the densest state the page has.
    await page.getByRole("button", { name: /Delete account/ }).click();
    await expect(page.getByRole("checkbox")).toBeVisible();

    await noSeriousViolations(page);
  });

  test(`Trends has no serious or critical axe violations in ${theme}, chart and table`, async ({
    context,
    page,
  }) => {
    await seedTheme(context, theme);
    await signedIn(page);

    await page.goto("/trends");
    await expect(page.locator(".ms-chart svg")).toBeVisible();
    await noSeriousViolations(page);

    await page.getByRole("tab", { name: "Table" }).click();
    await expect(page.locator("#main table").first()).toBeVisible();
    await noSeriousViolations(page);
  });
}
