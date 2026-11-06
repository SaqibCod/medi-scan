import type { Biomarker, Flag, SourceType } from "./api-types";

/**
 * Turning report fields into the strings the mockups show.
 *
 * Separate from the components because most of it is date arithmetic with a trap in it, and
 * arithmetic with a trap in it wants tests rather than a render.
 */

const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
] as const;

const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/**
 * The collection date, formatted without going anywhere near a time zone.
 *
 * `collectedOn` is a plain date in the contract — the day printed on the report — not an
 * instant. `new Date("2026-01-01")` reads it as UTC midnight, and every reader west of
 * Greenwich would then be shown "31 Dec 2025". So this never builds a `Date` at all: the
 * string already holds the three numbers, and a calendar date has no zone to convert from.
 */
export function formatCollectedOn(collectedOn: string | null): string {
  if (!collectedOn) return "Collection date not found";

  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(collectedOn);
  if (!match) return "Collection date not found";

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);

  if (month < 1 || month > 12) return "Collection date not found";

  const lastDay = month === 2 && isLeapYear(year) ? 29 : DAYS_IN_MONTH[month - 1];
  if (day < 1 || day > lastDay) return "Collection date not found";

  return `${day} ${MONTHS[month - 1]} ${year}`;
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/**
 * When the report was uploaded, in the reader's own time zone.
 *
 * The opposite case to the one above, and deliberately so: `createdAt` is a real instant, and
 * someone who uploaded a report at nine in the morning should read "09:31" wherever they are.
 */
export function formatUploadedAt(createdAt: string): string {
  const at = new Date(createdAt);
  if (Number.isNaN(at.getTime())) return "";

  const date = `${at.getDate()} ${MONTHS[at.getMonth()]} ${at.getFullYear()}`;
  return `${date}, ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

/** Every countdown rounds down, so the page never promises time the report does not have. */
function remainingMs(expiresAt: string, now: Date = new Date()): number | null {
  const expiry = Date.parse(expiresAt);
  if (Number.isNaN(expiry)) return null;
  return expiry - now.getTime();
}

/**
 * "Deleted in 23 h", for the header tag.
 *
 * One unit, chosen by how much is left: minutes while it is imminent, hours for the rest of a
 * guest's day, days for a signed-in report's month. A single number is easier to glance at
 * than "1 d 23 h 12 min", and this one is a reassurance, not a deadline.
 */
export function formatDeletedIn(expiresAt: string, now?: Date): string {
  const left = remainingMs(expiresAt, now);
  if (left === null) return "";
  if (left <= 0) return "any moment now";

  if (left < HOUR_MS) return `${Math.floor(left / MINUTE_MS)} min`;
  if (left < 2 * DAY_MS) return `${Math.floor(left / HOUR_MS)} h`;
  return `${Math.floor(left / DAY_MS)} days`;
}

/**
 * "23 h 12 min", for the guest note.
 *
 * Finer than the tag because this one is the warning: a guest's report really is going away
 * today, and the minutes are the part that makes that feel true.
 */
export function formatExpiryCountdown(expiresAt: string, now?: Date): string {
  const left = remainingMs(expiresAt, now);
  if (left === null) return "";
  if (left <= 0) return "any moment now";

  const hours = Math.floor(left / HOUR_MS);
  const minutes = Math.floor((left % HOUR_MS) / MINUTE_MS);

  return hours === 0 ? `${minutes} min` : `${hours} h ${minutes} min`;
}

const SOURCE_LABELS: Record<SourceType, string> = {
  PDF: "PDF",
  IMAGE: "Photo",
  TEXT: "Pasted text",
  SAMPLE: "Sample",
};

/** A source type the contract adds later is still a report (contract 9.2). */
export function sourceTypeLabel(sourceType: SourceType): string {
  return SOURCE_LABELS[sourceType] ?? "Report";
}

/** A value and its unit, kept apart so the unit can be set smaller and in `ink-3`. */
export function formatValue(biomarker: Pick<Biomarker, "rawValue" | "unit">): {
  value: string;
  unit: string | null;
} {
  const value = biomarker.rawValue.trim();
  return { value: value || "—", unit: biomarker.unit?.trim() || null };
}

/** The reference range as printed, or a dash when the report did not give one. */
export function formatRange(biomarker: Pick<Biomarker, "referenceRangeText">): string {
  return biomarker.referenceRangeText?.trim() || "—";
}

function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * The flag chip to show beside a summary highlight (B8).
 *
 * Highlights are plain strings in the contract, so the only honest way to colour one is to
 * find the marker it is talking about. Only flagged markers count, because a chip saying
 * NORMAL next to a sentence singling a value out would contradict it.
 *
 * The longest matching name wins: a sentence about "Non-HDL Cholesterol" also contains "HDL
 * Cholesterol", and the more specific name is the one it is about. Matching is bounded on
 * both sides so "Iron" does not match "environment".
 */
export function flagForHighlight(highlight: string, biomarkers: Biomarker[]): Flag | null {
  const flagged = biomarkers.filter((b) => b.flag === "HIGH" || b.flag === "LOW");

  let best: Biomarker | null = null;

  for (const biomarker of flagged) {
    const name = biomarker.testName.trim();
    if (!name) continue;
    if (best && name.length <= best.testName.trim().length) continue;

    // `\b` would not fire next to a hyphen, which "Non-HDL" and "LDL-C" both have. Looking
    // for "not a letter or digit" on each side is the same idea without that hole.
    const pattern = new RegExp(`(^|[^\\p{L}\\p{N}])${escapeForRegExp(name)}([^\\p{L}\\p{N}]|$)`, "iu");
    if (pattern.test(highlight)) best = biomarker;
  }

  return best?.flag ?? null;
}
