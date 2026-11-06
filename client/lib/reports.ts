"use client";

import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";

import { api } from "./api";
import { ApiError } from "./api-error";
import { queryKeys } from "./query-client";
import type { CreateReportResponse, ReportResponse, ReportStatus, Sample } from "./api-types";

/**
 * Creating, watching and deleting a report.
 *
 * The polling rules live here rather than in the page because they are the part with teeth:
 * a poll that never stops is a request every 1.5 seconds for as long as a forgotten tab stays
 * open, against a backend on free hosting.
 */

/** Contract 4.2: the client polls every 1.5 seconds. */
export const POLL_INTERVAL_MS = 1_500;

/** Contract 4.2: for up to 2 minutes, after which the page asks before carrying on. */
export const POLL_LIMIT_MS = 120_000;

/** `GET /api/samples` is cacheable for an hour, and the list never changes within a visit. */
const SAMPLES_STALE_MS = 3_600_000;

/**
 * Anything that is not finished is still running.
 *
 * Contract 9.2 says an unknown status means keep polling: a status this build has never heard
 * of is far more likely to be a new intermediate step than a finished report.
 */
export function isProcessing(status: ReportStatus | string | undefined): boolean {
  return status !== "DONE" && status !== "FAILED";
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Report ids are random UUIDs. Anything else never becomes a request. */
export function isReportId(value: string): boolean {
  return UUID.test(value);
}

/** What `POST /api/reports` needs. The upload panel's richer `Source` narrows to this. */
export type ReportRequest =
  | { kind: "file"; file: File }
  | { kind: "text"; text: string }
  | { kind: "sample"; sampleId: string };

async function send(request: ReportRequest): Promise<CreateReportResponse> {
  if (request.kind === "file") {
    const form = new FormData();
    form.append("file", request.file);
    // The contract wants the string "true" in the multipart part, not a boolean.
    form.append("consent", "true");
    // No Content-Type: the browser has to set the multipart boundary itself (api.ts).
    return api.post<CreateReportResponse>("/api/reports", form, { auth: "guest" });
  }

  const body =
    request.kind === "text"
      ? { text: request.text, consent: true }
      : { sampleId: request.sampleId, consent: true };

  return api.post<CreateReportResponse>("/api/reports", body, { auth: "guest" });
}

/**
 * Creates a report, retrying once if the guest session turned out to be dead.
 *
 * A guest session lasts 24 hours and someone can leave the landing page open for longer than
 * that. By the time this throws `SESSION_INVALID` the 7a client has already dropped the stale
 * token, so the second attempt creates a fresh session and sends the same file. Exactly one
 * retry: a second rejection is not a stale token, and asking again would only spend the
 * per-IP limit.
 */
export async function createReport(request: ReportRequest): Promise<CreateReportResponse> {
  try {
    return await send(request);
  } catch (error) {
    if (error instanceof ApiError && error.code === "SESSION_INVALID") {
      return send(request);
    }
    throw error;
  }
}

export function getReport(id: string, signal?: AbortSignal): Promise<ReportResponse> {
  return api.get<ReportResponse>(`/api/reports/${id}`, { auth: "guest", signal });
}

/**
 * Deletes a report, treating "it was not there" as success.
 *
 * A 404 means the report is gone — expired, or already deleted in another tab. The person
 * asked for it to not exist, and it does not exist. Reporting that as a failure would invite
 * them to try again at something that already worked.
 */
export async function deleteReport(id: string): Promise<void> {
  try {
    await api.delete<void>(`/api/reports/${id}`, { auth: "guest" });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return;
    throw error;
  }
}

export function getSamples(signal?: AbortSignal): Promise<Sample[]> {
  return api.get<Sample[]>("/api/samples", { auth: "none", signal });
}

/**
 * Puts the `202` response in the cache as a PENDING report.
 *
 * Without it the report page mounts with nothing, fetches, and shows a blank frame for a
 * round trip — right after a submit, which is the moment the page most needs to look like it
 * heard. The shape is the same as a real `GET`, with the two fields a pending report has not
 * got yet.
 */
export function seedReport(client: QueryClient, created: CreateReportResponse): void {
  client.setQueryData<ReportResponse>(queryKeys.report(created.id), {
    ...created,
    error: null,
    result: null,
  });
}

export function useSamples() {
  return useQuery({
    queryKey: queryKeys.samples,
    queryFn: ({ signal }) => getSamples(signal),
    staleTime: SAMPLES_STALE_MS,
  });
}

export interface UseReportResult {
  report: ReportResponse | undefined;
  error: ApiError | null;
  /** The 2-minute limit passed and the report is still not finished. */
  tookTooLong: boolean;
  /** Gives it another two minutes and checks again. */
  keepWaiting: () => void;
}

/**
 * Watches one report until it finishes.
 *
 * Three things stop the polling, and all three matter:
 * - a terminal status, because there is nothing left to learn
 * - an error the server has already decided (a 404, a dead session), because asking again
 *   cannot change a decision
 * - the 2-minute limit, because a tab left open overnight must not keep a free-tier backend
 *   awake by itself
 *
 * Pausing while the tab is hidden comes free: TanStack only runs the interval's fetch when
 * `focusManager` says the document is visible, and `refetchIntervalInBackground` is off by
 * default. `refetchOnWindowFocus` is turned on only while the report is unfinished, so
 * coming back to the tab checks once immediately without leaving a finished report refetching
 * for the rest of the session.
 */
export function useReport(id: string): UseReportResult {
  const enabled = isReportId(id);

  const [deadline, setDeadline] = useState(() => Date.now() + POLL_LIMIT_MS);

  /**
   * The deadline that has actually passed, set by the timer below.
   *
   * Held as the deadline value rather than a boolean so it needs no resetting: giving the
   * report two more minutes moves `deadline`, which no longer matches, and the derived flag
   * below goes false on its own.
   */
  const [expired, setExpired] = useState<number | null>(null);

  const query = useQuery({
    queryKey: queryKeys.report(id),
    queryFn: ({ signal }) => getReport(id, signal),
    enabled,
    // The whole point is to see a change, so a cached answer is never good enough.
    staleTime: 0,
    // Closing over `deadline` is enough to stay current: `useQuery` hands the observer a new
    // options object after every render, so the function running on each tick is the one
    // this render built.
    refetchInterval: (q) => {
      if (!isProcessing(q.state.data?.status)) return false;
      if (Date.now() >= deadline) return false;

      // A 404 or a dead session is settled. Only a failure that might clear up on its own —
      // a dropped connection, a 5xx, a backend still waking — is worth asking again about.
      const error = q.state.error;
      if (error instanceof ApiError && !error.isRetryable) return false;

      return POLL_INTERVAL_MS;
    },
    refetchOnWindowFocus: (q) => isProcessing(q.state.data?.status),
  });

  const unfinished = enabled && isProcessing(query.data?.status);

  useEffect(() => {
    if (!unfinished) return;

    const timer = setTimeout(() => setExpired(deadline), Math.max(0, deadline - Date.now()));
    return () => clearTimeout(timer);
  }, [unfinished, deadline]);

  const keepWaiting = useCallback(() => {
    setDeadline(Date.now() + POLL_LIMIT_MS);
    void query.refetch();
  }, [query]);

  return {
    report: query.data,
    error: query.error instanceof ApiError ? query.error : null,
    tookTooLong: unfinished && expired === deadline,
    keepWaiting,
  };
}

/** Drops a deleted report from the cache so nothing re-renders it on the way out. */
export function useForgetReport() {
  const client = useQueryClient();

  return useCallback(
    (id: string) => {
      void client.cancelQueries({ queryKey: queryKeys.report(id) });
      client.removeQueries({ queryKey: queryKeys.report(id) });
    },
    [client],
  );
}
