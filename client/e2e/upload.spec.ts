import { expect, test, type Page } from "@playwright/test";

import {
  API,
  EXPOSED_HEADERS,
  REPORT_ID,
  doneReport,
  landingReady,
  problem,
  processingReport,
  reportIs,
  scannedReport,
  watchRequests,
} from "./support";

/**
 * Creating a report, from the landing page through to the results.
 *
 * The backend is intercepted rather than run, so every refusal in LLD 5.4 is reachable on
 * demand instead of only by luck.
 */

/**
 * A minimal PDF that really is one.
 *
 * Written out by hand rather than generated with a library: it is thirty lines, it never
 * changes, and a dependency whose only job is to produce this would be a dependency to keep
 * up to date forever.
 */
function smallPdf(): Buffer {
  const body = [
    "%PDF-1.4",
    "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj",
    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj",
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R" +
      "/Resources<</Font<</F1 5 0 R>>>>>>endobj",
    "4 0 obj<</Length 74>>stream",
    "BT /F1 12 Tf 20 150 Td (LIPID PANEL) Tj 0 -20 Td (LDL Cholesterol 162) Tj ET",
    "endstream endobj",
    "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj",
    "trailer<</Root 1 0 R>>",
    "%%EOF",
  ].join("\n");

  return Buffer.from(body, "latin1");
}

/** `POST /api/reports` accepts, and the report then follows the given states. */
async function acceptsUpload(page: Page, states: unknown[]) {
  await page.route(`${API}/api/reports`, (route) =>
    route.fulfill({
      status: 202,
      contentType: "application/json",
      headers: {
        Location: `/api/reports/${REPORT_ID}`,
        "Access-Control-Expose-Headers": EXPOSED_HEADERS,
      },
      body: JSON.stringify({
        id: REPORT_ID,
        status: "PENDING",
        sourceType: "PDF",
        createdAt: "2026-10-02T09:31:00Z",
        expiresAt: new Date(Date.now() + 24 * 3_600_000).toISOString(),
      }),
    }),
  );

  let call = 0;
  await page.route(`${API}/api/reports/${REPORT_ID}`, (route) => {
    const body = states[Math.min(call, states.length - 1)];
    call += 1;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}

/** `POST /api/reports` refuses with the given code. */
async function refusesUpload(
  page: Page,
  status: number,
  code: string,
  retryAfterSec?: number,
) {
  await page.route(`${API}/api/reports`, (route) =>
    route.fulfill(problem(status, code, {}, retryAfterSec)),
  );
}

async function tickConsent(page: Page) {
  await page.getByRole("checkbox").check();
}

const analyze = (page: Page) => page.getByRole("button", { name: /^Analyze report/ });

test.describe("the sample flow", () => {
  test("select, consent, analyze, and read the results", async ({ page }) => {
    await landingReady(page);
    await acceptsUpload(page, [processingReport(), doneReport()]);
    await page.goto("/");

    await page.getByRole("button", { name: /Try it/ }).first().click();
    await expect(page.getByText("Sample: Lipid panel")).toBeVisible();

    await tickConsent(page);
    await analyze(page).click();

    await expect(page).toHaveURL(new RegExp(`/reports/${REPORT_ID}$`));
    await expect(page.getByRole("heading", { name: /Your report/ })).toBeVisible();
    await expect(page.getByText("Total Cholesterol").first()).toBeVisible();
  });

  test("the count tiles filter the table", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());
    await page.goto(`/reports/${REPORT_ID}`);

    await expect(page.getByText("5 of 5")).toBeVisible();

    await page.getByRole("radio", { name: /2\s*High/ }).click();

    await expect(page.getByText("2 of 5")).toBeVisible();
    await expect(page.getByText("VLDL Cholesterol")).toHaveCount(0);
  });
});

