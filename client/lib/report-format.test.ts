import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Biomarker, Flag } from "./api-types";
import {
  flagForHighlight,
  formatCollectedOn,
  formatDeletedIn,
  formatExpiryCountdown,
  formatUploadedAt,
  sourceTypeLabel,
} from "./report-format";

/** A biomarker with only the fields these helpers look at. */
function marker(testName: string, flag: Flag): Biomarker {
  return {
    id: `id-${testName}`,
    testName,
    rawValue: "1",
    numericValue: 1,
    unit: null,
    referenceRangeText: null,
    refLow: null,
    refHigh: null,
    flag,
    biomarkerSlug: null,
  };
}

/**
 * Runs a case in a given zone.
 *
 * Node re-reads `process.env.TZ` when a `Date` is constructed, so this is enough to prove a
 * formatter does or does not depend on where the reader is sitting.
 */
function inZone(tz: string, run: () => void) {
  const original = process.env.TZ;
  process.env.TZ = tz;
  try {
    run();
  } finally {
    process.env.TZ = original;
  }
}

/** Far either side of UTC, so an off-by-one day shows up in one of them. */
const ZONES = ["UTC", "Pacific/Kiritimati", "Pacific/Niue", "Asia/Karachi", "America/Los_Angeles"];

describe("the collection date", () => {
  it("reads the date printed on the report", () => {
    expect(formatCollectedOn("2026-09-28")).toBe("28 Sep 2026");
  });

  it("never shifts a day, wherever the reader is", () => {
    // The contract calls `collectedOn` a plain date (YYYY-MM-DD), not an instant. Parsing it
    // as one makes it UTC midnight, which is the previous day for every reader west of
    // Greenwich — so a report collected on the 1st would read as the 31st.
    for (const tz of ZONES) {
      inZone(tz, () => {
        expect(formatCollectedOn("2026-01-01")).toBe("1 Jan 2026");
        expect(formatCollectedOn("2026-12-31")).toBe("31 Dec 2026");
      });
    }
  });

  it("says so plainly when the report had no date on it", () => {
    expect(formatCollectedOn(null)).toBe("Collection date not found");
  });

  it("does not invent a date from something it cannot read", () => {
    expect(formatCollectedOn("not-a-date")).toBe("Collection date not found");
    expect(formatCollectedOn("2026-13-01")).toBe("Collection date not found");
    expect(formatCollectedOn("2026-02-30")).toBe("Collection date not found");
  });
});

describe("the upload time", () => {
  it("is shown in the reader's own time zone", () => {
    // 09:31 UTC is 14:31 in Karachi. Unlike the collection date, this one is a real instant
    // and should move.
    inZone("UTC", () => {
      expect(formatUploadedAt("2026-10-02T09:31:00Z")).toBe("2 Oct 2026, 09:31");
    });
    inZone("Asia/Karachi", () => {
      expect(formatUploadedAt("2026-10-02T09:31:00Z")).toBe("2 Oct 2026, 14:31");
    });
  });

  it("pads the hour, so the column lines up", () => {
    inZone("UTC", () => {
      expect(formatUploadedAt("2026-10-02T04:05:00Z")).toBe("2 Oct 2026, 04:05");
    });
  });
});

