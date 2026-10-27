import { expect, test } from "@playwright/test";

import { backendUp, watchForProblems, watchRequests } from "./support";

/**
 * The policy the app actually ships, checked against a production server.
 *
 * Unit tests assert the policy string; these assert that the browser accepts it and that the
 * page still works under it — which is the part a string comparison cannot tell you.
 */

const PAGES = ["/", "/privacy", "/this-page-does-not-exist"];

test.describe("Content Security Policy", () => {
  for (const path of PAGES) {
    test(`${path} is served with a strict policy`, async ({ page }) => {
      await backendUp(page);
      const response = await page.goto(path);
      const csp = response?.headers()["content-security-policy"] ?? "";

      expect(csp).toContain("default-src 'self'");
      expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("base-uri 'none'");
      expect(csp).toContain("frame-ancestors 'none'");
      expect(csp).toContain("upgrade-insecure-requests");

      // Development needs these; a shipped response must not have them.
      expect(csp).not.toContain("unsafe-eval");
      expect(csp.split("; ").find((d) => d.startsWith("script-src"))).not.toContain(
        "unsafe-inline",
      );
    });

    test(`${path} raises no CSP violation`, async ({ page }) => {
      const problems = watchForProblems(page);
      await backendUp(page);

      await page.goto(path);
      await page.waitForTimeout(800);

      expect(problems).toEqual([]);
    });
  }

  test("every script in the served HTML carries the nonce from the header", async ({ page }) => {
    await backendUp(page);
    const response = await page.goto("/");

    const nonce = response?.headers()["content-security-policy"]?.match(/'nonce-([^']+)'/)?.[1];
    expect(nonce).toBeTruthy();

    // Checked against the HTML as sent, not the DOM. Browsers deliberately blank the `nonce`
    // *content attribute* after parsing so a CSS selector cannot read it back out, which
    // means `getAttribute("nonce")` returns "" for scripts that are perfectly nonced.
    const html = (await response?.text()) ?? "";
    const scriptTags = html.match(/<script\b[^>]*>/g) ?? [];

    expect(scriptTags.length).toBeGreaterThan(0);
    // `strict-dynamic` means an un-nonced script is a blocked script. One miss breaks the page.
    const unnonced = scriptTags.filter((tag) => !tag.includes(`nonce="${nonce}"`));
    expect(unnonced).toEqual([]);
  });

  test("a fresh nonce per request", async ({ page }) => {
    await backendUp(page);

    const first = (await page.goto("/"))?.headers()["content-security-policy"];
    const second = (await page.reload())?.headers()["content-security-policy"];

    expect(first).not.toBe(second);
  });

  test("the other security headers are present", async ({ page }) => {
    await backendUp(page);
    const headers = (await page.goto("/"))?.headers() ?? {};

    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
    expect(headers["strict-transport-security"]).toContain("max-age=");
    expect(headers["permissions-policy"]).toContain("microphone=()");
    // Says which server is running and helps nobody but an attacker.
    expect(headers["x-powered-by"]).toBeUndefined();
  });
});

test.describe("third parties", () => {
  for (const path of PAGES) {
    test(`${path} talks to nobody but us`, async ({ page }) => {
      const hosts = watchRequests(page);
      await backendUp(page);

      await page.goto(path);
      await page.waitForTimeout(800);

      const external = [...hosts].filter((host) => !host.startsWith("localhost"));
      expect(external).toEqual([]);
    });
  }

  test("fonts are self-hosted, with no request to Google", async ({ page }) => {
    const hosts = watchRequests(page);
    await backendUp(page);

    await page.goto("/");
    await page.waitForTimeout(800);

    for (const host of hosts) {
      expect(host).not.toContain("fonts.googleapis.com");
      expect(host).not.toContain("fonts.gstatic.com");
    }

    // And the files really are coming from us.
    const fonts = await page.evaluate(() =>
      performance
        .getEntriesByType("resource")
        .map((entry) => entry.name)
        .filter((name) => name.endsWith(".woff2")),
    );
    expect(fonts.length).toBeGreaterThan(0);
    for (const font of fonts) expect(font).toContain("/_next/static/media/");
  });
});

test.describe("the dev gallery", () => {
  test("does not exist in production", async ({ page }) => {
    const response = await page.goto("/dev/components");
    expect(response?.status()).toBe(404);
  });
});
