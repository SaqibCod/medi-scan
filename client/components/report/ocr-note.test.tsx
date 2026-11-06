import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { OcrNote } from "@/components/report/ocr-note";
import { OCR_NOTE } from "@/lib/copy";
import type { TextSource } from "@/lib/api-types";

/**
 * The OCR notice (LLD 9.3).
 *
 * The rule under test is which way this fails when it is unsure. A report whose text came from
 * OCR carries numbers a machine read off a photograph, and the reader is the only one who can
 * check them against the paper — so a missing notice is a worse bug than an unnecessary one,
 * and `TEXT_LAYER` is the only value that may hide it.
 */
describe("the OCR notice", () => {
  function renderNote(textSource: TextSource) {
    render(<OcrNote textSource={textSource} />);
  }

  it("says nothing when the text came from a text layer", () => {
    renderNote("TEXT_LAYER");

    // A PDF with real text was transcribed by nobody. Asking that reader to check every value
    // against the paper would be noise, and noise is what gets notices ignored.
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("asks the reader to check the values on an OCR report", () => {
    renderNote("OCR");

    expect(screen.getByText(OCR_NOTE.title)).toBeInTheDocument();
    expect(screen.getByText(OCR_NOTE.body)).toBeInTheDocument();
  });

  it("says which part was scanned on a mixed report", () => {
    renderNote("MIXED");

    // A different first sentence, because "read from a photo" is wrong for a report whose
    // first page came straight out of a lab system.
    expect(screen.getByText(OCR_NOTE.mixedTitle)).toBeInTheDocument();
    expect(screen.queryByText(OCR_NOTE.title)).not.toBeInTheDocument();
    expect(screen.getByText(OCR_NOTE.body)).toBeInTheDocument();
  });

  it("shows the note for a source this build does not know", () => {
    // Contract 9.2: a value added later must not switch the notice off. The cast is the point
    // of the test - the server is free to send something this union has never heard of.
    renderNote("SCANNED_BY_MAGIC" as TextSource);

    expect(screen.getByText(OCR_NOTE.title)).toBeInTheDocument();
  });

  it("is information, not an alarm", () => {
    renderNote("OCR");

    // `status`, not `alert`: nothing went wrong, and interrupting a screen reader mid-sentence
    // for "this was read from a photo" is the wrong weight for what happened.
    const notice = screen.getByRole("status");
    expect(notice).toHaveClass("ms-notice--info");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("never tells the reader a value is wrong, only that it should be checked", () => {
    renderNote("OCR");

    const text = screen.getByRole("status").textContent ?? "";
    expect(text).toMatch(/check/i);
    // No medical advice, and no claim about the result itself (root CLAUDE.md).
    expect(text).not.toMatch(/\byou have\b|diagnos|treatment|incorrect|wrong value/i);
  });
});
