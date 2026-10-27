import { test } from "@playwright/test";

import { backendUp, seedTheme } from "./support";

/**
 * Takes the pictures for the visual review against docs/ui/screens/ (LLD 13).
 *
 * Not assertions. These are for a person — and for Claude Code — to compare against the
 * mockups; pixel-diffing a design that is still being built would fail on every intentional
 * change and tell nobody anything. The output is gitignored.
 *
 * Only the desktop project runs them, so each page is captured once per width here rather
 * than once per Playwright project.
 */

const OUT = "e2e/__screenshots__";

const PAGES = [
  { path: "/", name: "home" },
  { path: "/privacy", name: "privacy" },
  { path: "/no-such-page", name: "404" },
];

const WIDTHS = [
  { label: "1440", width: 1440, height: 1000 },
  { label: "390", width: 390, height: 844 },
];

test.describe("visual review", () => {
  test.skip(({ browserName }) => browserName !== "chromium", "one browser is enough");

  for (const { path, name } of PAGES) {
    for (const size of WIDTHS) {
      for (const theme of ["dark", "light"] as const) {
        test(`${name} at ${size.label} in ${theme}`, async ({ context, page }) => {
          await seedTheme(context, theme);
          await backendUp(page);
          await page.setViewportSize({ width: size.width, height: size.height });

          await page.goto(path);
          // The helix is client-only and drifts in; give it a beat so it is in the picture.
          await page.waitForTimeout(900);

          await page.screenshot({
            path: `${OUT}/${name}--${size.label}--${theme}.png`,
            fullPage: true,
          });
        });
      }
    }
  }
});
