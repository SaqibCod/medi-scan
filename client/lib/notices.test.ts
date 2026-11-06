import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { formatCountdown, hasExpired, nextMidnightUtc, resolveResetsAt } from "./notices";

/** A fixed clock, so "6 h 12 min" means the same thing on every machine. */
function at(iso: string) {
  vi.setSystemTime(new Date(iso));
  return new Date(iso);
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("when the cap lifts", () => {
  it("is the next midnight UTC", () => {
    const now = at("2026-10-26T17:48:00Z");
    expect(nextMidnightUtc(now).toISOString()).toBe("2026-10-27T00:00:00.000Z");
  });

  it("rolls to the next day from just before midnight", () => {
    const now = at("2026-10-26T23:59:59Z");
    expect(nextMidnightUtc(now).toISOString()).toBe("2026-10-27T00:00:00.000Z");
  });

  it("rolls to the next day from exactly midnight", () => {
    const now = at("2026-10-26T00:00:00Z");
    // The cap has just reset, so the next one is a full day away.
    expect(nextMidnightUtc(now).toISOString()).toBe("2026-10-27T00:00:00.000Z");
  });

  it("prefers Retry-After, because the server knows its own clock", () => {
    const now = at("2026-10-26T17:48:00Z");

    // The visitor's machine may be minutes out; the server's answer is authoritative.
    expect(resolveResetsAt(3600, now).toISOString()).toBe("2026-10-26T18:48:00.000Z");
  });

  it("falls back to midnight UTC when the header is missing", () => {
    const now = at("2026-10-26T17:48:00Z");
    expect(resolveResetsAt(null, now).toISOString()).toBe("2026-10-27T00:00:00.000Z");
  });

  it("ignores a nonsense Retry-After", () => {
    const now = at("2026-10-26T17:48:00Z");
    expect(resolveResetsAt(0, now).toISOString()).toBe("2026-10-27T00:00:00.000Z");
  });
});

describe("the countdown", () => {
  it("reads as the mockup writes it", () => {
    const now = at("2026-10-26T17:48:00Z");
    const resetsAt = new Date("2026-10-27T00:00:00Z");

    expect(formatCountdown(resetsAt, now)).toBe("6 h 12 min");
  });

  it("drops the hours when there are none", () => {
    const now = at("2026-10-26T23:18:00Z");
    expect(formatCountdown(new Date("2026-10-27T00:00:00Z"), now)).toBe("42 min");
  });

  it("never counts below zero", () => {
    const now = at("2026-10-27T01:00:00Z");
    expect(formatCountdown(new Date("2026-10-27T00:00:00Z"), now)).toBe("0 min");
  });
});

describe("clearing itself", () => {
  it("expires exactly at the reset", () => {
    const resetsAt = new Date("2026-10-27T00:00:00Z");

    at("2026-10-26T23:59:59Z");
    expect(hasExpired(resetsAt)).toBe(false);

    at("2026-10-27T00:00:00Z");
    expect(hasExpired(resetsAt)).toBe(true);
  });
});
