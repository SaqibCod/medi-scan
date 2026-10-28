import { StatFigure } from "@/components/ds";
import { copy } from "@/lib/copy";

/**
 * Four numbers that are all true of the running build.
 *
 * `StatFigure`'s rule is that these are product facts, never marketing numbers: the upload
 * limit and both retention windows are the contract's, and "0 chat messages stored" is what
 * the server actually does.
 */
export function FactsRow() {
  return (
    <section className="py-space-9">
      <div className="grid gap-space-8 md:grid-cols-2 md:gap-space-9">
        <h2 className="t-display-m max-w-[18ch] text-ink">
          Built to make lab results readable,{" "}
          <span className="text-faint">and to forget them when you&apos;re done.</span>
        </h2>

        <div className="grid grid-cols-2 gap-x-space-6 gap-y-space-8">
          <StatFigure value="10" unit="MB" label={copy.maxUploadLabel} />
          <StatFigure value="24" unit="h" label="Until guest reports are deleted" />
          <StatFigure value="0" label="Chat messages stored on our server" />
          <StatFigure value="30" unit="days" label="History kept when you sign in" />
        </div>
      </div>
    </section>
  );
}
