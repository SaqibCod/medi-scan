"use client";

import { useId } from "react";

import { MAX_TEXT_CHARS, formatCharCount, meaningfulText } from "@/lib/upload-source";
import { useUploadSource } from "./upload-source-context";

const PLACEHOLDER = `LIPID PANEL
Total Cholesterol    238 mg/dL    <200
LDL Cholesterol      162 mg/dL    <100
HDL Cholesterol       52 mg/dL     >40
Triglycerides        140 mg/dL    <150`;

/**
 * The paste box.
 *
 * Mono, because what goes in is a column-aligned table and proportional text would hide the
 * structure that makes it readable. The placeholder shows the shape rather than describing
 * it: someone who pastes the wrong half of their report finds out from the example faster
 * than from a sentence.
 *
 * The counter is against the trimmed length, which is the same length the check uses, so the
 * number on screen can never disagree with whether the button works.
 */
export function PasteBox() {
  const { source, setText } = useUploadSource();
  const id = useId();

  const text = source.kind === "text" ? source.text : "";
  const length = meaningfulText(text).length;
  const overLimit = length > MAX_TEXT_CHARS;

  return (
    <div>
      <label htmlFor={id} className="sr-only">
        Paste the text of your report
      </label>

      <textarea
        id={id}
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={8}
        spellCheck={false}
        placeholder={PLACEHOLDER}
        aria-describedby={`${id}-count`}
        className="t-data w-full resize-y rounded-md border border-line-strong bg-surface-2 px-space-4 py-space-4 text-ink placeholder:text-ink-3 focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent"
      />

      <p
        id={`${id}-count`}
        className={`t-meta mt-space-2 text-right ${overLimit ? "text-flag-high-fg" : "text-ink-3"}`}
      >
        {formatCharCount(length)} / {formatCharCount(MAX_TEXT_CHARS)}
      </p>
    </div>
  );
}
