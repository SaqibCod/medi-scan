import { FEATURE_OCR } from "./env";

/**
 * Every user-facing string whose truth depends on which phase has shipped (B4).
 *
 * The rule this file exists to keep: the app never offers something the running build cannot
 * do. OCR lands in Phase 4, so until `NEXT_PUBLIC_FEATURE_OCR` is on, nothing anywhere
 * mentions photos except the notice that says they are not ready. Collecting the pairs here
 * rather than scattering `FEATURE_OCR &&` through the components means Phase 4 flips one flag
 * and a test can read every rendered string back (LLD 10, unit 9).
 *
 * Wording is from the mockups. Where a string had to change because the mockup assumes OCR,
 * the OCR-on version is the mockup's own text, so Phase 4 restores it exactly.
 */

/** Picks the wording for the build that is running. */
function ocr<T>(off: T, on: T): T {
  return FEATURE_OCR ? on : off;
}

export const copy = {
  /** What the drop zone says it will take. Also the `accept` attribute's plain-words twin. */
  dropzoneHint: ocr("PDF — up to 10 MB · photos coming soon", "PDF · PNG · JPEG — up to 10 MB"),

  dropzoneTitle: "Drop your report here, or browse",

  /** The phone drop zone's second tile. Present either way; only its behaviour changes (B5). */
  takePhotoLabel: "Take a photo",

  /** The 10 MB fact on the landing page. */
  maxUploadLabel: ocr("Max upload, as a PDF", "Max upload, as PDF or a photo"),

  /** The six landing-page steps. "Read" repeats on the processing page, so it lives here. */
  steps: {
    upload: ocr(
      "Drop a text PDF, paste the text, or pick a sample.",
      "Drop a PDF or a photo of your report, paste the text, or pick a sample.",
    ),
    read: ocr(
      "Pull the text out of your file.",
      "Pull the text out of your file, with OCR for photos and scans.",
    ),
    mask: "Hide names and personal details before anything reaches the AI.",
    extract: "Find each test, value, unit and reference range.",
    flag: "Compare every value with the range printed on your report.",
    explain: "Write a short summary in plain language.",
  },

  /** The inline notice when someone picks a photo while OCR is off (B5, LLD 5.4). */
  photoNotYet: {
    title: "Photos are coming soon.",
    body: "For now Medi-Scan reads PDFs that contain text, pasted text, and samples. Paste the text from your report instead.",
    action: "Paste text instead",
  },

  /** `415 UNSUPPORTED_FILE_TYPE`. The list has to match what the server will actually take. */
  unsupportedFileTypeBody: ocr(
    "Upload a PDF, or paste the text of your report.",
    "Upload a PDF, PNG or JPEG, or paste the text of your report.",
  ),

  /** The `UNREADABLE` failed view (LLD 6.4). The OCR-on text is the mockup's. */
  unreadableBody: ocr(
    "No usable text came out of this file. Scanned pages and photos need OCR, which is coming soon. A PDF from your lab or pasted text works best.",
    "No usable text came out, even after OCR. Try a clearer photo: flat, in good light, with the whole page in frame. A PDF from your lab works best.",
  ),
} as const;

/** Whether the browser should send a photo at all, rather than explain that it cannot. */
export const acceptsPhotos = FEATURE_OCR;

/**
 * What the file input offers.
 *
 * Narrowed with the flag so the picker does not even list photos it would then refuse. The
 * client checks in `upload-source.ts` still run: an `accept` attribute is a filter on a
 * dialog, not a guarantee about what arrives.
 */
export const FILE_ACCEPT = FEATURE_OCR ? "application/pdf,image/png,image/jpeg" : "application/pdf";

/** Word for word on every page that shows results, plus the sentence the mockup adds there. */
export const RESULTS_DISCLAIMER =
  "Medi-Scan explains what your report says and is not medical advice. Talk to your doctor about what these results mean for you.";

/** Under the results table. The one thing a reader most needs to know about a range. */
export const RANGE_NOTE =
  "Ranges come from your report. Labs use different ranges, so always compare with the one printed on yours.";

export interface FailureCopy {
  eyebrow: string;
  headline: string;
  body: string;
}

/**
 * The failed-report views, keyed on `error.code` (LLD 6.4).
 *
 * Switching on the code and never on the server's `error.message` is the root rule: the
 * message is written for a log and may be reworded at any time.
 */
const FAILURES: Record<string, FailureCopy> = {
  UNREADABLE: {
    eyebrow: "Couldn't read this file",
    headline: "We couldn't read text from this file.",
    body: copy.unreadableBody,
  },
  DOCUMENT_TOO_LONG: {
    eyebrow: "Document too long",
    headline: "This document is too long to read.",
    body: "Try uploading only the pages with your results, or paste just that part of the text.",
  },
  NO_RESULTS_FOUND: {
    eyebrow: "No results found",
    headline: "We didn't find lab results in this document.",
    body: "Medi-Scan reads lab reports: test names, values and reference ranges. A letter or a discharge summary has nothing for it to work with.",
  },
  EXTRACTION_FAILED: {
    eyebrow: "Something went wrong",
    headline: "Something went wrong reading the results.",
    body: "This one is on us, not on your report. Try again, or try a different export of the same report.",
  },
  CAPACITY: {
    eyebrow: "Daily limit reached",
    headline: "The demo has hit today's limit.",
    body: "Medi-Scan runs on a free AI quota that resets at midnight UTC. Reports you already have still open.",
  },
  LLM_UNAVAILABLE: {
    eyebrow: "AI service unavailable",
    headline: "The AI service is unavailable right now.",
    body: "This is the provider's side, not your report. Try again in a few minutes.",
  },
  INTERRUPTED: {
    eyebrow: "Processing interrupted",
    headline: "Processing was interrupted.",
    body: "The server restarted while your report was being read. Nothing was saved, so upload it again.",
  },
};

const UNKNOWN_FAILURE: FailureCopy = {
  eyebrow: "Something went wrong",
  headline: "We couldn't finish reading this report.",
  body: "Try uploading it again, or try a sample report to see how it should go.",
};

/** A failure code the contract adds later still gets a view (contract 9.2). */
export function failureCopy(code: string | null | undefined): FailureCopy {
  return (code && FAILURES[code]) || UNKNOWN_FAILURE;
}
