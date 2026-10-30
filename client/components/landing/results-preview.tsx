import { BiomarkerRow, Card, ChatBubble, Eyebrow, Icon, Tag } from "@/components/ds";

/**
 * A picture of the output, not live data.
 *
 * Labelled "Example" because every value in it is invented. The two chat bubbles stay — they
 * are the one place the landing page shows what Phase 5 adds, and inside a card that already
 * says "Example" they illustrate rather than promise. Nothing here is interactive: there is
 * no chat box to type in until chat exists.
 */

const EXAMPLE = [
  { name: "Total Cholesterol", value: "238", unit: "mg/dL", range: "<200", flag: "HIGH" },
  { name: "LDL Cholesterol", value: "162", unit: "mg/dL", range: "<100", flag: "HIGH" },
  { name: "HDL Cholesterol", value: "52", unit: "mg/dL", range: ">40", flag: "NORMAL" },
  { name: "Triglycerides", value: "140", unit: "mg/dL", range: "<150", flag: "NORMAL" },
] as const;

const POINTS = [
  { key: "A", text: "A table of every test, with value, unit and the reference range from your report." },
  { key: "B", text: "Low, normal and high flags, plus a short summary in plain words." },
  { key: "C", text: "A chat to ask follow-up questions, answered only from your report." },
];

export function ResultsPreview() {
  return (
    <section id="results" aria-labelledby="results-heading" className="scroll-mt-24 py-space-9">
      {/*
        Not a `Card`: this band carries its own ground. The design system names exactly two
        gradients (README, "Colour"), and this is one of them — a faint radial glow of
        `accent` and `helix-cyan` over the page ground, which the mockup holds in a
        `--preview-bg` token. A glass card over flat `surface` renders the band noticeably
        flatter than the design.
      */}
      <div
        className="overflow-hidden rounded-2xl border border-line p-space-7 md:p-space-9"
        // The `background` shorthand, not `background-image`: the token's last layer is a
        // solid colour, which `background-image` rejects outright — the whole declaration is
        // dropped and the band renders flat.
        style={{ background: "var(--preview-bg)" }}
      >
        <div className="grid gap-space-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] lg:gap-space-9">
          <div>
            <Eyebrow>What you get</Eyebrow>
            <h2 id="results-heading" className="t-display-m mt-space-4 max-w-[14ch] text-ink">
              Every value, flagged and explained.
            </h2>

            {/* Hairline-ruled rows, as the mockup has them: a line above each and one
                closing the list. */}
            <ul className="mt-space-7 flex flex-col">
              {POINTS.map((point, i) => (
                <li
                  key={point.key}
                  className={`flex gap-space-4 border-t border-line py-space-4 ${
                    i === POINTS.length - 1 ? "border-b" : ""
                  }`}
                >
                  <span className="t-meta shrink-0 pt-[3px] text-ink-3">{point.key}</span>
                  <span className="t-body-s text-ink-2">{point.text}</span>
                </li>
              ))}
            </ul>
          </div>

          <div className="flex min-w-0 flex-col gap-space-4">
            <Card variant="glass">
              <div className="flex items-center justify-between gap-space-4">
                <span className="flex items-center gap-space-3">
                  <span className="t-body text-ink">Lipid panel</span>
                  {/* Says outright that nothing below is real data. */}
                  <Tag>Example</Tag>
                </span>
                <span className="t-meta text-ink-3">Collected 28 Sep 2026</span>
              </div>

              <div className="mt-space-4 flex flex-wrap gap-space-2">
                <Tag tone="count">4 tests</Tag>
                <Tag tone="high">2 high</Tag>
                <Tag tone="normal">2 normal</Tag>
              </div>

              <div className="mt-space-5">
                {EXAMPLE.map((row) => (
                  <BiomarkerRow key={row.name} {...row} />
                ))}
              </div>
            </Card>

            {/* The chat sits in its own card beside the results one, as in the mockup. */}
            <Card variant="glass">
              <div className="flex flex-col gap-space-3">
                <ChatBubble role="user">Why is my LDL flagged?</ChatBubble>
                <ChatBubble role="assistant">
                  Your LDL cholesterol is 162 mg/dL, which is above the range of &lt;100 printed
                  on your report.
                </ChatBubble>
                <p className="t-meta flex items-center gap-space-2 text-ink-3">
                  <Icon name="sources" size={14} />
                  Sources: your report · LDL Cholesterol
                </p>
              </div>
            </Card>

            <p className="t-meta text-ink-3">
              Every value above is made up, and the chat arrives in a later release.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
