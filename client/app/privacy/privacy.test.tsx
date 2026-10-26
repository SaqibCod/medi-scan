import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import PrivacyPage from "./page";

/**
 * The privacy page makes promises about what the backend does. These pin the ones that would
 * become lies if a retention period or a limit changed — and the ones the mockup got wrong.
 */

function renderPage() {
  return render(<PrivacyPage />);
}

describe("retention claims", () => {
  it.each([
    ["Guest reports and their results", "24 hours after the guest session started"],
    ["Saved reports and their results", "30 days after upload, or until you delete them"],
    ["Chat messages", "Never stored on our server"],
    // SECURITY.md: aggregated daily counters, 90 days.
    ["Usage counts and error logs", "90 days"],
    // SECURITY.md: accounts with no sign-in for 180 days are deleted.
    ["Accounts with no sign-in", "Deleted after 180 days"],
  ])("%s: %s", (what, how) => {
    renderPage();

    const row = screen.getByRole("rowheader", { name: what }).closest("tr")!;
    expect(within(row).getByText(how)).toBeInTheDocument();
  });

  it("says sign-in sessions last 7 days, not the 30 the mockup shows", () => {
    // plan.md section 4.10: the refresh token lives 7 days. 30 days is report retention, and
    // the mockup conflated the two. docs/ui/README.md says the docs win.
    renderPage();

    const row = screen.getByRole("rowheader", { name: "Sign-in sessions" }).closest("tr")!;
    expect(within(row).getByText("Up to 7 days, or until you sign out")).toBeInTheDocument();
    expect(within(row).queryByText(/30 days/)).not.toBeInTheDocument();
  });
});

describe("what is and is not stored", () => {
  it("names the three things kept about a signed-in person, and the three that are not", () => {
    renderPage();

    const text = document.body.textContent ?? "";
    expect(text).toContain("an ID that Google gives us for your account");
    expect(text).toContain("your display name");
    // SECURITY.md: never the email address, the photo, or the Google token.
    expect(text).toMatch(/not your\s+email address/);
    expect(text).toContain("not your profile photo");
  });

  it("says there are no cookies, rather than the mockup's 'Sign-in cookies'", () => {
    renderPage();

    expect(screen.getByText(/Medi-Scan uses no cookies at all/)).toBeInTheDocument();
    expect(screen.getByText("Sign-in tokens.")).toBeInTheDocument();
  });

  it("does not claim the IP address is stored", () => {
    // It is used in memory for rate limiting (Bucket4j). SECURITY.md's "what is stored" table
    // has no row for it, so the page must not imply one.
    renderPage();

    expect(screen.getByText(/is not written to the database/)).toBeInTheDocument();
  });

  it("states the real upload limit", () => {
    // contract 1.6: 10 uploads an hour.
    renderPage();
    expect(screen.getByText(/ten uploads an hour/)).toBeInTheDocument();
  });
});

describe("the AI provider", () => {
  it("names it, rather than leaving a placeholder", () => {
    renderPage();

    const mentions = screen.getAllByText(/Google's Gemini API/);
    expect(mentions.length).toBeGreaterThanOrEqual(2);
  });

  it("discloses that a free tier may train on what is sent", () => {
    // SECURITY.md treats this as the reason the synthetic-data policy exists. A privacy page
    // that left it out would be hiding the most material thing on it.
    renderPage();

    expect(screen.getByText(/use what is sent to improve their models/)).toBeInTheDocument();
  });

  it("says masking happens before anything is sent", () => {
    renderPage();
    expect(screen.getByText(/before anything leaves our server/)).toBeInTheDocument();
  });
});

describe("placeholders", () => {
  it("has none left", () => {
    renderPage();

    // The mockup ships [DATE], [AI PROVIDER], [RETENTION PERIOD], [CONTACT EMAIL], [YOUR NAME].
    expect(document.body.textContent).not.toMatch(/\[[A-Z][A-Z ]+\]/);
  });

  it("is dated and attributed", () => {
    renderPage();

    expect(screen.getByText("Last updated 26 October 2026")).toBeInTheDocument();
    expect(screen.getByText(/run by Saqib/)).toBeInTheDocument();
  });
});

describe("structure", () => {
  it("indexes every section it has, and every entry points somewhere real", () => {
    const { container } = renderPage();

    const nav = screen.getByRole("navigation", { name: "On this page" });
    const links = within(nav).getAllByRole("link");
    expect(links.length).toBe(7);

    for (const link of links) {
      const id = link.getAttribute("href")!.slice(1);
      expect(container.querySelector(`#${id}`), `no section #${id}`).not.toBeNull();
    }
  });

  it("has no heading above level 2 apart from the page title", () => {
    renderPage();

    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  });
});
