"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { SignInCard } from "@/components/auth/sign-in-card";
import { Button, Card, Checkbox, Eyebrow, Icon, type IconName } from "@/components/ds";
import { ApiError } from "@/lib/api-error";
import { fetchMe } from "@/lib/auth/api";
import { useAuth } from "@/lib/auth/provider";
import { initialFor } from "@/lib/auth/state";
import { ACCOUNT_COPY } from "@/lib/copy";
import { queryKeys } from "@/lib/query-client";
import { formatCollectedOn } from "@/lib/report-format";
import { useTrendMarkers } from "@/lib/trends";

/**
 * The account page (LLD 5).
 *
 * Three things a person might come here for: to see what is held about them, to sign out of
 * every tab, and to delete the lot. The page is arranged in that order of increasing
 * consequence, and the last one is the only thing on it that asks twice.
 */
export function AccountView() {
  const { state, user, signOut } = useAuth();

  const me = useQuery({
    queryKey: queryKeys.me,
    queryFn: ({ signal }) => fetchMe(signal),
    enabled: state.status === "SIGNED_IN",
  });

  const markers = useTrendMarkers();

  // Nothing is claimed while the answer is still being worked out. Showing the signed-out card
  // to someone whose refresh is still in flight would be telling them they are signed out.
  if (state.status === "RESOLVING" || state.status === "SIGNING_IN") {
    return <AccountSkeleton />;
  }

  if (!user) {
    return (
      <div className="py-space-9">
        <SignInCard
          eyebrow={ACCOUNT_COPY.eyebrow}
          headline={ACCOUNT_COPY.signedOut.headline}
          headlineFaint={ACCOUNT_COPY.signedOut.headlineFaint}
          benefits={ACCOUNT_COPY.signedOut.benefits}
        />
      </div>
    );
  }

  const reportCount = me.data?.reportCount ?? 0;
  const markerCount = markers.data?.length ?? 0;

  return (
    <div className="py-space-9">
      <header>
        <Eyebrow>{ACCOUNT_COPY.eyebrow}</Eyebrow>
        <h1 className="t-display-l mt-space-4 text-ink">
          {ACCOUNT_COPY.headline}
          <br />
          <span className="text-faint">{ACCOUNT_COPY.signedInAs(user.displayName)}</span>
        </h1>
      </header>

      <div className="ms-account">
        <Card className="ms-account__profile">
          <span className="ms-account__avatar" aria-hidden="true">
            {initialFor(user.displayName)}
          </span>

          <p className="t-title mt-space-5 text-ink">{user.displayName}</p>
          <p className="mt-space-3">
            <span className="ms-tag">{ACCOUNT_COPY.googleTag}</span>
          </p>

          <dl className="mt-space-6 border-t border-line">
            <Fact label={ACCOUNT_COPY.memberSince} value={formatCollectedOn(user.createdAt.slice(0, 10))} />
            <Fact label={ACCOUNT_COPY.reportsSaved} value={String(reportCount)} />
          </dl>

          <div className="mt-space-6">
            <Button variant="outline" block onClick={() => void signOut()}>
              {ACCOUNT_COPY.signOut}
            </Button>
            <p className="t-caption mt-space-3 text-center text-ink-3">
              {ACCOUNT_COPY.signOutNote}
            </p>
          </div>
        </Card>

        <div className="ms-account__main">
          <div className="ms-account__stats">
            <StatCard
              icon="file"
              figure={reportCount}
              label={ACCOUNT_COPY.statReports}
              href="/reports"
            />
            <StatCard
              icon="chart"
              figure={markerCount}
              label={ACCOUNT_COPY.statMarkers}
              href="/trends"
            />
          </div>

          <Card>
            <h2 className="t-title text-ink">{ACCOUNT_COPY.keepsTitle}</h2>
            <ul className="mt-space-5">
              {ACCOUNT_COPY.keeps.map((row) => (
                <li key={row.label} className="ms-account__keep">
                  <span className="shrink-0 text-accent" aria-hidden="true">
                    <Icon name={row.icon as IconName} size={18} />
                  </span>
                  <span className="t-body-s text-ink">{row.label}</span>
                  <span className="t-body-s text-ink-2">{row.body}</span>
                </li>
              ))}
            </ul>
          </Card>

          <DeleteAccountCard reportCount={reportCount} />
        </div>
      </div>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between border-b border-line py-space-4">
      <dt className="t-body-s text-ink-2">{label}</dt>
      <dd className="t-data text-ink">{value}</dd>
    </div>
  );
}

