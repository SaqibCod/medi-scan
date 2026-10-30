import { acceptsPhotos } from "./copy";
import type { ReportRequest } from "./reports";
import type { Sample } from "./api-types";

/**
 * What the upload panel is holding, and whether it can be sent.
 *
 * These checks exist for speed, not for safety: the server checks all of it again, and the
 * file-signature check is only ever done there. The point is that someone who picked a 14 MB
 * scan finds out now rather than after watching it upload.
 */

/** Contract 4.1. Binary megabytes, because that is what a file manager shows. */
export const MAX_FILE_BYTES = 10 * 1024 * 1024;
export const MIN_TEXT_CHARS = 20;
export const MAX_TEXT_CHARS = 50_000;

export type Source =
  | { kind: "none" }
  | { kind: "file"; file: File }
  | { kind: "text"; text: string }
  | { kind: "sample"; sample: Sample };

export const NO_SOURCE: Source = { kind: "none" };

/** Why a source cannot be sent. The panel maps each to the copy in LLD 5.4. */
export type UploadProblem =
  | { kind: "file-too-large"; size: string }
  | { kind: "photo-not-yet" }
  | { kind: "wrong-type" }
  | { kind: "text-too-short" }
  | { kind: "text-too-long" };

const PHOTO_TYPES = ["image/png", "image/jpeg", "image/jpg"];
const PHOTO_EXTENSIONS = [".png", ".jpg", ".jpeg"];

function hasExtension(name: string, extensions: string[]): boolean {
  const lower = name.toLowerCase();
  return extensions.some((extension) => lower.endsWith(extension));
}

/**
 * Whether a file is a photo.
 *
 * Both the MIME type and the name, because neither is reliable on its own: a file dragged
 * from some file managers arrives with an empty `type`, and a name can say anything. Either
 * one saying "photo" is enough to ask the question.
 */
function isPhoto(file: File): boolean {
  return PHOTO_TYPES.includes(file.type.toLowerCase()) || hasExtension(file.name, PHOTO_EXTENSIONS);
}

function isPdf(file: File): boolean {
  return file.type.toLowerCase() === "application/pdf" || hasExtension(file.name, [".pdf"]);
}

/** "14.2 MB", the way the mockup's notice says it. */
export function formatFileSize(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** "1,240", for the paste box counter. */
export function formatCharCount(count: number): string {
  return count.toLocaleString("en-US");
}

/**
 * The length that counts toward the contract's 20-to-50,000 limit.
 *
 * Whitespace around the whole paste is an artefact of selecting it, not part of the report,
 * and a box holding nothing but newlines is empty rather than short. Whitespace *inside* is
 * left exactly as it is: the line and column structure is most of what makes a pasted lab
 * report readable to the extractor.
 */
export function meaningfulText(text: string): string {
  return text.trim();
}

/**
 * Checks a file before it is sent.
 *
 * Type before size, deliberately. Telling someone their 14 MB PNG is too large would send
 * them off to shrink a file that this build would refuse at any size, and every message here
 * has to be one they can act on.
 */
export function checkFile(file: File): UploadProblem | null {
  // Nothing came out of the export, or the drive hiccuped. Either way there is no document.
  if (file.size === 0) return { kind: "wrong-type" };

  if (isPhoto(file) && !acceptsPhotos) return { kind: "photo-not-yet" };
  if (!isPdf(file) && !(isPhoto(file) && acceptsPhotos)) return { kind: "wrong-type" };

  if (file.size > MAX_FILE_BYTES) return { kind: "file-too-large", size: formatFileSize(file.size) };

  return null;
}

export function checkText(text: string): UploadProblem | null {
  const length = meaningfulText(text).length;

  if (length < MIN_TEXT_CHARS) return { kind: "text-too-short" };
  if (length > MAX_TEXT_CHARS) return { kind: "text-too-long" };

  return null;
}

/** What is wrong with the current source, if anything. A sample is always sendable. */
export function checkSource(source: Source): UploadProblem | null {
  if (source.kind === "file") return checkFile(source.file);
  if (source.kind === "text") return checkText(source.text);
  return null;
}

/** Whether "Analyze report" may be pressed. Consent is the caller's half of the question. */
export function canSend(source: Source, consented: boolean): boolean {
  return source.kind !== "none" && consented && checkSource(source) === null;
}

/**
 * What is still missing, as a sentence under the button.
 *
 * A disabled button with no explanation is a dead end: the reason it cannot be pressed is
 * exactly the thing the person needs to know.
 */
export function missingStep(source: Source, consented: boolean): string | null {
  if (source.kind === "none") return "Choose a file, paste your report, or pick a sample.";
  if (checkSource(source) !== null) return null; // the notice already says it
  if (!consented) return "Tick the box above to continue.";
  return null;
}

/** Narrows the panel's state to what `POST /api/reports` actually needs. */
export function toReportRequest(source: Source): ReportRequest | null {
  switch (source.kind) {
    case "file":
      return { kind: "file", file: source.file };
    case "text":
      // Sent trimmed, and checked trimmed, so the two can never disagree about the limit.
      return { kind: "text", text: meaningfulText(source.text) };
    case "sample":
      return { kind: "sample", sampleId: source.sample.id };
    case "none":
      return null;
  }
}