describe("how long until it is deleted", () => {
  const now = new Date("2026-10-02T09:31:00Z");

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function inHours(h: number) {
    return new Date(now.getTime() + h * 3_600_000).toISOString();
  }

  function inMinutes(m: number) {
    return new Date(now.getTime() + m * 60_000).toISOString();
  }

  it("counts minutes in the last hour", () => {
    expect(formatDeletedIn(inMinutes(45))).toBe("45 min");
    expect(formatDeletedIn(inMinutes(1))).toBe("1 min");
  });

  it("counts hours from one hour to two days", () => {
    expect(formatDeletedIn(inMinutes(60))).toBe("1 h");
    expect(formatDeletedIn(inHours(23))).toBe("23 h");
    expect(formatDeletedIn(inHours(47))).toBe("47 h");
  });

  it("counts days past two", () => {
    expect(formatDeletedIn(inHours(48))).toBe("2 days");
    expect(formatDeletedIn(inHours(24 * 30))).toBe("30 days");
  });

  it("rounds down, so it never promises time it does not have", () => {
    expect(formatDeletedIn(inMinutes(119))).toBe("1 h");
    expect(formatDeletedIn(inHours(71))).toBe("2 days");
  });

  it("says it is gone rather than counting backwards", () => {
    expect(formatDeletedIn(inMinutes(-5))).toBe("any moment now");
    expect(formatDeletedIn(inMinutes(0))).toBe("any moment now");
  });

  it("gives the guest note hours and minutes together", () => {
    expect(formatExpiryCountdown(inMinutes(23 * 60 + 12))).toBe("23 h 12 min");
    expect(formatExpiryCountdown(inMinutes(59))).toBe("59 min");
    expect(formatExpiryCountdown(inMinutes(60))).toBe("1 h 0 min");
    expect(formatExpiryCountdown(inMinutes(-1))).toBe("any moment now");
  });
});

describe("the source type", () => {
  it("names each one the way the mockup does", () => {
    expect(sourceTypeLabel("PDF")).toBe("PDF");
    expect(sourceTypeLabel("TEXT")).toBe("Pasted text");
    expect(sourceTypeLabel("SAMPLE")).toBe("Sample");
    expect(sourceTypeLabel("IMAGE")).toBe("Photo");
  });

  it("falls back for a source type the contract adds later", () => {
    expect(sourceTypeLabel("HOLOGRAM" as never)).toBe("Report");
  });
});

describe("the flag on a summary highlight", () => {
  // B8: highlights are plain strings in the contract, so the chip is derived from whichever
  // flagged marker the sentence names.
  const biomarkers = [
    marker("Total Cholesterol", "HIGH"),
    marker("LDL Cholesterol", "HIGH"),
    marker("HDL Cholesterol", "NORMAL"),
    marker("Ferritin", "LOW"),
  ];

  it("takes the flag of the marker the sentence names", () => {
    expect(
      flagForHighlight("LDL Cholesterol is 162 mg/dL, above the reference range of <100.", biomarkers),
    ).toBe("HIGH");
    expect(flagForHighlight("Ferritin is below the reference range on this report.", biomarkers)).toBe(
      "LOW",
    );
  });

  it("prefers the longest matching name", () => {
    // "Total Cholesterol" contains "Cholesterol", and a highlight about non-HDL contains
    // "HDL Cholesterol" as well. The specific name is the one the sentence is about.
    const withNonHdl = [...biomarkers, marker("Non-HDL Cholesterol", "HIGH")];
    expect(flagForHighlight("Non-HDL Cholesterol is above the range.", withNonHdl)).toBe("HIGH");

    const shadowed = [marker("Cholesterol", "LOW"), marker("Total Cholesterol", "HIGH")];
    expect(flagForHighlight("Total Cholesterol is 238 mg/dL.", shadowed)).toBe("HIGH");
  });

  it("ignores the case the report happened to use", () => {
    expect(flagForHighlight("Your ldl cholesterol is high.", biomarkers)).toBe("HIGH");
  });

  it("gives no chip when the sentence names no flagged marker", () => {
    // HDL is NORMAL here, and a highlight naming it carries no flag worth showing.
    expect(flagForHighlight("HDL Cholesterol is within range.", biomarkers)).toBeNull();
    expect(flagForHighlight("Most of your results are in the normal range.", biomarkers)).toBeNull();
    expect(flagForHighlight("Anything at all.", [])).toBeNull();
  });

  it("does not match a name hidden inside a longer word", () => {
    const odd = [marker("Iron", "LOW")];
    expect(flagForHighlight("Your environment is fine.", odd)).toBeNull();
    expect(flagForHighlight("Iron is below the range.", odd)).toBe("LOW");
  });
});