function StatCard({
  icon,
  figure,
  label,
  href,
}: {
  icon: IconName;
  figure: number;
  label: string;
  href: string;
}) {
  return (
    <Link href={href} className="ms-statcard">
      <span className="ms-statcard__top">
        <span className="text-accent" aria-hidden="true">
          <Icon name={icon} size={20} />
        </span>
        <span className="text-ink-3" aria-hidden="true">
          <Icon name="arrow-up-right" size={18} />
        </span>
      </span>
      <span className="t-numeral-l mt-space-5 block text-ink">{figure}</span>
      <span className="t-body-s mt-space-2 block text-ink-2">{label}</span>
    </Link>
  );
}

/**
 * Deleting the account (mockups `--02` and `--05`).
 *
 * An inline confirm rather than a dialog, because the thing being confirmed is the card it
 * opens inside: the sentence above it says what will go, and the question repeats the number.
 * The checkbox is the gate — a button that is one mis-click from destroying everything is not
 * made safe by being labelled carefully.
 */
function DeleteAccountCard({ reportCount }: { reportCount: number }) {
  const { deleteAccount } = useAuth();

  const [open, setOpen] = useState(false);
  const [understood, setUnderstood] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  function confirm() {
    setDeleting(true);
    setFailure(null);

    void deleteAccount()
      .catch((error: unknown) => {
        // The panel stays open and ticked. Closing it would leave someone believing the
        // account was deleted when it was not.
        setFailure(
          error instanceof ApiError && error.code === "NETWORK_ERROR"
            ? "That didn't reach the server. Your account is unchanged. Try again."
            : "That didn't work. Your account is unchanged. Try again.",
        );
        setDeleting(false);
      });
  }

  return (
    <Card className="ms-account__delete">
      <div className="ms-account__deletehead">
        <span className="ms-account__deleteicon" aria-hidden="true">
          <Icon name="trash" size={18} />
        </span>

        <div className="flex-1">
          <h2 className="t-title text-ink">{ACCOUNT_COPY.deleteTitle}</h2>
          <p className="t-body-s mt-space-2 max-w-[60ch] text-ink-2">
            {ACCOUNT_COPY.deleteBody(reportCount)}
          </p>
        </div>

        {!open && (
          <Button variant="outline" icon="trash" onClick={() => setOpen(true)}>
            {ACCOUNT_COPY.deleteTrigger}
          </Button>
        )}
      </div>

      {open && (
        <div className="ms-account__confirm">
          <p className="t-body-s text-ink">{ACCOUNT_COPY.confirmQuestion(reportCount)}</p>

          <Checkbox checked={understood} onChange={setUnderstood}>
            {ACCOUNT_COPY.confirmCheckbox}
          </Checkbox>

          {failure && <p className="t-caption text-flag-high-fg">{failure}</p>}

          <div className="flex flex-wrap justify-end gap-space-3">
            <Button
              onClick={() => {
                setOpen(false);
                setUnderstood(false);
                setFailure(null);
              }}
              disabled={deleting}
            >
              {ACCOUNT_COPY.confirmKeep}
            </Button>
            <Button
              variant="outline"
              icon="trash"
              onClick={confirm}
              disabled={!understood || deleting}
            >
              {ACCOUNT_COPY.confirmDelete}
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function AccountSkeleton() {
  return (
    <div className="py-space-9" aria-hidden="true">
      <span className="ms-skel block" style={{ width: 280, height: 28 }} />
      <span className="ms-skel mt-space-5 block" style={{ width: 420, height: 56 }} />
      <div className="ms-account">
        <span className="ms-skel" style={{ height: 320, borderRadius: 16 }} />
        <span className="ms-skel" style={{ height: 320, borderRadius: 16 }} />
      </div>
    </div>
  );
}
