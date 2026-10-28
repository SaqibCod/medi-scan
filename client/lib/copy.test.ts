import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * `copy.ts` reads the flag at import, so each case needs a fresh module registry — the same
 * shape as `env.test.ts`.
 */
async function loadCopy(ocrOn: boolean) {
  vi.resetModules();
  vi.stubEnv("NODE_ENV", "development");
  vi.stubEnv("NEXT_PUBLIC_FEATURE_OCR", ocrOn ? "true" : "");
  return import("./copy");
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

/** Every string the flag switches, so the sweep below cannot miss one by forgetting it. */
function switchedStrings(mod: Awaited<ReturnType<typeof loadCopy>>): string[] {
  return [
    mod.copy.dropzoneHint,
    mod.copy.dropzoneTitle,
    mod.copy.maxUploadLabel,
    mod.copy.steps.upload,
    mod.copy.steps.read,
    mod.copy.steps.mask,
    mod.copy.steps.extract,
    mod.copy.steps.flag,
    mod.copy.steps.explain,
    mod.copy.unsupportedFileTypeBody,
    mod.copy.unreadableBody,
    mod.failureCopy("UNREADABLE").body,
    mod.failureCopy("NO_RESULTS_FOUND").body,
  ];
}

describe("with OCR off, which is how it ships now", () => {
  it("offers a photo nowhere", async () => {
    const mod = await loadCopy(false);

    // B4: the backend answers 415 for PNG and JPEG until Phase 4, so no copy may offer one.
    // Naming photos to say they are *not* ready is the opposite of a promise and is allowed —
    // "PDF — up to 10 MB · photos coming soon" is the LLD's own wording. The test is that
    // every mention is of that kind.
    for (const line of switchedStrings(mod)) {
      // The product is called Medi-Scan, so "scan" has to come out of the name before the
      // sweep can ask whether the line is about scanning.
      const prose = line.replaceAll("Medi-Scan", "the app");

      if (/photo|OCR|scan/i.test(prose)) {
        expect(prose, line).toMatch(/coming soon/i);
      }
      // A file type listed as accepted is an offer however it is phrased.
      expect(line, line).not.toMatch(/PNG|JPEG/);
    }
  });

  it("still says photos are coming, in the one place that is the point", async () => {
    const mod = await loadCopy(false);

    // The exception, and the reason it is an exception: this notice exists to explain the
    // refusal, so it has to name what was refused.
    expect(mod.copy.photoNotYet.title).toBe("Photos are coming soon.");
    expect(mod.copy.photoNotYet.action).toBe("Paste text instead");
  });

  it("asks the file picker for PDFs only", async () => {
    const mod = await loadCopy(false);

    expect(mod.FILE_ACCEPT).toBe("application/pdf");
    expect(mod.acceptsPhotos).toBe(false);
  });

  it("tells someone with a scan what will actually work", async () => {
    const mod = await loadCopy(false);

    const body = mod.failureCopy("UNREADABLE").body;
    expect(body).toContain("coming soon");
    expect(body).toContain("pasted text");
    // Not "try a clearer photo", which would be advice they cannot act on.
    expect(body).not.toMatch(/clearer/i);
  });
});

describe("with OCR on, as Phase 4 will ship it", () => {
  it("restores the mockup wording", async () => {
    const mod = await loadCopy(true);

    expect(mod.copy.dropzoneHint).toBe("PDF · PNG · JPEG — up to 10 MB");
    expect(mod.copy.maxUploadLabel).toBe("Max upload, as PDF or a photo");
    expect(mod.copy.steps.read).toContain("OCR");
    expect(mod.failureCopy("UNREADABLE").body).toContain("clearer photo");
  });

  it("opens the picker to photos too", async () => {
    const mod = await loadCopy(true);

    expect(mod.FILE_ACCEPT).toBe("application/pdf,image/png,image/jpeg");
    expect(mod.acceptsPhotos).toBe(true);
  });

  it("changes nothing that does not depend on OCR", async () => {
    const off = await loadCopy(false);
    const on = await loadCopy(true);

    expect(on.copy.steps.mask).toBe(off.copy.steps.mask);
    expect(on.copy.steps.extract).toBe(off.copy.steps.extract);
    expect(on.RANGE_NOTE).toBe(off.RANGE_NOTE);
    expect(on.RESULTS_DISCLAIMER).toBe(off.RESULTS_DISCLAIMER);
  });
});

describe("the failed-report views", () => {
  it("covers every processing code in the contract", async () => {
    const mod = await loadCopy(false);
    const { REPORT_ERROR_CODES } = await import("./api-types");

    for (const code of REPORT_ERROR_CODES) {
      const failure = mod.failureCopy(code);
      expect(failure.eyebrow, code).toBeTruthy();
      expect(failure.headline, code).toBeTruthy();
      expect(failure.body, code).toBeTruthy();
      // A code with no view of its own would silently fall back and read as a generic fault.
      expect(failure.headline, code).not.toBe("We couldn't finish reading this report.");
    }
  });

  it("falls back for a code the contract adds later", async () => {
    const mod = await loadCopy(false);

    // Contract 9.2: an unknown code is a generic error, not a crash.
    expect(mod.failureCopy("SOMETHING_NEW").headline).toBe(
      "We couldn't finish reading this report.",
    );
    expect(mod.failureCopy(null).headline).toBe("We couldn't finish reading this report.");
    expect(mod.failureCopy(undefined).eyebrow).toBe("Something went wrong");
  });

  it("never diagnoses or advises treatment", async () => {
    const mod = await loadCopy(false);
    const { REPORT_ERROR_CODES } = await import("./api-types");

    for (const code of REPORT_ERROR_CODES) {
      const all = Object.values(mod.failureCopy(code)).join(" ");
      expect(all, code).not.toMatch(/\byou have\b|diagnos|treatment|medication|dose/i);
    }
  });
});

describe("the disclaimer", () => {
  it("carries the exact line the root rules require", async () => {
    const mod = await loadCopy(false);

    expect(mod.RESULTS_DISCLAIMER).toContain(
      "Medi-Scan explains what your report says and is not medical advice.",
    );
  });
});
