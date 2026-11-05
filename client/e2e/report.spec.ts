import { expect, test, type Page } from "@playwright/test";

import {
  API,
  GUEST_STORAGE_KEY,
  THEME_STORAGE_KEY,
  REPORT_ID,
  doneReport,
  failedReport,
  landingReady,
  problem,
  processingReport,
  reportIs,
  watchRequests,
} from "./support";

/** `GET` answers with each state in turn, so a page can be watched moving between them. */
async function reportBecomes(page: Page, states: unknown[]) {
  let call = 0;
  await page.route(`${API}/api/reports/${REPORT_ID}`, (route) => {
    if (route.request().method() === "DELETE") {
      return route.fulfill({ status: 204, body: "" });
    }
    const body = states[Math.min(call, states.length - 1)];
    call += 1;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}

test.describe("the processing view", () => {
  test("updates itself to the results without a reload", async ({ page }) => {
    await landingReady(page);
    await reportBecomes(page, [processingReport("PENDING"), processingReport(), doneReport()]);

    await page.goto(`/reports/${REPORT_ID}`);

    await expect(page.getByText("QUEUED").first()).toBeVisible();
    await expect(page.getByRole("heading", { name: /Your report/ })).toBeVisible({
      timeout: 10_000,
    });
  });

  test("claims no progress it cannot know", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, processingReport());

    await page.goto(`/reports/${REPORT_ID}`);
    await expect(page.getByText("PROCESSING")).toBeVisible();

    // B3: the backend reports PENDING or PROCESSING and nothing finer, so there is no
    // progress bar and no step marked done.
    await expect(page.getByRole("progressbar")).toHaveCount(0);
    await expect(page.getByText(/\d+%/)).toHaveCount(0);
  });
});

test.describe("the failed view", () => {
  const CASES: Array<[string, string]> = [
    ["UNREADABLE", "We couldn't read text from this file."],
    ["DOCUMENT_TOO_LONG", "This document is too long to read."],
    ["NO_RESULTS_FOUND", "We didn't find lab results in this document."],
    ["EXTRACTION_FAILED", "Something went wrong reading the results."],
    ["CAPACITY", "The demo has hit today's limit."],
    ["LLM_UNAVAILABLE", "The AI service is unavailable right now."],
    ["INTERRUPTED", "Processing was interrupted."],
  ];

  for (const [code, headline] of CASES) {
    test(`${code} shows its own copy and the report's code line`, async ({ page }) => {
      await landingReady(page);
      await reportIs(page, failedReport(code));

      await page.goto(`/reports/${REPORT_ID}`);

      await expect(page.getByRole("heading", { name: headline })).toBeVisible();
      // B9: the report id, because a background failure has no request id.
      await expect(page.getByText(`${code} · report ${REPORT_ID.slice(0, 8)}`)).toBeVisible();
      await expect(page.getByText("Server-side wording.")).toHaveCount(0);
    });
  }

  test("the UNREADABLE copy offers nothing this build cannot do", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, failedReport("UNREADABLE"));

    await page.goto(`/reports/${REPORT_ID}`);

    // Phase 4 runs OCR, so the advice is now advice the reader can act on: a clearer photo.
    // The old "coming soon" line would be a lie about a build that already tried OCR and
    // still could not read the page (B4 - never promise, and never under-promise either).
    await expect(page.getByText(/clearer photo/)).toBeVisible();
    await expect(page.getByText(/coming soon/)).toHaveCount(0);
  });
});

