import { expect, test } from "@playwright/test";

import { backendUp, seedTheme } from "./support";

test.describe("the shell", () => {
  test("the skip link is first and moves focus to the content", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    // First Tab from the top of the document must reach it, or it is no use to anyone.
    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Skip to content" });
    await expect(skip).toBeFocused();

    await skip.press("Enter");

    const focusedId = await page.evaluate(() => document.activeElement?.id);
    expect(focusedId).toBe("main");
  });

  test("has exactly one main landmark", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    await expect(page.locator("main")).toHaveCount(1);
  });

  test("the footer hides the GitHub link when no repo URL is set", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    await expect(page.getByRole("link", { name: "GitHub" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Privacy" }).first()).toBeVisible();
  });

  test("shows no sign-in control anywhere", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    // F8: sign-in ships in 7c. Until then it is absent, not disabled.
    await expect(page.getByText(/sign in with google/i)).toHaveCount(0);
  });
});

test.describe("the mobile menu", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("opens, traps focus, closes on Escape, and gives focus back", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    const trigger = page.getByRole("button", { name: "Open menu" });
    await trigger.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();

    // Wait for the dialog to have taken focus before tabbing. Base UI moves initial focus
    // asynchronously, and a Tab that lands before the trap is armed escapes legitimately —
    // which made this test fail while the behaviour was correct.
    await expect
      .poll(() =>
        page.evaluate(() => {
          const open = document.querySelector('[role="dialog"]');
          return !!open && !!document.activeElement && open.contains(document.activeElement);
        }),
      )
      .toBe(true);

    // Tab right around the dialog and past the end; focus must never leave it.
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press("Tab");
      const inside = await page.evaluate(() => {
        const open = document.querySelector('[role="dialog"]');
        return !!open && !!document.activeElement && open.contains(document.activeElement);
      });
      expect(inside, `focus escaped after ${i + 1} tabs`).toBe(true);
    }

    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test("closes when a link is followed", async ({ page }) => {
    await backendUp(page);
    await page.goto("/");

    await page.getByRole("button", { name: "Open menu" }).click();
    await page.getByRole("dialog").getByRole("link", { name: "Privacy" }).click();

    await expect(page).toHaveURL(/\/privacy$/);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});

test.describe("layout", () => {
  for (const width of [320, 390, 900, 1440]) {
    for (const path of ["/", "/privacy", "/nope"]) {
      test(`${path} does not scroll sideways at ${width}px`, async ({ page }) => {
        await page.setViewportSize({ width, height: 900 });
        await backendUp(page);
        await page.goto(path);
        await page.waitForTimeout(400);

        const overflows = await page.evaluate(
          () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
        );
        expect(overflows).toBe(false);
      });
    }
  }
});

test.describe("the 404 page", () => {
  for (const theme of ["dark", "light"] as const) {
    test(`renders for an unknown path in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await backendUp(page);

      const response = await page.goto("/no-such-page");
      expect(response?.status()).toBe(404);

      await expect(page.getByRole("heading", { level: 1 })).toContainText(
        "This page doesn't exist.",
      );
      // The status is in words too, since the numeral is hidden from screen readers.
      await expect(page.getByText("Page not found")).toBeVisible();
      await expect(page.getByRole("link", { name: /Back to home/ })).toBeVisible();
    });
  }
});
