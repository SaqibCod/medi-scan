import { expect, test } from "@playwright/test";

import { backendUp, REPORT_ID, reportIs, doneReport, guestSession, watchForProblems, watchRequests } from "./support";
import {
  authEndpoints,
  googleStub,
  listItem,
  REFRESH_KEY,
  reportList,
  startSignedIn,
  storageKeys,
  TAB_KEY,
  trendEndpoints,
  USER_NAME,
} from "./support-auth";

/**
 * The page body.
 *
 * The header has its own sign-in button and the toasts repeat the page's own wording, both
 * deliberately — so every assertion below says which one it means.
 */
function main(page: import("@playwright/test").Page) {
  return page.locator("#main");
}


/**
 * Signing in, staying signed in, and signing out (LLD 10, e2e 1 to 5).
 *
 * Against a production build, with Google replaced by a stub. What is being tested is the
 * part this app actually owns: what the browser keeps, which tab holds it, and what happens
 * to the other tabs.
 */

test.describe("signing in from a report", () => {
  test("claims the guest report, says so, and keeps it for 30 days", async ({ page }) => {
    await backendUp(page);
    await guestSession(page);
    await googleStub(page);
    await authEndpoints(page, { claimedReportCount: 1, newUser: true, reportCount: 1 });
    await reportIs(page, doneReport());
    await reportList(page, { all: [listItem()], DONE: [listItem()], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    await page.goto(`/reports/${REPORT_ID}`);

    // A guest's report says it is going away, and offers the way to stop that.
    await expect(page.getByText(/deleted automatically in/)).toBeVisible();

    // The only Google button in the page body is the one in the guest note.
    await main(page).getByRole("button", { name: "Sign in with Google" }).click();

    // The welcome names them and says what moved.
    await expect(page.getByText(`Welcome to Medi-Scan, ${USER_NAME}.`)).toBeVisible();
    await expect(
      page.getByText("Your 1 guest report moved to your account. It is now kept for 30 days."),
    ).toBeVisible();

    // The guest token is gone — those reports belong to the account now.
    const keys = await storageKeys(page);
    expect(keys.session).not.toContain("ms.guest");
    expect(keys.session).toContain(REFRESH_KEY);

    // And the report is in the list, with the longer life.
    await page.goto("/reports");
    await expect(page.getByText("Expires in 30 days")).toBeVisible();
  });

  test("the Google ID token is never written anywhere", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    await authEndpoints(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    await page.goto("/account");
    await main(page).getByRole("button", { name: "Sign in with Google" }).click();
    await expect(main(page).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    const everything = await page.evaluate(() =>
      JSON.stringify({ local: { ...window.localStorage }, session: { ...window.sessionStorage } }),
    );

    expect(everything).not.toContain("e2e-google-id-token");
    // Nor the access token, which lives in a variable and nowhere else.
    expect(everything).not.toContain("e2e-access-token");
  });
});

test.describe("staying signed in", () => {
  test("a reload refreshes once and stays signed in", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    const calls = await authEndpoints(page, { reportCount: 3 });
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });
    await startSignedIn(page);

    await page.goto("/account");
    await expect(main(page).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    expect(calls.refresh).toBe(1);
    // Never a sign-in: the refresh token alone restores the session.
    expect(calls.google ?? 0).toBe(0);
  });

  test("a new tab signs in through the prompt, with no click", async ({ page }) => {
    await backendUp(page);
    // A fresh tab has no refresh token; One Tap is what brings it back.
    await googleStub(page, { autoSignIn: true });
    await authEndpoints(page, { reportCount: 3 });
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    await page.goto("/account");

    await expect(main(page).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __gis: { prompted: number } }).__gis.prompted)).toBe(1);
  });
});