test.describe("the PDF flow", () => {
  test("a small generated PDF goes through to done", async ({ page }) => {
    await landingReady(page);
    await acceptsUpload(page, [processingReport("PENDING"), processingReport(), doneReport()]);
    await page.goto("/");

    await page.locator('#upload input[type="file"]').first().setInputFiles({
      name: "bloods.pdf",
      mimeType: "application/pdf",
      buffer: smallPdf(),
    });
    await tickConsent(page);
    await analyze(page).click();

    await expect(page.getByRole("heading", { name: /Reading your report/ })).toBeVisible();
    await expect(page.getByRole("heading", { name: /Your report/ })).toBeVisible({
      timeout: 10_000,
    });
  });
});

test.describe("the paste flow", () => {
  test("text under 20 characters is refused, and valid text is sent", async ({ page }) => {
    await landingReady(page);
    await acceptsUpload(page, [doneReport()]);
    await page.goto("/");

    await page.getByRole("tab", { name: "Paste text" }).click();
    const box = page.getByRole("textbox");

    await box.fill("too short");
    await tickConsent(page);
    await expect(page.getByText("That text is too short to read.")).toBeVisible();
    await expect(analyze(page)).toBeDisabled();

    await box.fill("LIPID PANEL\nLDL Cholesterol 162 mg/dL (<100)\nHDL 52 mg/dL");
    await expect(analyze(page)).toBeEnabled();

    await analyze(page).click();
    await expect(page).toHaveURL(new RegExp(`/reports/${REPORT_ID}$`));
  });

  test("the counter tracks what will be sent", async ({ page }) => {
    await landingReady(page);
    await page.goto("/");

    await page.getByRole("tab", { name: "Paste text" }).click();
    await page.getByRole("textbox").fill("  " + "x".repeat(24) + "  ");

    // Trimmed, because that is the length the check uses and the length that is sent.
    await expect(page.getByText("24 / 50,000")).toBeVisible();
  });
});

test.describe("every upload refusal", () => {
  const CASES: Array<[string, number, string, string, number | undefined]> = [
    ["FILE_TOO_LARGE", 413, "FILE_TOO_LARGE", "This file is too large.", undefined],
    ["UNSUPPORTED_FILE_TYPE", 415, "UNSUPPORTED_FILE_TYPE", "We can't read this kind of file.", undefined],
    ["FILE_SIGNATURE_MISMATCH", 400, "FILE_SIGNATURE_MISMATCH", "This file isn't really a PDF.", undefined],
    ["BUSY", 429, "BUSY", "Lots of reports are being read right now.", 60],
    ["CAPACITY", 429, "CAPACITY", "The demo has hit today's AI limit.", 3600],
    ["SAMPLE_NOT_FOUND", 404, "SAMPLE_NOT_FOUND", "That sample isn't available.", undefined],
    ["RATE_LIMITED", 429, "RATE_LIMITED", "You've reached this hour's upload limit.", 1071],
    ["INTERNAL_ERROR", 500, "INTERNAL_ERROR", "Something broke on our side.", undefined],
  ];

  for (const [name, status, code, title, retryAfterSec] of CASES) {
    test(`${name} is explained, and the report is not created`, async ({ page }) => {
      await landingReady(page);
      await refusesUpload(page, status, code, retryAfterSec);
      await page.goto("/");

      await page.getByRole("button", { name: /Try it/ }).first().click();
      await tickConsent(page);
      await analyze(page).click();

      await expect(page.getByText(title)).toBeVisible();
      await expect(page).toHaveURL(/\/$/);

      if (code !== "RATE_LIMITED" && code !== "CAPACITY") {
        // The button comes back rather than staying stuck on "Sending…".
        await expect(analyze(page)).toBeEnabled();
      }
    });
  }

  test("RATE_LIMITED counts down from Retry-After", async ({ page }) => {
    await landingReady(page);
    await refusesUpload(page, 429, "RATE_LIMITED", 1071);
    await page.goto("/");

    await page.getByRole("button", { name: /Try it/ }).first().click();
    await tickConsent(page);
    await analyze(page).click();

    await expect(page.getByText(/Try again in 17 min 51 s\./)).toBeVisible();
    await expect(page.getByText(/Try again in 17 min 4[0-9] s\./)).toBeVisible({ timeout: 5_000 });
  });

  test("CAPACITY also raises the page-wide banner", async ({ page }) => {
    await landingReady(page);
    await refusesUpload(page, 429, "CAPACITY", 3600);
    await page.goto("/");

    await page.getByRole("button", { name: /Try it/ }).first().click();
    await tickConsent(page);
    await analyze(page).click();

    // The whole app shares one budget, so this is a page-wide fact, not a per-upload one.
    await expect(page.getByText(/New reports and chat open again/)).toBeVisible();
  });
});

