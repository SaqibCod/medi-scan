import { test } from "@playwright/test";

import {
  REPORT_ID,
  chatAnswers,
  doneReport,
  failedReport,
  landingReady,
  processingReport,
  reportIs,
  seedTheme,
} from "./support";

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
  // The "mobile" project is excluded in playwright.config.ts rather than here: it is Pixel 7,
  // which is also chromium, so a `browserName` guard let it through — and with a device scale
  // factor of 2.625 it quietly overwrote every desktop shot at 2.6x the size.

  for (const { path, name } of PAGES) {
    for (const size of WIDTHS) {
      for (const theme of ["dark", "light"] as const) {
        test(`${name} at ${size.label} in ${theme}`, async ({ context, page }) => {
          await seedTheme(context, theme);
          await landingReady(page);
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

test.describe("the report page", () => {
  // The "mobile" project is excluded in playwright.config.ts rather than here: it is Pixel 7,
  // which is also chromium, so a `browserName` guard let it through — and with a device scale
  // factor of 2.625 it quietly overwrote every desktop shot at 2.6x the size.

  const STATES = [
    { name: "report-done", body: () => doneReport() },
    { name: "report-processing", body: () => processingReport() },
    { name: "report-failed", body: () => failedReport("UNREADABLE") },
  ];

  for (const state of STATES) {
    for (const size of WIDTHS) {
      for (const theme of ["dark", "light"] as const) {
        test(`${state.name} at ${size.label} in ${theme}`, async ({ context, page }) => {
          await seedTheme(context, theme);
          await landingReady(page);
          await reportIs(page, state.body());
          await page.setViewportSize({ width: size.width, height: size.height });

          await page.goto(`/reports/${REPORT_ID}`);
          await page.waitForTimeout(900);

          await page.screenshot({
            path: `${OUT}/${state.name}--${size.label}--${theme}.png`,
            fullPage: true,
          });
        });
      }
    }
  }
});

test.describe("the biomarker pages", () => {
  // The "mobile" project is excluded in playwright.config.ts rather than here: it is Pixel 7,
  // which is also chromium, so a `browserName` guard let it through — and with a device scale
  // factor of 2.625 it quietly overwrote every desktop shot at 2.6x the size.

  for (const size of WIDTHS) {
    for (const theme of ["dark", "light"] as const) {
      test(`biomarker-ldl at ${size.label} in ${theme}`, async ({ context, page }) => {
        await seedTheme(context, theme);
        await landingReady(page);
        await reportIs(page, doneReport());
        await page.setViewportSize({ width: size.width, height: size.height });

        // Reached from a results row, so the "On your report" box has what it needs.
        await page.goto(`/biomarkers/ldl-cholesterol?report=${REPORT_ID}&b=b2`);
        await page.waitForTimeout(900);

        await page.screenshot({
          path: `${OUT}/biomarker-ldl--${size.label}--${theme}.png`,
          fullPage: true,
        });
      });
    }
  }

  test("biomarker index at 1440 in dark", async ({ context, page }) => {
    await seedTheme(context, "dark");
    await landingReady(page);
    await page.setViewportSize({ width: 1440, height: 1000 });

    await page.goto("/biomarkers");
    await page.waitForTimeout(500);

    await page.screenshot({ path: `${OUT}/biomarker-index--1440--dark.png`, fullPage: true });
  });
});

/**
 * The upload panel's three interesting states.
 *
 * Cropped to the panel rather than the whole page: the states differ inside one card, and a
 * full-page shot of a 4,700px landing page makes them hard to compare.
 */
test.describe("upload panel states", () => {
  // The "mobile" project is excluded in playwright.config.ts rather than here: it is Pixel 7,
  // which is also chromium, so a `browserName` guard let it through — and with a device scale
  // factor of 2.625 it quietly overwrote every desktop shot at 2.6x the size.

  for (const theme of ["dark", "light"] as const) {
    test(`panel states in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto("/");

      const panel = page.locator("#upload");
      await panel.scrollIntoViewIfNeeded();

      // A file chosen.
      await page.locator('#upload input[type="file"]').first().setInputFiles({
        name: "bloods-september.pdf",
        mimeType: "application/pdf",
        buffer: Buffer.from("%PDF-1.4\n% a small but real-looking name\n"),
      });
      await page.getByRole("checkbox").check();
      await panel.screenshot({ path: `${OUT}/upload--file-selected--${theme}.png` });

      // A file well over the limit, rather than one byte past it: at the exact boundary the
      // notice rounds to "10.0 MB and the limit is 10 MB", which is true but reads oddly and
      // is not what anyone actually hits.
      await page.locator('#upload input[type="file"]').first().setInputFiles({
        name: "whole-record.pdf",
        mimeType: "application/pdf",
        buffer: Buffer.alloc(Math.round(14.2 * 1024 * 1024), 0x20),
      });
      await page.getByText(/the limit is 10 MB/).waitFor();
      await panel.screenshot({ path: `${OUT}/upload--too-large--${theme}.png` });

      // A sample chosen, which replaces the tabs entirely.
      await page.getByRole("button", { name: /Try it/ }).first().click();
      await page.getByText("Sample: Lipid panel").waitFor();
      await panel.screenshot({ path: `${OUT}/upload--sample-selected--${theme}.png` });
    });
  }
});

test.describe("the chat panel", () => {
  // The "mobile" project is excluded in playwright.config.ts rather than here, for the same
  // reason as the groups above: Pixel 7 is chromium, so a browserName guard lets it through
  // and its 2.625 scale factor overwrites every desktop shot.

  /** The answer the live run actually produced, so the picture is not nicer than reality. */
  const ANSWER = {
    tokens: [
      "Your LDL Cholesterol is 162 mg/dL.",
      " It has an H flag, which means it is HIGH and above the range printed on your report.",
      // The leading blank line is the paragraph break the guard now carries through with the
      // sentence. Without it the client, which appends token events verbatim, renders
      // "your report.LDL stands for".
      "\n\nLDL stands for low-density lipoprotein. It carries cholesterol from your liver to cells around your body.",
    ],
    pages: [
      { slug: "ldl-cholesterol", title: "LDL Cholesterol", heading: "What it measures" },
    ],
  } as const;

  const DECLINED = {
    tokens: [
      "I cannot tell you if you should take medication, as only a doctor or nurse can decide on the right treatment for you.",
      " Your report does show that your Total Cholesterol is 238 mg/dL with an H flag for high.",
    ],
    finishReason: "DECLINED" as const,
    pages: [],
  };

  for (const theme of ["dark", "light"] as const) {
    test(`chat empty state at 1440 in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await reportIs(page, doneReport());
      await page.setViewportSize({ width: 1440, height: 1000 });

      await page.goto(`/reports/${REPORT_ID}`);
      await page.getByRole("heading", { name: "Ask about this report" }).waitFor();
      await page.waitForTimeout(600);

      await page.screenshot({ path: `${OUT}/chat--empty--1440--${theme}.png`, fullPage: true });
    });

    test(`chat answered at 1440 in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await reportIs(page, doneReport());
      await chatAnswers(page, ANSWER);
      await page.setViewportSize({ width: 1440, height: 1000 });

      await page.goto(`/reports/${REPORT_ID}`);
      // Through a suggestion chip, which is how most readers will ask their first question.
      await page.getByRole("button", { name: "Why is my Total Cholesterol flagged?" }).click();
      await page.getByText(/low-density lipoprotein/).waitFor();
      await page.waitForTimeout(400);

      await page.screenshot({ path: `${OUT}/chat--answered--1440--${theme}.png`, fullPage: true });
    });

    test(`chat declined at 1440 in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await reportIs(page, doneReport());
      await chatAnswers(page, DECLINED);
      await page.setViewportSize({ width: 1440, height: 1000 });

      await page.goto(`/reports/${REPORT_ID}`);
      await page.getByRole("button", { name: "Should I take medication for this?" }).click();
      await page.getByText("Medi-Scan doesn't give diagnosis or treatment advice.").waitFor();
      await page.waitForTimeout(400);

      await page.screenshot({ path: `${OUT}/chat--declined--1440--${theme}.png`, fullPage: true });
    });

    test(`chat on a phone in ${theme}`, async ({ context, page }) => {
      await seedTheme(context, theme);
      await landingReady(page);
      await reportIs(page, doneReport());
      await chatAnswers(page, ANSWER);
      await page.setViewportSize({ width: 390, height: 844 });

      await page.goto(`/reports/${REPORT_ID}`);
      // On a phone the panel is its own full-screen view, reached from under the results.
      await page.getByRole("button", { name: "Ask about this report" }).click();
      await page.getByRole("heading", { name: "Ask about this report" }).waitFor();

      // Scrolled to the top for the picture. The button sits under a long results table, so
      // the page is well down the document by the time it is tapped and the view swaps
      // without resetting that — the panel opens mid-way down. Done here rather than in the
      // component because that is a product decision, not a screenshot one.
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.waitForTimeout(400);

      // Viewport, not fullPage: this is a full-screen view, so what a reader sees is the
      // viewport - and fullPage re-paints the sticky site header part way down the image.
      await page.screenshot({ path: `${OUT}/chat--empty--390--${theme}.png` });

      await page.getByRole("button", { name: "Why is my Total Cholesterol flagged?" }).click();
      await page.getByText(/low-density lipoprotein/).waitFor();
      await page.waitForTimeout(400);

      await page.screenshot({ path: `${OUT}/chat--answered--390--${theme}.png` });
    });
  }
});
