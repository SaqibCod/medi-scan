import { afterEach, describe, expect, it, vi } from "vitest";

import type { Sample } from "./api-types";

/**
 * The photo check depends on `FEATURE_OCR`, which `copy.ts` reads at import, so each case
 * loads the module fresh — the same shape as `env.test.ts` and `copy.test.ts`.
 */
async function load(ocrOn = false) {
  vi.resetModules();
  vi.stubEnv("NODE_ENV", "development");
  vi.stubEnv("NEXT_PUBLIC_FEATURE_OCR", ocrOn ? "true" : "");
  return import("./upload-source");
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

/** A file of an exact size, without allocating one that big. */
function fileOf(bytes: number, name: string, type: string): File {
  const file = new File(["x"], name, { type });
  Object.defineProperty(file, "size", { value: bytes });
  return file;
}

function pdf(bytes: number, name = "report.pdf"): File {
  return fileOf(bytes, name, "application/pdf");
}

const SAMPLE: Sample = {
  id: "lipid-panel",
  title: "Lipid panel",
  description: "Cholesterol and triglycerides, with high LDL.",
  markerCount: 5,
};

describe("file size", () => {
  it("accepts a file right up to the limit", async () => {
    const { checkFile, MAX_FILE_BYTES } = await load();

    expect(checkFile(pdf(MAX_FILE_BYTES))).toBeNull();
    expect(checkFile(pdf(MAX_FILE_BYTES - 1))).toBeNull();
  });

  it("refuses one byte over, and says how big it is", async () => {
    const { checkFile, MAX_FILE_BYTES } = await load();

    expect(checkFile(pdf(MAX_FILE_BYTES + 1))).toEqual({
      kind: "file-too-large",
      size: "10.0 MB",
    });
  });

  it("measures in binary megabytes, like a file manager", async () => {
    const { checkFile } = await load();

    // 14.2 MB is the number in the mockup's notice.
    const problem = checkFile(pdf(Math.round(14.2 * 1024 * 1024)));
    expect(problem).toEqual({ kind: "file-too-large", size: "14.2 MB" });
  });

  it("calls an empty file the wrong type rather than nothing at all", async () => {
    const { checkFile } = await load();

    // An export that produced no bytes. "Too large" would be absurd and "it worked" is false.
    expect(checkFile(pdf(0))).toEqual({ kind: "wrong-type" });
  });
});

describe("file type with OCR off", () => {
  it("takes a PDF", async () => {
    const { checkFile } = await load();
    expect(checkFile(pdf(1000))).toBeNull();
  });

  it("knows a photo by its MIME type", async () => {
    const { checkFile } = await load();

    expect(checkFile(fileOf(1000, "scan", "image/png"))).toEqual({ kind: "photo-not-yet" });
    expect(checkFile(fileOf(1000, "scan", "image/jpeg"))).toEqual({ kind: "photo-not-yet" });
  });

  it("knows a photo by its name when the type is missing", async () => {
    const { checkFile } = await load();

    // Dragged from a file manager that sets no type.
    expect(checkFile(fileOf(1000, "IMG_4021.JPG", ""))).toEqual({ kind: "photo-not-yet" });
    expect(checkFile(fileOf(1000, "page.jpeg", ""))).toEqual({ kind: "photo-not-yet" });
    expect(checkFile(fileOf(1000, "page.png", ""))).toEqual({ kind: "photo-not-yet" });
  });

  it("says photos before it says too large", async () => {
    const { checkFile, MAX_FILE_BYTES } = await load();

    // Shrinking it would not help: this build refuses a photo at any size, and "export fewer
    // pages" would be advice that cannot work.
    expect(checkFile(fileOf(MAX_FILE_BYTES * 2, "big.png", "image/png"))).toEqual({
      kind: "photo-not-yet",
    });
  });

  it("refuses anything that is neither", async () => {
    const { checkFile } = await load();

    expect(checkFile(fileOf(1000, "results.docx", ""))).toEqual({ kind: "wrong-type" });
    expect(checkFile(fileOf(1000, "results.csv", "text/csv"))).toEqual({ kind: "wrong-type" });
    expect(checkFile(fileOf(1000, "noextension", ""))).toEqual({ kind: "wrong-type" });
  });
});

describe("file type with OCR on", () => {
  it("takes photos, and still minds the size", async () => {
    const { checkFile, MAX_FILE_BYTES } = await load(true);

    expect(checkFile(fileOf(1000, "scan.png", "image/png"))).toBeNull();
    expect(checkFile(fileOf(1000, "IMG_1.JPG", ""))).toBeNull();
    expect(checkFile(fileOf(MAX_FILE_BYTES + 1, "big.png", "image/png"))).toMatchObject({
      kind: "file-too-large",
    });
  });

  it("still refuses what the API never took", async () => {
    const { checkFile } = await load(true);
    expect(checkFile(fileOf(1000, "results.docx", ""))).toEqual({ kind: "wrong-type" });
  });
});

describe("pasted text length", () => {
  it("refuses 19 characters and accepts 20", async () => {
    const { checkText } = await load();

    expect(checkText("x".repeat(19))).toEqual({ kind: "text-too-short" });
    expect(checkText("x".repeat(20))).toBeNull();
  });

  it("accepts 50,000 and refuses 50,001", async () => {
    const { checkText } = await load();

    expect(checkText("x".repeat(50_000))).toBeNull();
    expect(checkText("x".repeat(50_001))).toEqual({ kind: "text-too-long" });
  });

  it("does not count whitespace wrapped around the paste", async () => {
    const { checkText } = await load();

    // Selecting a block of a PDF usually picks up a trailing newline. That is an artefact of
    // selecting, not part of the report.
    expect(checkText(`\n\n  ${"x".repeat(20)}  \n`)).toBeNull();
    expect(checkText(`   ${"x".repeat(50_000)}   `)).toBeNull();
  });

  it("calls a box of whitespace empty rather than short", async () => {
    const { checkText } = await load();
    expect(checkText("   \n\n\t  ")).toEqual({ kind: "text-too-short" });
    expect(checkText("")).toEqual({ kind: "text-too-short" });
  });

  it("keeps the line structure inside the paste", async () => {
    const { toReportRequest } = await load();

    // Columns and line breaks are most of what makes a pasted lab report readable.
    const text = `  LIPID PANEL\n  LDL Cholesterol   162 mg/dL   <100\n  HDL    52 mg/dL   >40  `;
    const request = toReportRequest({ kind: "text", text });

    expect(request).toEqual({
      kind: "text",
      text: "LIPID PANEL\n  LDL Cholesterol   162 mg/dL   <100\n  HDL    52 mg/dL   >40",
    });
  });
});

describe("whether the button works", () => {
  it("needs a source and a tick", async () => {
    const { canSend, NO_SOURCE } = await load();

    expect(canSend(NO_SOURCE, true)).toBe(false);
    expect(canSend({ kind: "sample", sample: SAMPLE }, false)).toBe(false);
    expect(canSend({ kind: "sample", sample: SAMPLE }, true)).toBe(true);
  });

  it("stays shut while the source is one the server would refuse", async () => {
    const { canSend, MAX_FILE_BYTES } = await load();

    expect(canSend({ kind: "file", file: pdf(MAX_FILE_BYTES + 1) }, true)).toBe(false);
    expect(canSend({ kind: "text", text: "too short" }, true)).toBe(false);
  });

  it("says what is missing, and says nothing when a notice already has", async () => {
    const { missingStep, NO_SOURCE, MAX_FILE_BYTES } = await load();

    expect(missingStep(NO_SOURCE, false)).toMatch(/Choose a file/);
    expect(missingStep({ kind: "sample", sample: SAMPLE }, false)).toMatch(/Tick the box/);
    expect(missingStep({ kind: "sample", sample: SAMPLE }, true)).toBeNull();

    // The "too large" notice is already on screen; repeating it under the button is noise.
    expect(missingStep({ kind: "file", file: pdf(MAX_FILE_BYTES + 1) }, true)).toBeNull();
  });
});

describe("turning a source into a request", () => {
  it("sends the sample id, not the whole sample", async () => {
    const { toReportRequest } = await load();

    expect(toReportRequest({ kind: "sample", sample: SAMPLE })).toEqual({
      kind: "sample",
      sampleId: "lipid-panel",
    });
  });

  it("passes the file through untouched", async () => {
    const { toReportRequest } = await load();
    const file = pdf(1000);

    expect(toReportRequest({ kind: "file", file })).toEqual({ kind: "file", file });
  });

  it("has nothing to send when nothing is chosen", async () => {
    const { toReportRequest, NO_SOURCE } = await load();
    expect(toReportRequest(NO_SOURCE)).toBeNull();
  });
});

describe("the character counter", () => {
  it("groups thousands, like the mockup", async () => {
    const { formatCharCount } = await load();

    expect(formatCharCount(1240)).toBe("1,240");
    expect(formatCharCount(50_000)).toBe("50,000");
    expect(formatCharCount(0)).toBe("0");
  });
});
