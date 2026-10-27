import { expect, test } from "@playwright/test";

import { backendUp, seedTheme } from "./support";

/**
 * The no-flash guarantee, which only a real browser can check: it is about what was painted
 * first, not what React eventually settled on.
 */

test.describe("theme", () => {
  test("a saved light choice is already applied at first paint", async ({ context, page }) => {
    await seedTheme(context, "light");
    await backendUp(page);

    // Read the attribute the moment the document exists, before React has hydrated. If the
    // theme were decided in React this would be "dark" and the visitor would see it flip.
    await page.addInitScript(() => {
      document.addEventListener("DOMContentLoaded", () => {
        (window as unknown as { __themeAtParse?: string }).__themeAtParse =
          document.documentElement.getAttribute("data-theme") ?? "none";
      });
    });

    await page.goto("/");

    const atParse = await page.evaluate(
      () => (window as unknown as { __themeAtParse?: string }).__themeAtParse ?? "missing",
    );

    expect(atParse).toBe("light");
  });

  test("a saved dark choice is applied the same way", async ({ context, page }) => {
    await seedTheme(context, "dark");
    await backendUp(page);

    await page.goto("/");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  });

  test("follows the device when nothing is saved", async ({ browser }) => {
    const context = await browser.newContext({ colorScheme: "light" });
    const page = await context.newPage();
    await backendUp(page);

    await page.goto("/");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");

    await context.close();
  });

  test("the toggle switches the theme and survives a reload", async ({ page }) => {
    // Deliberately no `seedTheme` here: `addInitScript` runs on *every* navigation, so it
    // would rewrite ms-theme back to "dark" during the reload and the test would be checking
    // its own fixture rather than the app.
    await backendUp(page);
    await page.goto("/");
    await page.evaluate(() => window.localStorage.setItem("ms-theme", "dark"));
    await page.reload();

    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

    await page.getByRole("button", { name: "Switch to light mode" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");

    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    // And the button now offers the other direction.
    await expect(page.getByRole("button", { name: "Switch to dark mode" })).toBeVisible();
  });

  test("stores only the theme, and only in localStorage", async ({ context, page }) => {
    await seedTheme(context, "dark");
    await backendUp(page);
    await page.goto("/");

    await page.getByRole("button", { name: /Switch to (light|dark) mode/ }).click();

    const storage = await page.evaluate(() => ({
      local: Object.keys(window.localStorage),
      session: Object.keys(window.sessionStorage),
    }));

    // The one localStorage key allowed anywhere in the app (root CLAUDE.md).
    expect(storage.local).toEqual(["ms-theme"]);
    // And no guest session, because nothing has asked for one.
    expect(storage.session).toEqual([]);

    const value = await page.evaluate(() => window.localStorage.getItem("ms-theme"));
    expect(["dark", "light"]).toContain(value);
  });

  test("hydrates without an error, in either theme", async ({ browser }) => {
    for (const theme of ["dark", "light"] as const) {
      const context = await browser.newContext();
      await seedTheme(context, theme);
      const page = await context.newPage();

      const errors: string[] = [];
      page.on("pageerror", (error) => errors.push(error.message));

      await backendUp(page);
      await page.goto("/");
      await page.waitForTimeout(600);

      // A theme-dependent markup difference shows up here as "Hydration failed".
      expect(errors.filter((e) => /[Hh]ydration/.test(e)), theme).toEqual([]);

      await context.close();
    }
  });
});