test.describe("photos, with OCR on", () => {
  /**
   * A PNG that really is one: the signature, then a minimal 1x1 IHDR/IDAT/IEND.
   *
   * The browser only needs it to look like a PNG to the client-side checks, and the server is
   * intercepted - but writing a real one keeps the test honest about what was sent, and a
   * deliberately broken file would be testing the refusal path by accident.
   */
  function smallPng(): Buffer {
    return Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
      "base64",
    );
  }

  test("a photo is accepted, sent, and the results say it was read from one", async ({ page }) => {
    await landingReady(page);
    const hosts = watchRequests(page);
    await acceptsUpload(page, [processingReport("PENDING"), scannedReport("OCR")]);
    await page.goto("/");

    // Addressed by its label rather than its `accept`: with OCR on, every input in the panel
    // takes an image, so `accept*="image/"` no longer picks out the phone tile.
    await page.getByLabel("Take a photo").setInputFiles({
      name: "report.png",
      mimeType: "image/png",
      buffer: smallPng(),
    });

    // The phase-3 notice must be gone: offering a photo and then refusing it was the whole
    // thing the flag existed to prevent, and so is refusing one the build can now read.
    await expect(page.getByText("Photos are coming soon.")).toHaveCount(0);

    await tickConsent(page);
    await analyze(page).click();

    await expect(page).toHaveURL(new RegExp(`/reports/${REPORT_ID}$`));
    await expect(page.getByRole("heading", { name: /Your report/ })).toBeVisible({
      timeout: 10_000,
    });

    // The reader is told their numbers came off a photograph, and asked to check them.
    await expect(page.getByText("Read from a photo or scan.")).toBeVisible();
    await expect(page.getByText(/OCR can misread numbers/)).toBeVisible();

    // The image itself never leaves for anywhere but our own API. Google is reached for the
    // sign-in library the header offers and for nothing else — no report, and no photograph,
    // is ever sent there.
    const external = [...hosts].filter((host) => !host.startsWith("localhost"));
    expect(external.filter((host) => host !== "accounts.google.com")).toEqual([]);
  });

  test("a mixed report names the part that was scanned", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, scannedReport("MIXED"));
    await page.goto(`/reports/${REPORT_ID}`);

    await expect(page.getByText("Some pages were read from a scan.")).toBeVisible();
    await expect(page.getByText("Read from a photo or scan.")).toHaveCount(0);
  });

  test("a text-layer report carries no OCR notice", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());
    await page.goto(`/reports/${REPORT_ID}`);

    await expect(page.getByText("Total Cholesterol").first()).toBeVisible();
    await expect(page.getByText(/OCR can misread/)).toHaveCount(0);
  });

  test("the drop zone offers photos in words as well as in its accept attribute", async ({
    page,
  }) => {
    await landingReady(page);
    await page.goto("/");

    // B4's rule, now running the other way: the build can read a photo, so the copy must say
    // so rather than still promising it "soon".
    // Counted rather than asserted visible: the drop zone's hint and the landing page's upload
    // fact both carry this line, and which of the two is on screen depends on the viewport.
    // What matters here is that neither says "coming soon" any more.
    await expect(page.getByText("PDF · PNG · JPEG — up to 10 MB")).toHaveCount(2);
    await expect(page.getByText(/photos coming soon/)).toHaveCount(0);

    // And the picker really offers them, which is the other half of the same promise.
    await expect(page.getByLabel("Choose file")).toHaveAttribute(
      "accept",
      "application/pdf,image/png,image/jpeg",
    );
  });
});
