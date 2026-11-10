"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { GoogleButton } from "@/components/auth/google-button";
import { Button, Eyebrow, Helix, Icon, Tabs } from "@/components/ds";
import { useAuth } from "@/lib/auth/provider";
import { REPORTS_COPY } from "@/lib/copy";
import { CAN_SIGN_IN } from "@/lib/env";
import {
  isStatusFilter,
  PAGE_SIZE,
  STATUS_FILTERS,
  useReportCounts,
  useReportList,
  type StatusFilter,
} from "@/lib/history";
import { queryKeys } from "@/lib/query-client";
import { useTrendMarkers } from "@/lib/trends";
import { Pagination } from "./pagination";
import { ReportRow } from "./report-row";
import { TrendsTeaser } from "./trends-teaser";

/**
 * My reports (`/reports`), for guests and signed-in readers alike (LLD 4, A6).
 *
 * Guests get the same list of the same shape. What differs is how long the reports last and
 * what the page says about it — hours in a warning colour against days in plain text — plus
 * the banner offering to make that difference go away.
 */

const TAB_LABELS: Record<StatusFilter, string> = {
  all: "All",
  DONE: "Done",
  processing: "Processing",
  FAILED: "Failed",
};

export function ReportsView() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();

  const { state, user, waiting } = useAuth();
  const guest = state.status === "GUEST";

  const filter: StatusFilter = isStatusFilter(params.get("status")) ? (params.get("status") as StatusFilter) : "all";
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);

  const counts = useReportCounts();
  const { page: result, loading, empty } = useReportList(filter, page);
  const markers = useTrendMarkers();

  /** Both the tab and the page live in the URL, so back and forward work (LLD 4). */
  const go = useCallback(
    (next: { status?: StatusFilter; page?: number }) => {
      const search = new URLSearchParams();
      const status = next.status ?? filter;
      // Changing the tab always returns to the first page: page 3 of "All" is rarely page 3
      // of "Failed", and landing on an empty page reads as a bug.
      const target = next.status !== undefined ? 0 : (next.page ?? page);

      if (status !== "all") search.set("status", status);
      if (target > 0) search.set("page", String(target));

      const query = search.toString();
      router.push(query ? `/reports?${query}` : "/reports", { scroll: false });
    },
    [filter, page, router],
  );

  const refreshAfterDelete = useCallback(() => {
    // The counts are stale the moment a row goes, and so is every other page of the list.
    void queryClient.invalidateQueries({ queryKey: queryKeys.reports });
  }, [queryClient]);

  const total = counts?.all ?? 0;
  const items = result?.items ?? [];
  const showEmpty = empty && filter === "all" && !loading;

  const subhead = showEmpty
    ? REPORTS_COPY.emptySubhead
    : user
      ? REPORTS_COPY.userSubhead(total)
      : REPORTS_COPY.guestSubhead(total);

  return (
    <div className="py-space-9">
      <header className="flex flex-wrap items-end justify-between gap-space-6">
        <div>
          <Eyebrow>{REPORTS_COPY.eyebrow}</Eyebrow>
          <h1 className="t-display-l mt-space-4 text-ink">
            {REPORTS_COPY.headline}
            <br />
            <span className="text-faint">{waiting ? " " : subhead}</span>
          </h1>
        </div>

        <Button href="/#upload" icon="upload">
          {REPORTS_COPY.newReport}
        </Button>
      </header>

      {guest && CAN_SIGN_IN && !showEmpty && (
        <section className="ms-guestbanner">
          <span className="ms-guestbanner__icon" aria-hidden="true">
            <Icon name="clock" size={18} />
          </span>
          <p className="t-body-s flex-1 text-ink-2">{REPORTS_COPY.guestBanner}</p>
          <GoogleButton width={240} />
        </section>
      )}

      {user && markers.data && markers.data.length > 0 && <TrendsTeaser markers={markers.data} />}

      {showEmpty ? (
        <EmptyState />
      ) : (
        <>
          <div className="mt-space-8 flex flex-wrap items-center justify-between gap-space-4">
            <Tabs
              label="Filter reports by status"
              value={filter}
              onChange={(next) => go({ status: next as StatusFilter })}
              items={STATUS_FILTERS.map((value) => ({
                value,
                label: counts ? `${TAB_LABELS[value]} ${counts[value]}` : TAB_LABELS[value],
              }))}
            />

            {result && result.totalItems > 0 && (
              <p className="t-caption text-ink-3">
                {REPORTS_COPY.showing(
                  page * PAGE_SIZE + 1,
                  Math.min((page + 1) * PAGE_SIZE, result.totalItems),
                  result.totalItems,
                )}
              </p>
            )}
          </div>

          {loading ? (
            <SkeletonRows />
          ) : items.length === 0 ? (
            <p className="t-body mt-space-7 text-ink-2">
              Nothing in this tab right now.
            </p>
          ) : (
            <ul className="mt-space-5 flex flex-col gap-space-4">
              {items.map((item) => (
                <ReportRow
                  key={item.id}
                  item={item}
                  guest={!user}
                  onDeleted={refreshAfterDelete}
                />
              ))}
            </ul>
          )}

          <div className="mt-space-8 flex flex-col items-center gap-space-6">
            <Pagination
              page={page}
              totalPages={result?.totalPages ?? 0}
              onChange={(next) => go({ page: next })}
            />
            <p className="t-caption text-center text-ink-3">
              {user ? REPORTS_COPY.footerUser : REPORTS_COPY.footerGuest}
            </p>
          </div>
        </>
      )}
    </div>
  );
}

function SkeletonRows() {
  return (
    <ul className="mt-space-5 flex flex-col gap-space-4" aria-hidden="true">
      {[0, 1, 2, 3, 4, 5].map((row) => (
        <li key={row} className="ms-skel" style={{ height: 104, borderRadius: 16 }} />
      ))}
    </ul>
  );
}

/** Mockups `--03` and `--06`. No tabs and no banner: there is nothing to filter or keep. */
function EmptyState() {
  return (
    <section className="ms-emptyreports">
      <div className="ms-emptyreports__body">
        <h2 className="t-display-s text-ink">{REPORTS_COPY.empty.title}</h2>
        <p className="t-body mt-space-4 max-w-[46ch] text-ink-2">{REPORTS_COPY.empty.body}</p>

        <div className="mt-space-6 flex flex-wrap gap-space-3">
          <Button href="/#upload" icon="upload">
            {REPORTS_COPY.empty.upload}
          </Button>
          <Button href="/#samples" variant="outline">
            {REPORTS_COPY.empty.sample}
          </Button>
        </div>
      </div>

      <div className="ms-emptyreports__art" aria-hidden="true">
        <Helix />
      </div>
    </section>
  );
}