test.describe("the duplicated tab", () => {
  test("drops the copied refresh token and signs itself in separately", async ({ browser }) => {
    const context = await browser.newContext();

    const first = await context.newPage();
    await backendUp(first);
    await googleStub(first);
    await authEndpoints(first, { reportCount: 3 });
    await reportList(first, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(first, { markers: [] });
    await startSignedIn(first);

    await first.goto("/account");
    await expect(main(first).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    const original = await first.evaluate(() => ({
      refresh: window.sessionStorage.getItem("ms.refresh"),
      tab: window.sessionStorage.getItem("ms.tab"),
    }));
    expect(original.tab).toBeTruthy();

    // "Duplicate tab": a new page that opens holding a copy of the first one's storage.
    const copy = await context.newPage();
    await backendUp(copy);
    await googleStub(copy, { autoSignIn: true });
    await authEndpoints(copy, { reportCount: 3 });
    await reportList(copy, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(copy, { markers: [] });
    await copy.addInitScript(
      ([refresh, tab]) => {
        window.sessionStorage.setItem("ms.refresh", refresh as string);
        window.sessionStorage.setItem("ms.tab", tab as string);
      },
      [original.refresh ?? "", original.tab ?? ""],
    );

    await copy.goto("/account");

    // The copy signs in under a family of its own rather than rotating the inherited token.
    await expect(main(copy).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    const copyTab = await copy.evaluate(() => window.sessionStorage.getItem("ms.tab"));
    expect(copyTab).not.toBe(original.tab);

    // And neither tab was signed out by the other.
    await first.reload();
    await expect(main(first).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();
    await expect(main(copy).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    await context.close();
  });
});

test.describe("signing out", () => {
  test("reaches every tab, and each revokes its own token", async ({ browser }) => {
    const context = await browser.newContext();

    const pages = [] as Awaited<ReturnType<typeof context.newPage>>[];
    const callCounts = [] as Record<string, number>[];

    for (let index = 0; index < 2; index += 1) {
      const page = await context.newPage();
      await backendUp(page);
      await googleStub(page);
      callCounts.push(await authEndpoints(page, { reportCount: 3 }));
      await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
      await trendEndpoints(page, { markers: [] });
      await startSignedIn(page);
      await page.goto("/account");
      await expect(main(page).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();
      pages.push(page);
    }

    const [one, two] = pages;

    await one.getByRole("button", { name: "Sign out" }).click();

    // The tab that asked goes home and says so.
    await expect(one.getByText("Signed out.")).toBeVisible();
    await expect(one).toHaveURL(/\/$/);

    // The other tab finds itself signed out where it stands, with its own token revoked.
    await expect(main(two).getByText("Sign in to see")).toBeVisible();
    expect(callCounts[1].logout).toBe(1);

    for (const page of pages) {
      const keys = await storageKeys(page);
      expect(keys.session).not.toContain(REFRESH_KEY);
    }

    await context.close();
  });

  test("deleting the account clears everything and leaves the page signed out", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    const calls = await authEndpoints(page, { reportCount: 2 });
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });
    await startSignedIn(page);

    await page.goto("/account");
    await page.getByRole("button", { name: /Delete account/ }).click();
    await page.getByRole("checkbox").click();
    await page.getByRole("button", { name: /Delete my account/ }).click();

    await expect(page.getByText("Account deleted.")).toBeVisible();
    expect(calls.deleteMe).toBe(1);

    // It stays put and shows the signed-out state rather than navigating away.
    await expect(main(page).getByText("Sign in to see")).toBeVisible();

    const keys = await storageKeys(page);
    expect(keys.session).not.toContain(REFRESH_KEY);
  });
});

test.describe("what the browser keeps", () => {
  test("only the theme, the guest token and the refresh token", async ({ page }) => {
    await backendUp(page);
    await guestSession(page);
    await googleStub(page);
    await authEndpoints(page, { reportCount: 1 });
    await reportIs(page, doneReport());
    await reportList(page, { all: [listItem()], DONE: [listItem()], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    // A full guest-to-signed-in run, which is when the most is in storage at once.
    await page.goto(`/reports/${REPORT_ID}`);
    await expect(page.getByText(/deleted automatically in/)).toBeVisible();

    await page.goto("/account");
    await main(page).getByRole("button", { name: "Sign in with Google" }).click();
    await expect(main(page).getByText(`Signed in as ${USER_NAME}.`)).toBeVisible();

    const keys = await storageKeys(page);

    // `ms-theme` is written only once somebody picks a theme (7a F5), so the rule is that
    // nothing *else* is ever in localStorage — not that the theme is always there.
    expect(keys.local.filter((key) => key !== "ms-theme")).toEqual([]);

    // ms.guest is removed at sign-in; the tab id only exists because a token now does.
    expect(keys.session.sort()).toEqual([REFRESH_KEY, TAB_KEY].sort());
  });

  test("a guest who never signs in is given nothing to carry", async ({ page }) => {
    await backendUp(page);
    await googleStub(page);
    await authEndpoints(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });

    await page.goto("/reports");
    await expect(page.getByText("No reports yet.")).toBeVisible();

    const keys = await storageKeys(page);
    // No session was created, and no tab id written: there is nothing to protect.
    expect(keys.session).toEqual([]);
  });
});

test.describe("the third party", () => {
  test("is Google, and only on the pages that offer sign-in", async ({ page }) => {
    const hosts = watchRequests(page);
    const problems = watchForProblems(page);

    await backendUp(page);
    await googleStub(page);
    await authEndpoints(page);
    await reportList(page, { all: [], DONE: [], PENDING: [], PROCESSING: [], FAILED: [] });
    await trendEndpoints(page, { markers: [] });

    await page.goto("/account");
    await page.waitForTimeout(800);

    const external = [...hosts].filter((host) => !host.startsWith("localhost"));
    expect(external).toEqual(["accounts.google.com"]);

    // And the policy that allows it raises nothing.
    expect(problems).toEqual([]);
  });
});
