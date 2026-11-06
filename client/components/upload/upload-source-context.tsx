"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";

import { ApiError } from "@/lib/api-error";
import { createReport, seedReport } from "@/lib/reports";
import {
  NO_SOURCE,
  canSend,
  checkSource,
  missingStep,
  toReportRequest,
  type Source,
  type UploadProblem,
} from "@/lib/upload-source";
import type { Sample } from "@/lib/api-types";

/**
 * What the visitor has chosen to analyze, shared across the landing page.
 *
 * It is a context rather than state in one component because two sections need the same
 * answer: the upload panel near the top, and the sample cards further down. Pressing "Try it"
 * on a card has to change what the panel is holding (B6), and the phone's sticky bar has to
 * know whether it can submit.
 *
 * Nothing here is ever persisted. The chosen `File` lives in memory for as long as the page
 * does and no longer.
 */

type Tab = "file" | "text";

interface UploadSourceValue {
  source: Source;
  tab: Tab;
  consented: boolean;
  sending: boolean;
  /** Something the browser can see is wrong, before anything is sent. */
  problem: UploadProblem | null;
  /** What the server said when a send was refused. */
  failure: ApiError | null;
  /** Set when a drop carried more than one file. */
  tooManyFiles: boolean;
  ready: boolean;
  /** What is still needed, for the hint under the button. */
  missing: string | null;

  setTab: (tab: Tab) => void;
  chooseFiles: (files: FileList | File[]) => void;
  setText: (text: string) => void;
  chooseSample: (sample: Sample) => void;
  clearSource: () => void;
  setConsented: (consented: boolean) => void;
  /** The "Paste text instead" action on the photo notice. */
  switchToPasting: () => void;
  submit: () => void;
}

const UploadSourceContext = createContext<UploadSourceValue | null>(null);

export function UploadSourceProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const queryClient = useQueryClient();

  const [source, setSource] = useState<Source>(NO_SOURCE);
  const [tab, setTabState] = useState<Tab>("file");
  const [consented, setConsented] = useState(false);
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [tooManyFiles, setTooManyFiles] = useState(false);

  /** Any change of source invalidates what the last one was told. */
  const clearErrors = useCallback(() => {
    setFailure(null);
    setTooManyFiles(false);
  }, []);

  const setTab = useCallback(
    (next: Tab) => {
      setTabState(next);
      // Switching tabs drops the other tab's source: a file still held while the paste box is
      // showing would be sent by a button the visitor thinks is about their pasted text.
      setSource(NO_SOURCE);
      clearErrors();
    },
    [clearErrors],
  );

  const chooseFiles = useCallback(
    (files: FileList | File[]) => {
      const list = Array.from(files);
      if (list.length === 0) return;

      clearErrors();
      // Dropping a folder's worth of files is a slip, not a request to queue them. The first
      // one is taken and the rest are called out rather than silently dropped.
      setTooManyFiles(list.length > 1);
      setSource({ kind: "file", file: list[0] });
    },
    [clearErrors],
  );

  const setText = useCallback(
    (text: string) => {
      clearErrors();
      setSource(text.length === 0 ? NO_SOURCE : { kind: "text", text });
    },
    [clearErrors],
  );

  const chooseSample = useCallback(
    (sample: Sample) => {
      clearErrors();
      setSource({ kind: "sample", sample });
    },
    [clearErrors],
  );

  const clearSource = useCallback(() => {
    clearErrors();
    setSource(NO_SOURCE);
  }, [clearErrors]);

  const switchToPasting = useCallback(() => {
    setTabState("text");
    setSource(NO_SOURCE);
    clearErrors();
  }, [clearErrors]);

  const submit = useCallback(() => {
    const request = toReportRequest(source);
    if (!request || !consented || sending) return;
    if (checkSource(source) !== null) return;

    setSending(true);
    setFailure(null);

    void createReport(request)
      .then((created) => {
        // Seeded so the report page draws as PENDING immediately, instead of showing an
        // empty frame for a round trip right after the submit.
        seedReport(queryClient, created);
        router.push(`/reports/${created.id}`);
      })
      .catch((error: unknown) => {
        setFailure(
          error instanceof ApiError
            ? error
            : new ApiError({ status: 0, code: "UNKNOWN_ERROR", title: "", detail: "" }),
        );
        setSending(false);
      });
  }, [source, consented, sending, queryClient, router]);

  const value = useMemo<UploadSourceValue>(
    () => ({
      source,
      tab,
      consented,
      sending,
      problem: checkSource(source),
      failure,
      tooManyFiles,
      ready: canSend(source, consented),
      missing: missingStep(source, consented),
      setTab,
      chooseFiles,
      setText,
      chooseSample,
      clearSource,
      setConsented,
      switchToPasting,
      submit,
    }),
    [
      source,
      tab,
      consented,
      sending,
      failure,
      tooManyFiles,
      setTab,
      chooseFiles,
      setText,
      chooseSample,
      clearSource,
      switchToPasting,
      submit,
    ],
  );

  return <UploadSourceContext.Provider value={value}>{children}</UploadSourceContext.Provider>;
}

export function useUploadSource(): UploadSourceValue {
  const value = useContext(UploadSourceContext);
  if (!value) throw new Error("useUploadSource must be used inside UploadSourceProvider");
  return value;
}
