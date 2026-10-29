import { expect, test } from "@playwright/test";

/**
 * The real flow, against the real backend and the real model.
 *
 * Not part of `npm run e2e`: it needs Postgres, the backend and a live API key, so it runs
 * on purpose (LLD 13 item 2) rather than on every commit. Nothing here is mocked.
 */

const SITE = process.env.LIVE_SITE ?? "http://localhost:3000";

test.use({ baseURL: SITE });

// Not serial: one blocked test must not skip the rest. The stages before and after the LLM
// call are independently worth verifying, and on 2026-10-29 the model was the only part
// that could not run.
test.describe.configure({ timeout: 180_000 });

/** Picks a sample, consents, submits, and waits for the report to settle. */
async function runSample(page: import("@playwright/test").Page, title: string) {
  await page.goto("/");
  await page.getByRole("button", { name: /Try it/ }).first().waitFor();

  const card = page.locator("li", { hasText: title }).first();
  await card.getByRole("button", { name: /Try it|Selected/ }).click();

  await expect(page.getByText(`Sample: ${title}`)).toBeVisible();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: /^Analyze report/ }).click();

  await expect(page).toHaveURL(/\/reports\/[0-9a-f-]{36}$/);
  return page.url();
}

/**
 * Waits for a terminal state, done or failed.
 *
 * The alternation has to cover every failed headline, not just the likely ones: a matcher
 * that only knows the happy path turns "the AI provider rejected us" into a timeout, which
 * looks identical to "still processing" and hides the real reason.
 */
async function waitForSettled(page: import("@playwright/test").Page) {
  await expect(
    page.getByRole("heading", {
      name: /Your report|We couldn't|didn't find|Something went|too long to read|unavailable right now|was interrupted|today's limit/,
    }),
  ).toBeVisible({ timeout: 150_000 });
}

for (const sample of ["Lipid panel", "Complete blood count", "Thyroid panel"]) {
  test(`sample: ${sample} reaches a result`, async ({ page }) => {
    await runSample(page, sample);
    await waitForSettled(page);

    await expect(page.getByRole("heading", { name: /Your report/ })).toBeVisible();
    await expect(page.getByText(/All results/).first()).toBeVisible();

    // The disclaimer is on every page that shows results.
    await expect(
      page.getByText(/Medi-Scan explains what your report says and is not medical advice\./),
    ).toBeVisible();

    await page.screenshot({
      path: `e2e/__screenshots__/live--${sample.replace(/\s+/g, "-").toLowerCase()}.png`,
      fullPage: true,
    });
  });
}

test("a text PDF reaches a result", async ({ page }) => {
  const pdf = [
    "%PDF-1.4",
    "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj",
    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj",
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 400 300]/Contents 4 0 R" +
      "/Resources<</Font<</F1 5 0 R>>>>>>endobj",
    "4 0 obj<</Length 260>>stream",
    "BT /F1 11 Tf 30 260 Td (LIPID PANEL) Tj",
    "0 -20 Td (Total Cholesterol 238 mg/dL ref <200) Tj",
    "0 -20 Td (LDL Cholesterol 162 mg/dL ref <100) Tj",
    "0 -20 Td (HDL Cholesterol 52 mg/dL ref >40) Tj",
    "0 -20 Td (Triglycerides 140 mg/dL ref <150) Tj ET",
    "endstream endobj",
    "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj",
    "trailer<</Root 1 0 R>>",
    "%%EOF",
  ].join("\n");

  await page.goto("/");
  await page.locator('#upload input[type="file"]').first().setInputFiles({
    name: "lipids.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from(pdf, "latin1"),
  });
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: /^Analyze report/ }).click();

  await expect(page).toHaveURL(/\/reports\/[0-9a-f-]{36}$/);
  await waitForSettled(page);

  await page.screenshot({ path: "e2e/__screenshots__/live--pdf.png", fullPage: true });
});

