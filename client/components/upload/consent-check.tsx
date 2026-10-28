"use client";

import Link from "next/link";

import { Checkbox } from "@/components/ds";
import { useUploadSource } from "./upload-source-context";

/**
 * The consent box the API requires (`consent: true`).
 *
 * Unticked every time the page loads, never remembered. Consent that persists is not consent
 * that was given for this upload, and the box exists so the sentence beside it gets read.
 */
export function ConsentCheck() {
  const { consented, setConsented } = useUploadSource();

  return (
    <Checkbox checked={consented} onChange={setConsented}>
      I understand Medi-Scan explains my report but doesn&apos;t give medical advice, and I
      agree to the{" "}
      <Link href="/privacy" className="text-accent-ink underline underline-offset-4">
        privacy terms
      </Link>
      .
    </Checkbox>
  );
}
