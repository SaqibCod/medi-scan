/**
 * Takes the review screenshots.
 *
 * Used for the approval points and for the visual review against docs/ui/screens/ (LLD 13).
 * Not part of the test suite — Playwright's own e2e specs do the asserting; this just makes
 * pictures at exact viewport sizes, which a real browser window cannot do below its minimum
 * width.
 *
 *   node scripts/shoot.mjs <base-url> <out-dir> <path>[:<name>] ...
 */

import { mkdirSync } from "node:fs";
import { resolve } from "node:path";

import { chromium } from "playwright";

const [baseUrl, outDir, ...targets] = process.argv.slice(2);

if (!baseUrl || !outDir || targets.length === 0) {
  console.error("usage: node scripts/shoot.mjs <base-url> <out-dir> <path>[:<name>] ...");
  process.exit(1);
}

const VIEWPORTS = [
  { name: "1440", width: 1440, height: 1000 },
  { name: "390", width: 390, height: 844 },
];

const THEMES = ["dark", "light"];

mkdirSync(resolve(outDir), { recursive: true });

const browser = await chromium.launch();

try {
  for (const target of targets) {
    const [path, label] = target.includes(":") ? target.split(":") : [target, null];
    const slug = label ?? (path === "/" ? "home" : path.replace(/^\//, "").replace(/\//g, "-"));

    for (const viewport of VIEWPORTS) {
      for (const theme of THEMES) {
        const context = await browser.newContext({
          viewport: { width: viewport.width, height: viewport.height },
          deviceScaleFactor: 1,
        });

        // Seeded before the first script runs, so the pre-hydration theme script reads it and
        // the very first paint is already in the theme being captured.
        await context.addInitScript(
          ([key, value]) => {
            try {
              window.localStorage.setItem(key, value);
            } catch {}
          },
          ["ms-theme", theme],
        );

        const page = await context.newPage();
        await page.goto(new URL(path, baseUrl).href, { waitUntil: "networkidle" });
        // The helix is client-only and animates in; give it a beat so it is in the picture.
        await page.waitForTimeout(600);

        const file = resolve(outDir, `${slug}--${viewport.name}--${theme}.png`);
        await page.screenshot({ path: file, fullPage: true });
        console.log(file);

        await context.close();
      }
    }
  }
} finally {
  await browser.close();
}
