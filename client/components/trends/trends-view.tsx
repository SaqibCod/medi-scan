"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { SignInCard } from "@/components/auth/sign-in-card";
import { Button, Eyebrow, Tabs } from "@/components/ds";
import { api } from "@/lib/api";
import type { Page, ReportListItem } from "@/lib/api-types";
import { useAuth } from "@/lib/auth/provider";
import { TRENDS_COPY } from "@/lib/copy";
import { queryKeys } from "@/lib/query-client";
import { useTrend, useTrendMarkers } from "@/lib/trends";
import { MultiUnitNotice, SeriesCard } from "./series-card";

/**
 * Trends (`/trends`) — LLD 6.
 *
 * One marker at a time, one card per unit. Units are never converted and never merged, which
 * is the server's rule and the only safe one: 97 mg/dL and 5.4 mmol/L are the same glucose
 * reading, and a chart that put them on one axis would invent a change that never happened.
 */
export function TrendsView() {
  const router = useRouter();
  const params = useSearchParams();
  const { state, user } = useAuth();

  const [view, setView] = useState<"chart" | "table">("chart");

  const markers = useTrendMarkers();
  const urlMarker = params.get("marker");
  // A marker named in the URL wins, even if it is not in the list: it may have a single
  // point, which is exactly the case a link from a biomarker page arrives with.
  const selected = urlMarker ?? markers.data?.[0]?.marker ?? null;
  const trend = useTrend(selected);

  /** Only for the "not enough yet" line, so it is fetched only when that is in play. */
  const doneCount = useQuery({
    queryKey: [...queryKeys.reports, "done-count"],
    queryFn: ({ signal }) =>
      api.get<Page<ReportListItem>>("/api/reports?status=DONE&size=1", { auth: "auto", signal }),
    enabled: state.status === "SIGNED_IN" && markers.isSuccess && markers.data.length === 0,
  });

  const select = useCallback(
    (marker: string) => {
      router.push(`/trends?marker=${encodeURIComponent(marker)}`, { scroll: false });
    },
    [router],
  );

  if (state.status === "RESOLVING" || state.status === "SIGNING_IN") {
    return <TrendsSkeleton />;
  }

  if (!user) {
    return (
      <div className="py-space-9">
        <Header />
        <div className="mt-space-8">
          <SignInCard
            headline={TRENDS_COPY.signedOut.headline}
            body={TRENDS_COPY.signedOut.body}
          />
        </div>
      </div>
    );
  }

  // Signed in, but no marker yet appears in two finished reports.
  if (markers.isSuccess && markers.data.length === 0 && !urlMarker) {
    return (
      <div className="py-space-9">
        <Header />
        <section className="ms-notenough">
          <div>
            <h2 className="t-display-s text-ink">{TRENDS_COPY.notEnough.title}</h2>
            <p className="t-body mt-space-4 max-w-[52ch] text-ink-2">
              {TRENDS_COPY.notEnough.body(doneCount.data?.totalItems ?? 0)}
            </p>
            <div className="mt-space-6 flex flex-wrap gap-space-3">
              <Button href="/#upload" icon="upload">
                {TRENDS_COPY.notEnough.add}
              </Button>
              <Button href="/reports" variant="outline">
                {TRENDS_COPY.notEnough.reports}
              </Button>
            </div>
          </div>
          <div className="ms-notenough__art" aria-hidden="true" />
        </section>
      </div>
    );
  }

  const series = trend.data?.series ?? [];
  const displayName = trend.data?.displayName ?? selected ?? "";

  return (
    <div className="py-space-9">
      <Header />

      <div className="ms-trends">
        <aside className="ms-trends__list">
          <Eyebrow>{TRENDS_COPY.listEyebrow}</Eyebrow>
          <ul className="mt-space-4 flex flex-col gap-space-3">
            {(markers.data ?? []).map((marker) => (
              <li key={marker.marker}>
                <button
                  type="button"
                  className="ms-markerbtn"
                  aria-current={marker.marker === selected ? "true" : undefined}
                  onClick={() => select(marker.marker)}
                >
                  <span className="t-body-s">{marker.displayName}</span>
                  <span className="t-data text-ink-3">
                    {TRENDS_COPY.reports(marker.reportCount)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <p className="t-caption mt-space-5 text-ink-3">{TRENDS_COPY.listNote}</p>
        </aside>

        <div className="ms-trends__main">
          <div className="flex flex-wrap items-center justify-between gap-space-4">
            <h2 className="t-display-s text-ink">{displayName}</h2>
            <Tabs
              label="Chart or table"
              value={view}
              onChange={(next) => setView(next as "chart" | "table")}
              items={[
                { value: "chart", label: TRENDS_COPY.chart },
                { value: "table", label: TRENDS_COPY.table },
              ]}
            />
          </div>

          {series.length > 1 && (
            <MultiUnitNotice markerName={displayName} count={series.length} />
          )}

          {trend.isPending ? (
            <span className="ms-skel mt-space-6 block" style={{ height: 320, borderRadius: 16 }} />
          ) : series.length === 0 ? (
            <p className="t-body mt-space-6 text-ink-2">{TRENDS_COPY.emptyMarker}</p>
          ) : (
            series.map((entry) => (
              <SeriesCard
                key={entry.unit ?? "no-unit"}
                markerName={displayName}
                unit={entry.unit}
                points={entry.points}
                view={view}
              />
            ))
          )}

          <p className="t-caption mt-space-6 text-ink-3">{TRENDS_COPY.footer}</p>
        </div>
      </div>
    </div>
  );
}

function Header() {
  return (
    <header>
      <Eyebrow>{TRENDS_COPY.eyebrow}</Eyebrow>
      <h1 className="t-display-l mt-space-4 text-ink">
        {TRENDS_COPY.headline}
        <br />
        <span className="text-faint">{TRENDS_COPY.subhead}</span>
      </h1>
    </header>
  );
}

function TrendsSkeleton() {
  return (
    <div className="py-space-9" aria-hidden="true">
      <span className="ms-skel block" style={{ width: 220, height: 24 }} />
      <span className="ms-skel mt-space-5 block" style={{ width: 460, height: 56 }} />
      <div className="ms-trends">
        <span className="ms-skel" style={{ height: 260, borderRadius: 16 }} />
        <span className="ms-skel" style={{ height: 360, borderRadius: 16 }} />
      </div>
    </div>
  );
}
