import { expect, test } from "@playwright/test";

import { landingReady, seedTheme } from "./support";

/**
 * The two gradients the design system allows, and nothing else about the landing page that
 * the other specs already cover.
 *
 * These are here because the failure mode is silent. `--preview-bg` ends in a solid colour
 * layer, so applying it with `background-image` makes the whole declaration invalid and the
 * browser drops it — the band renders flat, nothing errors, and no test that checks text or
 * roles notices. The same is true of a wordmark whose background-clip stops working: it just
 * turns invisible, because the text colour is transparent.
 */
test.describe("the two allowed gradients", () => {
  for (const theme of ["dark", "light"] as const) {
    test(`the results-preview band carries its glow in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await page.goto("/");

      const band = page.locator("#results > div").first();
      const background = await band.evaluate((el) => getComputedStyle(el).backgroundImage);

      // Both radial stops, not just "some gradient": a single one would mean half the token
      // was dropped.
      expect(background).toContain("radial-gradient");
      expect(background.match(/radial-gradient/g)?.length).toBe(2);
    });

    test(`the footer wordmark fades rather than disappearing in ${theme}`, async ({
      context,
      page,
    }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await page.goto("/");

      const wordmark = page.locator("footer div[aria-hidden='true']").last();
      await expect(wordmark).toHaveText("MEDI·SCAN");

      const style = await wordmark.evaluate((el) => {
        const s = getComputedStyle(el);
        return {
          image: s.backgroundImage,
          // `getPropertyValue` rather than the camelCase alias, which TypeScript flags as
          // deprecated even though it is the property Chromium actually reflects.
          clip: s.getPropertyValue("-webkit-background-clip") || s.backgroundClip,
          colour: s.color,
        };
      });

      // Transparent text over a clipped gradient. If the clip ever stops applying, the
      // wordmark becomes an invisible block rather than an obviously broken one.
      expect(style.image).toContain("linear-gradient");
      expect(style.clip).toBe("text");
      expect(style.colour).toMatch(/rgba\(0, 0, 0, 0\)|transparent/);
    });
  }

  test("the wordmark is scenery, not something a screen reader reads again", async ({ page }) => {
    await landingReady(page);
    await page.goto("/");

    // Asserted on the attribute rather than through `getByText`, which walks the DOM and
    // finds `aria-hidden` nodes quite happily — it would pass whether or not the attribute
    // was there. The brand is already in the header and in the footer paragraph.
    const wordmark = page.locator("footer div[aria-hidden='true']").last();
    await expect(wordmark).toHaveText("MEDI·SCAN");
    await expect(wordmark).toHaveAttribute("aria-hidden", "true");
  });
});
