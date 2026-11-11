"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "./api";
import type { Page, ReportListItem, ReportStatus } from "./api-types";
import { useAuth } from "./auth/provider";
import { peekGuestSession } from "./guest-session";
import { queryKeys } from "./query-client";
import { isProcessing } from "./reports";

/**
 * The history list behind `/reports` (contract 4.5).
 *
 * The endpoint filters on one status at a time, and the page shows four tabs, one of which —
 * "Processing" — is two statuses. Everything awkward about this file comes from that one
 * mismatch.
 */

/** The mockup shows six rows per page. The contract allows 1 to 50. */
export const PAGE_SIZE = 6;

/**
 * How many rows the Processing tab pulls per status before merging them.
 *
 * The API cannot answer "PENDING or PROCESSING", so that tab asks twice and interleaves the
 * answers by date in the browser. Server-side paging cannot be used across two lists — page 1
 * of each is not page 1 of the union — so it takes the contract's maximum of each and pages
 * the merged result here. In-flight reports are transient by definition; nobody has fifty at
 * once, and if they somehow did, the count beside the tab would still be exact.
 */
const MERGE_SIZE = 50;

/** Poll while anything on screen is still being read (LLD 4). */
export const LIST_POLL_MS = 3_000;

export type StatusFilter = "all" | "DONE" | "processing" | "FAILED";

export const STATUS_FILTERS: StatusFilter[] = ["all", "DONE", "processing", "FAILED"];

export function isStatusFilter(value: string | null): value is StatusFilter {
  return value !== null && (STATUS_FILTERS as string[]).includes(value);
}

function emptyPage(page: number): Page<ReportListItem> {
  return { items: [], page, size: PAGE_SIZE, totalItems: 0, totalPages: 0 };
}

function fetchPage(
  status: ReportStatus | null,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<Page<ReportListItem>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set("status", status);

  return api.get<Page<ReportListItem>>(`/api/reports?${params}`, { auth: "auto", signal });
}

/** Newest first, which is the order the endpoint already uses within each status. */
function byNewest(a: ReportListItem, b: ReportListItem): number {
  return Date.parse(b.createdAt) - Date.parse(a.createdAt);
}

/**
 * The Processing tab: two requests, one list.
 *
 * Both totals are real, so the merged total is too — it is only the rows past the fiftieth of
 * either status that could be missing, and that is not a situation this app can reach.
 */
async function fetchProcessingPage(
  page: number,
  signal?: AbortSignal,
): Promise<Page<ReportListItem>> {
  const [pending, processing] = await Promise.all([
    fetchPage("PENDING", 0, MERGE_SIZE, signal),
    fetchPage("PROCESSING", 0, MERGE_SIZE, signal),
  ]);

  const items = [...pending.items, ...processing.items].sort(byNewest);
  const totalItems = pending.totalItems + processing.totalItems;
  const start = page * PAGE_SIZE;

  return {
    items: items.slice(start, start + PAGE_SIZE),
    page,
    size: PAGE_SIZE,
    totalItems,
    totalPages: Math.max(1, Math.ceil(totalItems / PAGE_SIZE)),
  };
}

function fetchFiltered(
  filter: StatusFilter,
  page: number,
  signal?: AbortSignal,
): Promise<Page<ReportListItem>> {
  if (filter === "processing") return fetchProcessingPage(page, signal);
  return fetchPage(filter === "all" ? null : filter, page, PAGE_SIZE, signal);
}

/**
 * Whether this visitor has anything the server could possibly list.
 *
 * A guest with no session token has no reports by definition, and asking would create a
 * session — spending one of twenty per hour — to be told so. The empty state is the correct
 * answer and it costs nothing (LLD 4, "States").
 */
function canList(signedIn: boolean): boolean {
  return signedIn || peekGuestSession() !== null;
}

export interface UseReportListResult {
  page: Page<ReportListItem> | undefined;
  loading: boolean;
  error: unknown;
  /** True when there is genuinely nothing to show, rather than nothing yet. */
  empty: boolean;
}

export function useReportList(filter: StatusFilter, page: number): UseReportListResult {
  const { state, waiting } = useAuth();
  const signedIn = state.status === "SIGNED_IN";

  // Holding the query while sign-in resolves is what stops a report that belongs to an
  // account being fetched as a guest and coming back empty (contract 2.1).
  const listable = canList(signedIn);
  const enabled = !waiting && listable;

  const query = useQuery({
    queryKey: queryKeys.reportList(filter, page),
    queryFn: ({ signal }) => fetchFiltered(filter, page, signal),
    enabled,
    // A list whose rows change on the server is worth re-reading when the tab comes back.
    refetchOnWindowFocus: true,
    refetchInterval: (q) =>
      q.state.data?.items.some((item) => isProcessing(item.status)) ? LIST_POLL_MS : false,
  });

  if (!listable) {
    return { page: emptyPage(page), loading: false, error: null, empty: true };
  }

  return {
    page: query.data,
    loading: query.isPending && enabled,
    error: query.error,
    empty: query.data !== undefined && query.data.totalItems === 0,
  };
}

export type ReportCounts = Record<StatusFilter, number>;

/**
 * The numbers beside the tab labels (LLD A7).
 *
 * Five requests rather than the four the design assumed, because "Processing" is two
 * statuses. Each asks for a single row and reads `totalItems`, so the responses are a few
 * hundred bytes; the contract has no counts endpoint and inventing one is a backend change
 * this phase does not get to make.
 */
export function useReportCounts(): ReportCounts | undefined {
  const { state, waiting } = useAuth();
  const signedIn = state.status === "SIGNED_IN";
  const listable = canList(signedIn);

  const query = useQuery({
    queryKey: queryKeys.reportCounts,
    enabled: !waiting && listable,
    staleTime: 30_000,
    queryFn: async ({ signal }) => {
      const [all, done, pending, processing, failed] = await Promise.all([
        fetchPage(null, 0, 1, signal),
        fetchPage("DONE", 0, 1, signal),
        fetchPage("PENDING", 0, 1, signal),
        fetchPage("PROCESSING", 0, 1, signal),
        fetchPage("FAILED", 0, 1, signal),
      ]);

      return {
        all: all.totalItems,
        DONE: done.totalItems,
        processing: pending.totalItems + processing.totalItems,
        FAILED: failed.totalItems,
      } satisfies ReportCounts;
    },
  });

  if (!listable) return { all: 0, DONE: 0, processing: 0, FAILED: 0 };
  return query.data;
}
