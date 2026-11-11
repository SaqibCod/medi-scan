"use client";

import Link from "next/link";
import { useState } from "react";

import { Icon, IconButton, Tag } from "@/components/ds";
import { DeleteReportDialog } from "@/components/report/delete-report-dialog";
import type { ReportListItem } from "@/lib/api-types";
import { failureCopy, REPORTS_COPY } from "@/lib/copy";
import { formatDeletedIn, formatUploadedAt, sourceTypeLabel } from "@/lib/report-format";
import { isProcessing } from "@/lib/reports";
import { FailedBar, FlagBar, ProcessingBar } from "./flag-bar";

/**
 * One row of My reports (LLD 4).
 *
 * Four columns that each answer a different question: when the blood was taken, what the file
 * was, what it found, and how long it has left.
 */

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

/**
 * The big date block.
 *
 * `collectedOn` when the report printed one, because that is the date the values belong to;
 * otherwise the upload date, clearly labelled as such. Which one it is matters — a value
 * dated by upload could have been drawn months earlier — so the row says which.
 */
function dateBlock(item: ReportListItem): { day: string; monthYear: string; source: string } {
  if (item.collectedOn) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(item.collectedOn);
    if (match) {
      const [, year, month, day] = match;
      return {
        day: String(Number(day)),
        monthYear: `${MONTHS[Number(month) - 1]} ${year}`,
        source: "COLLECTED",
      };
    }
  }

  // A plain date has no time zone; an upload instant does, and belongs in the reader's.
  const at = new Date(item.createdAt);
  if (Number.isNaN(at.getTime())) return { day: "—", monthYear: "", source: "UPLOADED" };

  return {
    day: String(at.getDate()),
    monthYear: `${MONTHS[at.getMonth()]} ${at.getFullYear()}`,
    source: "UPLOADED",
  };
}

function title(item: ReportListItem): string {
  if (item.status === "FAILED") return REPORTS_COPY.titleFailed;
  if (isProcessing(item.status)) return REPORTS_COPY.titleProcessing;
  return item.sourceType === "SAMPLE" ? REPORTS_COPY.titleSample : REPORTS_COPY.titleDone;
}

export function ReportRow({
  item,
  guest,
  onDeleted,
}: {
  item: ReportListItem;
  /** Guests see hours and a warning colour; a signed-in reader sees days. */
  guest: boolean;
  onDeleted: () => void;
}) {
  const [confirming, setConfirming] = useState(false);

  const date = dateBlock(item);
  const heading = title(item);
  const running = isProcessing(item.status);

  return (
    <li className="ms-reportrow">
      <div className="ms-reportrow__date">
        <span className="ms-reportrow__day">{date.day}</span>
        <span className="ms-reportrow__month">{date.monthYear}</span>
        <span className="ms-reportrow__source">{date.source}</span>
      </div>

      <div className="ms-reportrow__what">
        <p className="flex flex-wrap items-center gap-space-3">
          <span className="t-title text-ink">{heading}</span>
          <Tag>{sourceTypeLabel(item.sourceType)}</Tag>
        </p>
        <p className="t-caption mt-space-2 text-ink-3">
          Uploaded {formatUploadedAt(item.createdAt)}
        </p>
      </div>

      <div className="ms-reportrow__found">
        {item.status === "FAILED" ? (
          <FailedBar text={failureCopy(item.errorCode).short} />
        ) : running ? (
          <ProcessingBar label={REPORTS_COPY.processingLabel} />
        ) : item.counts ? (
          <FlagBar counts={item.counts} />
        ) : null}
      </div>

      <div className="ms-reportrow__when">
        <span className={guest ? "t-caption text-flag-high-fg" : "t-caption text-ink-2"}>
          {guest
            ? `Deleted in ${formatDeletedIn(item.expiresAt)}`
            : `Expires in ${formatDeletedIn(item.expiresAt)}`}
        </span>

        <Link
          href={`/reports/${item.id}`}
          className="ms-reportrow__open"
          // The row's own heading is "Lab report" six times over, so the link says which one.
          aria-label={`Open the report from ${date.day} ${date.monthYear}`}
        >
          <Icon name="arrow-right" size={18} />
        </Link>

        <IconButton
          icon="trash"
          label={`Delete the report from ${date.day} ${date.monthYear}`}
          onClick={() => setConfirming(true)}
        />
      </div>

      <DeleteReportDialog
        reportId={item.id}
        open={confirming}
        onOpenChange={setConfirming}
        onDeleted={() => {
          setConfirming(false);
          onDeleted();
        }}
      />
    </li>
  );
}