test.describe("errors on the report page", () => {
  test("an unknown report id shows 'report not available'", async ({ page }) => {
    await landingReady(page);
    await page.route(`${API}/api/reports/*`, (route) =>
      route.fulfill(problem(404, "REPORT_NOT_FOUND")),
    );

    await page.goto("/reports/11111111-2222-4333-8444-555555555555");

    await expect(page.getByRole("heading", { name: /We can't find this report/ })).toBeVisible();
  });

  test("an id that is not a UUID never reaches the API", async ({ page }) => {
    await landingReady(page);
    let calls = 0;
    await page.route(`${API}/api/reports/**`, (route) => {
      calls += 1;
      return route.fulfill(problem(404, "REPORT_NOT_FOUND"));
    });

    await page.goto("/reports/latest");

    await expect(page.getByRole("heading", { name: /We can't find this report/ })).toBeVisible();
    expect(calls).toBe(0);
  });

  test("a dead session shows 'guest session ended'", async ({ page }) => {
    await landingReady(page);
    await page.route(`${API}/api/reports/${REPORT_ID}`, (route) =>
      route.fulfill(problem(401, "SESSION_INVALID")),
    );

    await page.goto(`/reports/${REPORT_ID}`);

    await expect(page.getByRole("heading", { name: /Your guest session has ended/ })).toBeVisible();
  });
});

test.describe("deleting", () => {
  test("from the done view: confirm, toast, and the report is gone", async ({ page }) => {
    await landingReady(page);

    let deleted = false;
    await page.route(`${API}/api/reports/${REPORT_ID}`, (route) => {
      if (route.request().method() === "DELETE") {
        deleted = true;
        return route.fulfill({ status: 204, body: "" });
      }
      return deleted
        ? route.fulfill(problem(404, "REPORT_NOT_FOUND"))
        : route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(doneReport()),
          });
    });

    await page.goto(`/reports/${REPORT_ID}`);
    await page.getByRole("button", { name: /Delete report/ }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("Delete this report?")).toBeVisible();
    await dialog.getByRole("button", { name: /^Delete report$/ }).click();

    await expect(page.getByText("Report deleted.")).toBeVisible();
    await expect(page).toHaveURL(/\/$/);

    // Reloading it now really is a 404, not a cached copy.
    await page.goto(`/reports/${REPORT_ID}`);
    await expect(page.getByRole("heading", { name: /We can't find this report/ })).toBeVisible();
  });

  test("from the processing view", async ({ page }) => {
    await landingReady(page);
    await reportBecomes(page, [processingReport()]);

    await page.goto(`/reports/${REPORT_ID}`);
    await page.getByRole("button", { name: /Cancel and delete/ }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: /^Delete report$/ }).click();

    await expect(page.getByText("Report deleted.")).toBeVisible();
  });

  test("can be cancelled, and Escape cancels too", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());

    await page.goto(`/reports/${REPORT_ID}`);

    const trigger = page.getByRole("button", { name: /Delete report/ });
    await trigger.click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);

    await trigger.click();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    // Deleting cannot be undone, so leaving must be a choice — and focus comes back.
    await expect(trigger).toBeFocused();
    await expect(page).toHaveURL(new RegExp(REPORT_ID));
  });
});

test.describe("what the browser keeps and who it talks to", () => {
  test("stores only the theme and the guest token, and contacts only us", async ({
    context,
    page,
  }) => {
    const hosts = watchRequests(page);

    await landingReady(page);
    await reportIs(page, doneReport());

    await page.goto("/");
    await page.getByRole("button", { name: /Try it/ }).first().click();
    await page.getByRole("checkbox").check();
    await page.goto(`/reports/${REPORT_ID}`);
    await expect(page.getByText("Total Cholesterol").first()).toBeVisible();

    const stored = await page.evaluate(() => ({
      local: Object.fromEntries(
        Object.keys(localStorage).map((k) => [k, localStorage.getItem(k) ?? ""]),
      ),
      session: Object.fromEntries(
        Object.keys(sessionStorage).map((k) => [k, sessionStorage.getItem(k) ?? ""]),
      ),
    }));

    // Only these keys are permitted. Neither has to be present — the theme is written only
    // once someone picks one, and the guest session is created lazily — so this is an
    // allowlist rather than an expected set.
    expect(Object.keys(stored.local).filter((k) => k !== THEME_STORAGE_KEY)).toEqual([]);
    expect(Object.keys(stored.session).filter((k) => k !== GUEST_STORAGE_KEY)).toEqual([]);

    // No report content anywhere in storage.
    const everything = JSON.stringify(stored);
    expect(everything).not.toMatch(/Cholesterol|238|162|normal range/);

    // The title names nothing about the report.
    expect(await page.title()).toBe("Your report · Medi-Scan");

    // Only the site and the API.
    for (const host of hosts) {
      expect(["localhost:3210", "localhost:8080"]).toContain(host);
    }

    await context.close();
  });
});

/**
 * The ways into chat on a phone (LLD 10.1).
 *
 * The button under the results is the one the mockup shows. The floating one exists because a
 * report with a dozen markers puts that button a long scroll away from where the reader is
 * actually looking, and a question occurs to you while you are reading a row, not after you
 * have reached the end.
 */
test.describe("opening chat on a phone", () => {
  test.skip(({ viewport }) => (viewport?.width ?? 0) > 700, "phone layout only");

  test("the floating button appears only after the summary has gone past", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());
    await page.goto(`/reports/${REPORT_ID}`);

    // Waited for: until the results render the document is one viewport tall, so a "hidden"
    // assertion would pass against a page that simply has not loaded yet.
    await expect(page.getByText(/Ranges come from your report/)).toBeVisible();

    const floating = page.getByTestId("floating-ask");

    // While the summary is on screen the reader is reading it, and a button over the text
    // would be in the way rather than handy.
    await expect(floating).toBeHidden();

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    await expect(floating).toBeVisible();
  });

  test("it opens the chat view, and back returns to the same place", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());
    await page.goto(`/reports/${REPORT_ID}`);
    // Waited for: until the results render the document is one viewport tall and there is
    // nothing to scroll, so the "before" position would be 0 and the test would prove nothing.
    await expect(page.getByText(/Ranges come from your report/)).toBeVisible();

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    const before = await page.evaluate(() => window.scrollY);
    expect(before).toBeGreaterThan(0);

    await page.getByTestId("floating-ask").click();
    await expect(page.getByRole("heading", { name: "Ask about this report" })).toBeVisible();
    // The chat view is a screen of its own, so it starts at the top of itself.
    expect(await page.evaluate(() => window.scrollY)).toBe(0);

    await page.getByRole("button", { name: "Back to results" }).click();
    await expect(page.getByText(/Ranges come from your report/)).toBeVisible();

    // The whole point: the reader lands back on the row they were looking at.
    expect(await page.evaluate(() => window.scrollY)).toBe(before);
  });

  test("the desktop panel is not rendered on a phone", async ({ page }) => {
    await landingReady(page);
    await reportIs(page, doneReport());
    await page.goto(`/reports/${REPORT_ID}`);

    // The panel exists only inside the phone view, which has not been opened yet.
    await expect(page.getByRole("heading", { name: "Ask about this report" })).toBeHidden();
  });
});
