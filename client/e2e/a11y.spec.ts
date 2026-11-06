import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import { backendUp, seedTheme } from "./support";

/**
 * axe on every 7a page, in both themes.
 *
 * Both themes matter because most of what axe finds here is contrast, and a pair that passes
 * on the near-black ground can fail on the near-white one.
 */

const PAGES = [
  { path: "/", name: "home" },
  { path: "/privacy", name: "privacy" },
  { path: "/no-such-page", name: "404" },
];

for (const { path, name } of PAGES) {
  for (const theme of ["dark", "light"] as const) {
    test(`${name} has no serious or critical axe violations in ${theme}`, async ({
      context,
      page,
    }) => {
      await seedTheme(context, theme);
      await backendUp(page);
      await page.goto(path);
      await page.waitForTimeout(500);

      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();

      const bad = results.violations.filter(
        (violation) => violation.impact === "serious" || violation.impact === "critical",
      );

      expect(
        bad.map((v) => `${v.id} (${v.impact}): ${v.nodes.length} node(s) — ${v.help}`),
      ).toEqual([]);
    });
  }
}

test("the mobile menu is accessible while open", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await backendUp(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Open menu" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();

  const bad = results.violations.filter(
    (violation) => violation.impact === "serious" || violation.impact === "critical",
  );

  expect(bad.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
});