test("pasted text reaches a result", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "Paste text" }).click();
  await page.getByRole("textbox").fill(
    [
      "THYROID PANEL",
      "TSH            0.21 mIU/L      (0.40 - 4.00)",
      "Free T4        1.6  ng/dL      (0.8 - 1.8)",
    ].join("\n"),
  );
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: /^Analyze report/ }).click();

  await expect(page).toHaveURL(/\/reports\/[0-9a-f-]{36}$/);
  await waitForSettled(page);

  await page.screenshot({ path: "e2e/__screenshots__/live--pasted.png", fullPage: true });
});

test("an image-only PDF fails as UNREADABLE with the OCR-off copy", async ({ page }) => {
  // A valid PDF with a page and no text operators at all: nothing for PDFBox to extract,
  // which is exactly the scanned-report case until OCR lands.
  const imageOnly = [
    "%PDF-1.4",
    "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj",
    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj",
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>endobj",
    "4 0 obj<</Length 44>>stream",
    "0.5 0.5 0.5 rg 20 20 160 160 re f",
    "endstream endobj",
    "trailer<</Root 1 0 R>>",
    "%%EOF",
  ].join("\n");

  await page.goto("/");
  await page.locator('#upload input[type="file"]').first().setInputFiles({
    name: "scan.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from(imageOnly, "latin1"),
  });
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: /^Analyze report/ }).click();

  await waitForSettled(page);

  await expect(page.getByRole("heading", { name: /We couldn't read text from this file/ })).toBeVisible();
  await expect(page.getByText(/coming soon/)).toBeVisible();
  await expect(page.getByText(/clearer photo/)).toHaveCount(0);
  await expect(page.getByText(/UNREADABLE · report/)).toBeVisible();

  await page.screenshot({ path: "e2e/__screenshots__/live--unreadable.png", fullPage: true });
});

test("deleting really removes it", async ({ page }) => {
  const url = await runSample(page, "Lipid panel");
  await waitForSettled(page);

  // The done view has a "Delete report" button; the failed view has a "Delete this report"
  // link. Either is a valid starting point and the dialog is the same.
  const fromDone = page.getByRole("button", { name: /^Delete report$/ });
  const fromFailed = page.getByRole("button", { name: /Delete this report/ });
  await ((await fromDone.count()) > 0 ? fromDone : fromFailed).first().click();

  await page.getByRole("alertdialog").getByRole("button", { name: /^Delete report$/ }).click();

  await expect(page.getByText("Report deleted.")).toBeVisible();
  await expect(page).toHaveURL(/\/$/);

  // Reloading it is a real 404 from the real server.
  await page.goto(url);
  await expect(page.getByRole("heading", { name: /We can't find this report/ })).toBeVisible();
});

test("storage, titles and origins after the whole flow", async ({ page }) => {
  const hosts = new Set<string>();
  page.on("request", (r) => hosts.add(new URL(r.url()).host));

  const titles: string[] = [];
  await runSample(page, "Lipid panel");
  titles.push(await page.title());
  await waitForSettled(page);
  titles.push(await page.title());

  // A results row links to the explainer. There are no rows on a failed report, so go
  // straight there — the point of this test is storage, titles and origins, not the table.
  const row = page.getByRole("link", { name: /Cholesterol/ });
  if ((await row.count()) > 0) await row.first().click();
  else await page.goto("/biomarkers/ldl-cholesterol");
  titles.push(await page.title());

  const stored = await page.evaluate(() => ({
    local: Object.fromEntries(Object.keys(localStorage).map((k) => [k, localStorage.getItem(k)])),
    session: Object.fromEntries(
      Object.keys(sessionStorage).map((k) => [k, sessionStorage.getItem(k)]),
    ),
  }));

  console.log("LIVE_STORAGE " + JSON.stringify(stored));
  console.log("LIVE_TITLES " + JSON.stringify(titles));
  console.log("LIVE_HOSTS " + JSON.stringify([...hosts]));

  expect(Object.keys(stored.local).filter((k) => k !== "ms-theme")).toEqual([]);
  expect(Object.keys(stored.session).filter((k) => k !== "ms.guest")).toEqual([]);
  expect(JSON.stringify(stored)).not.toMatch(/Cholesterol|238|162/);
  for (const host of hosts) expect(["localhost:3000", "localhost:8080"]).toContain(host);
});
