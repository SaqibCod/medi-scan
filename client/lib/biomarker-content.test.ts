import { describe, expect, it } from "vitest";

import {
  REQUIRED_HEADINGS,
  getAllBiomarkerPages,
  getBiomarkerPage,
  headingId,
} from "./biomarker-content";

/**
 * The curated pages, checked as content rather than as code.
 *
 * These are the rules that cannot be enforced by a type: no numeric reference ranges, no
 * diagnosis, no treatment advice, and nothing marked reviewed that a person has not read.
 * A failure here is a content mistake, and the message has to name the file.
 */

const pages = getAllBiomarkerPages();

/** Everything below the frontmatter, which is where the rules apply. */
function body(slug: string): string {
  return getBiomarkerPage(slug)?.body ?? "";
}

describe("the set of pages", () => {
  it("has one page per slug in index.json and no others", () => {
    // `loadAll` throws on a mismatch in either direction, so reaching here is the assertion;
    // this pins the count so a silently dropped file is visible.
    expect(pages.length).toBe(21);
  });

  it("gives every page the frontmatter the explainer needs", () => {
    for (const page of pages) {
      expect(page.title, page.slug).toBeTruthy();
      expect(page.panel, page.slug).toBeTruthy();
      expect(page.measures, page.slug).toBeTruthy();
      expect(page.usualUnits, page.slug).toBeTruthy();
      expect(page.usuallyPartOf, page.slug).toBeTruthy();
      expect(page.sources.length, page.slug).toBeGreaterThan(0);
    }
  });

  it("points every related slug at a page that exists", () => {
    const slugs = new Set(pages.map((page) => page.slug));

    for (const page of pages) {
      for (const related of page.related) {
        expect(slugs.has(related), `${page.slug} → ${related}`).toBe(true);
      }
    }
  });

  it("cites only https sources", () => {
    for (const page of pages) {
      for (const source of page.sources) {
        expect(source.url, `${page.slug}: ${source.title}`).toMatch(/^https:\/\//);
      }
    }
  });

  it("cites only the reputable public sources the design calls for", () => {
    // MedlinePlus (U.S. National Library of Medicine) and the NHS. A new host here is a
    // decision to make deliberately, not one to slip in with a page.
    const allowed = ["medlineplus.gov", "www.nhs.uk"];

    for (const page of pages) {
      for (const source of page.sources) {
        const host = new URL(source.url).host;
        expect(allowed, `${page.slug} cites ${host}`).toContain(host);
      }
    }
  });
});

describe("the body of every page", () => {
  it("carries the five headings, in order", () => {
    for (const page of pages) {
      expect(page.headings.length, page.slug).toBe(REQUIRED_HEADINGS.length);

      page.headings.forEach((heading, i) => {
        expect(heading.title, `${page.slug} heading ${i + 1}`).toMatch(REQUIRED_HEADINGS[i]);
      });
    }
  });

  it("gives each heading a usable id for the page index", () => {
    for (const page of pages) {
      for (const heading of page.headings) {
        expect(heading.id, `${page.slug}: ${heading.title}`).toMatch(/^[a-z0-9-]+$/);
      }
    }
  });

  /**
   * The rule that matters most.
   *
   * Reference ranges differ between labs, so a number printed here could contradict the one
   * on the reader's own report — and theirs is the one that applies. The sources these pages
   * are drawn from do print ranges, which is exactly why this test exists.
   */
  it("never states a numeric reference range or cut-off", () => {
    const UNITS = String.raw`mg\/dL|mmol\/L|g\/dL|g\/L|mIU\/L|µIU\/mL|uIU\/mL|ng\/dL|pmol\/L|IU\/mL|fL|pg\/cell`;

    const PATTERNS: Array<{ re: RegExp; what: string }> = [
      { re: new RegExp(String.raw`\d+(\.\d+)?\s*(${UNITS})`, "i"), what: "a number with a lab unit" },
      { re: /[<>]\s*\d/, what: "a less-than or greater-than cut-off" },
      { re: /\b\d+(\.\d+)?\s*[–-]\s*\d+(\.\d+)?\s*(%|\w+\/\w+)/, what: "a numeric range" },
      { re: /\b(above|below|over|under|less than|more than)\s+\d+(\.\d+)?\s*(%|\w+\/\w+)/i, what: "a stated threshold" },
    ];

    const offenders: string[] = [];

    for (const page of pages) {
      for (const [i, line] of body(page.slug).split("\n").entries()) {
        for (const { re, what } of PATTERNS) {
          if (re.test(line)) {
            offenders.push(`${page.slug}.md:${i + 1} (${what})  ${line.trim()}`);
          }
        }
      }
    }

    expect(
      offenders,
      `A page states a numeric range. Point at the range on the reader's own report instead:\n${offenders.join("\n")}`,
    ).toEqual([]);
  });

  it("never tells the reader what they have, or what to take", () => {
    const BANNED: Array<{ re: RegExp; what: string }> = [
      // "you have no say", "you have any questions" and the like are fine; "you have X" is
      // the diagnosis this is looking for.
      {
        re: /\byou have\s+(?!no\b|any\b|your\b|a report\b|results?\b|questions?\b)/i,
        what: "telling the reader they have a condition",
      },
      { re: /\byou (should|must) (take|start|stop|increase|decrease)\b/i, what: "treatment advice" },
      { re: /\b(dose|dosage|mg of|prescrib)/i, what: "dosing" },
      { re: /\byou (are|have been) diagnos/i, what: "diagnosis" },
      { re: /\b(this means you|you probably have|you definitely)\b/i, what: "diagnosis" },
    ];

    const offenders: string[] = [];

    for (const page of pages) {
      for (const [i, line] of body(page.slug).split("\n").entries()) {
        for (const { re, what } of BANNED) {
          if (re.test(line)) offenders.push(`${page.slug}.md:${i + 1} (${what})  ${line.trim()}`);
        }
      }
    }

    expect(offenders, `Medical advice found:\n${offenders.join("\n")}`).toEqual([]);
  });

  it("points at a clinician on every page", () => {
    for (const page of pages) {
      expect(body(page.slug), page.slug).toMatch(/doctor|clinician/i);
    }
  });

  it("contains no raw HTML", () => {
    // The renderer drops HTML anyway, but content that relies on it would render as a blank.
    for (const page of pages) {
      expect(body(page.slug), page.slug).not.toMatch(/<[a-zA-Z/!][^>]*>/);
    }
  });

  it("defers to the reader's own lab on where the line falls", () => {
    // Every page has to make this point somewhere, since it is the reason no numbers appear.
    // A qualitative test has no range to defer to, so it says the same thing about the
    // lab's cut-off instead.
    for (const page of pages) {
      expect(body(page.slug), page.slug).toMatch(
        /range printed on your report|range on your report|printed on your report|comparison point|cut-off/i,
      );
    }
  });
});

describe("review status", () => {
  it("leaves every page unreviewed, because nothing in the app can mark one reviewed", () => {
    // LLD 7.4: a person reviews these before Phase 8, and the deploy checklist blocks on it.
    for (const page of pages) {
      expect(page.reviewedOn, page.slug).toBeNull();
      expect(page.reviewer, page.slug).toBeNull();
    }
  });
});

describe("heading ids", () => {
  it("are slugified from the heading text", () => {
    expect(headingId("What it measures")).toBe("what-it-measures");
    expect(headingId("What high LDL means")).toBe("what-high-ldl-means");
    expect(headingId("Questions to ask your doctor")).toBe("questions-to-ask-your-doctor");
  });
});

describe("looking a page up", () => {
  it("finds one by slug", () => {
    const page = getBiomarkerPage("ldl-cholesterol");

    expect(page?.title).toBe("LDL Cholesterol");
    expect(page?.panel).toBe("Lipid panel");
    expect(page?.related).toContain("hdl-cholesterol");
  });

  it("returns null for a slug that does not exist", () => {
    expect(getBiomarkerPage("unobtainium")).toBeNull();
    expect(getBiomarkerPage("../index")).toBeNull();
  });
});
