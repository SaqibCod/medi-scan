"use client";

import { useState, type ReactNode } from "react";

import {
  BiomarkerRow,
  Button,
  Card,
  ChatBubble,
  Checkbox,
  Dropzone,
  Eyebrow,
  FlagChip,
  Helix,
  Icon,
  ICON_NAMES,
  IconButton,
  Marquee,
  SampleCard,
  StatFigure,
  StatusPill,
  StepTimeline,
  Tabs,
  Tag,
  ThemeToggle,
} from "@/components/ds";

/**
 * Renders its children once per theme, side by side.
 *
 * `data-theme` is set on the column rather than on `<html>`, which works because every colour
 * is a CSS variable redefined under that attribute — the same mechanism the real toggle uses.
 * Seeing both at once is the whole point: a contrast problem in light mode is invisible when
 * you have to toggle to find it.
 */
function Pair({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-t border-line pt-space-5">
      <h3 className="t-meta mb-space-4 text-ink-3">{title}</h3>
      <div className="grid gap-space-4 md:grid-cols-2">
        {(["dark", "light"] as const).map((theme) => (
          <div
            key={theme}
            data-theme={theme}
            // `min-w-0` on the grid item as well as on the flex row inside it: both default
            // to `min-width: auto` and would otherwise be stretched by their widest child.
            className="min-w-0 rounded-lg border border-line bg-bg p-space-5"
          >
            <p className="t-meta mb-space-4 text-ink-3">{theme}</p>
            <div className="flex min-w-0 flex-wrap items-start gap-space-3">{children}</div>
          </div>
        ))}
      </div>
    </section>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mt-space-9 flex flex-col gap-space-6">
      <h2 className="t-heading text-ink">{title}</h2>
      {children}
    </div>
  );
}

const SAMPLE_STEPS = [
  { title: "Upload", body: "Drop a PDF or a photo of your report, paste the text, or pick a sample." },
  { title: "Read", body: "We pull the text out of your file." },
  { title: "Mask", body: "Names and personal details are hidden before anything leaves our server." },
  { title: "Extract", body: "The masked text is read for lab values." },
  { title: "Flag", body: "Each value is checked against the range printed on your report." },
  { title: "Explain", body: "A short summary, in plain words." },
];

export function Gallery() {
  const [tab, setTab] = useState("upload");
  const [consent, setConsent] = useState(false);
  const [step, setStep] = useState(0);

  return (
    <div className="mx-auto w-full max-w-340 px-space-4 py-space-9 md:px-space-8">
      <Eyebrow>Design system</Eyebrow>
      <h1 className="t-display-m mt-space-3 text-ink">
        Every component, <span className="text-faint">both themes.</span>
      </h1>
      <p className="t-body mt-space-4 max-w-[60ch] text-ink-2">
        Development only — this route is a 404 in production. Compare against{" "}
        <code className="t-data">docs/design-system/</code> and the mockups in{" "}
        <code className="t-data">docs/ui/screens/</code>.
      </p>

      <Group title="Actions">
        <Pair title="Button — variants">
          <Button variant="solid">Upload a report</Button>
          <Button variant="outline">Try a sample report</Button>
          <Button variant="accent" iconEnd="arrow-right">
            Analyze report
          </Button>
        </Pair>

        <Pair title="Button — sizes, icons, disabled, link">
          <Button size="sm">Scroll for more</Button>
          <Button size="lg" icon="upload">
            Upload a report
          </Button>
          <Button variant="accent" disabled>
            Analyze report
          </Button>
          <Button href="/privacy" variant="outline">
            Privacy
          </Button>
        </Pair>

        <Pair title="Button — block">
          <div className="w-full">
            <Button block size="lg" variant="accent" iconEnd="arrow-right">
              Analyze report
            </Button>
          </div>
        </Pair>

        <Pair title="IconButton">
          <IconButton icon="menu" label="Open menu" />
          <IconButton icon="close" label="Close menu" />
          <IconButton icon="chevron-left" label="Previous step" />
          <IconButton icon="chevron-right" label="Next step" />
          <IconButton icon="trash" label="Delete report" />
        </Pair>

        <Pair title="ThemeToggle — the icon follows the column's theme, not a prop">
          {/* Both icons are always rendered and CSS picks one, so each column shows the icon
              for its own theme. That is the same mechanism that keeps it from flashing or
              mismatching on a real page load. */}
          <ThemeToggle onToggle={() => {}} />
        </Pair>
      </Group>

      <Group title="Inputs">
        <Pair title="Tabs">
          <Tabs
            items={[
              { value: "upload", label: "Upload file" },
              { value: "paste", label: "Paste text" },
            ]}
            value={tab}
            onChange={setTab}
            label="Input method"
          />
        </Pair>

        <Pair title="Tabs — block">
          <div className="w-full">
            <Tabs
              items={[
                { value: "upload", label: "Upload file" },
                { value: "paste", label: "Paste text" },
              ]}
              value={tab}
              onChange={setTab}
              label="Input method, full width"
              block
            />
          </div>
        </Pair>

        <Pair title="Dropzone — wide">
          <div className="w-full">
            <Dropzone />
          </div>
        </Pair>

        <Pair title="Dropzone — tile (phones)">
          <div className="grid w-full grid-cols-2 gap-space-3">
            <Dropzone variant="tile" icon="file" title="Choose file" />
            <Dropzone variant="tile" icon="camera" title="Take a photo" capture="environment" />
          </div>
        </Pair>

        <Pair title="Checkbox">
          <Checkbox checked={consent} onChange={setConsent}>
            I understand Medi-Scan explains my report but doesn&rsquo;t give medical advice, and I
            agree to the <a href="/privacy">privacy terms</a>.
          </Checkbox>
        </Pair>
      </Group>

      <Group title="Data">
        <Pair title="FlagChip — every value, including one the API might add later">
          <FlagChip flag="LOW" />
          <FlagChip flag="NORMAL" />
          <FlagChip flag="HIGH" />
          <FlagChip flag="UNKNOWN" />
          <FlagChip flag="SOMETHING_NEW" />
        </Pair>

        <Pair title="Tag">
          <Tag>5 markers</Tag>
          <Tag>Sample</Tag>
          <Tag tone="count">4 tests</Tag>
          <Tag tone="high">2 high</Tag>
          <Tag tone="normal">2 normal</Tag>
          <Tag tone="low">1 low</Tag>
        </Pair>

        <Pair title="BiomarkerRow">
          <div className="w-full">
            <BiomarkerRow name="Total Cholesterol" value="238" unit="mg/dL" range="<200" flag="HIGH" />
            <BiomarkerRow
              name="LDL Cholesterol"
              value="162"
              unit="mg/dL"
              range="<100"
              flag="HIGH"
              biomarkerSlug="ldl-cholesterol"
            />
            <BiomarkerRow name="HDL Cholesterol" value="52" unit="mg/dL" range=">40" flag="NORMAL" />
            <BiomarkerRow name="Vitamin D" value="18" unit="ng/mL" range="30-100" flag="LOW" />
            <BiomarkerRow name="Ketones" value="Negative" unit={null} range={null} flag="UNKNOWN" />
          </div>
        </Pair>

        <Pair title="BiomarkerRow — compact (phones)">
          <div className="w-full">
            <BiomarkerRow name="Total Cholesterol" value="238" unit="mg/dL" range="<200" flag="HIGH" compact />
            <BiomarkerRow name="HDL Cholesterol" value="52" unit="mg/dL" range=">40" flag="NORMAL" compact />
          </div>
        </Pair>

        <Pair title="StatFigure">
          <div className="grid w-full grid-cols-2 gap-space-5">
            <StatFigure value="10" unit="MB" label="Max upload, as PDF or a photo" />
            <StatFigure value="24" unit="h" label="Until guest reports are deleted" />
            <StatFigure value="0" label="Chat messages stored on our server" />
            <StatFigure value="30" unit="days" label="History kept when you sign in" />
          </div>
        </Pair>

        <Pair title="SampleCard">
          <div className="grid w-full gap-space-4 lg:grid-cols-2">
            <SampleCard
              num="01"
              title="Lipid panel"
              description="Cholesterol and triglycerides, with high LDL."
              markers="5 markers"
            />
            <SampleCard
              num="02"
              title="Complete blood count"
              description="Red and white blood cells, all in range."
              markers="12 markers"
              selected
            />
          </div>
        </Pair>

        <Pair title="ChatBubble">
          <div className="ms-chat w-full">
            <ChatBubble role="user">Why is my LDL flagged?</ChatBubble>
            <ChatBubble role="assistant">
              Your LDL cholesterol is 162 mg/dL, which is above the range of &lt;100 printed on your
              report.
            </ChatBubble>
          </div>
        </Pair>
      </Group>

      <Group title="Layout">
        <Pair title="Card — variants">
          <Card>Surface card</Card>
          <Card variant="glass">Glass card</Card>
          <Card variant="panel">Panel</Card>
        </Pair>

        <Pair title="Card — interactive and selected">
          <Card onClick={() => {}}>Interactive — lifts and borders in accent on hover</Card>
          <Card onClick={() => {}} selected>
            Selected
          </Card>
        </Pair>
      </Group>

      <Group title="Navigation">
        <Pair title="StepTimeline — horizontal (desktop, needs ~720px)">
          {/* Six steps have a 120px minimum each, so the horizontal rail wants the full
              content width. These gallery columns are half that, hence the scroller — on the
              real desktop landing page it has 1360px and never scrolls. */}
          <div className="w-full min-w-0 overflow-x-auto">
            <div className="min-w-[720px]">
              <StepTimeline steps={SAMPLE_STEPS} active={step} onSelect={setStep} />
            </div>
          </div>
        </Pair>

        <Pair title="StepTimeline — vertical (phones)">
          <div className="w-full">
            <StepTimeline
              steps={SAMPLE_STEPS}
              active={step}
              onSelect={setStep}
              orientation="vertical"
            />
          </div>
        </Pair>
      </Group>

      <Group title="Feedback">
        <Pair title="StatusPill">
          <StatusPill status="online" />
          <StatusPill status="waking" />
          <StatusPill status="waking" short />
        </Pair>
      </Group>

      <Group title="Brand">
        <Pair title="Eyebrow">
          <Eyebrow>What you get</Eyebrow>
        </Pair>

        <Pair title="Marquee">
          <div className="w-full">
            <Marquee items={["Private by default", "Every value flagged", "Plain language"]} />
          </div>
        </Pair>

        <Pair title="Helix — client only, static under reduced motion">
          <Helix width={240} height={380} />
        </Pair>
      </Group>

      <Group title="Icons">
        <Pair title="The whole set — the only icons the app uses">
          {ICON_NAMES.map((name) => (
            <span key={name} className="flex w-[84px] flex-col items-center gap-space-2 text-ink-2">
              <Icon name={name} size={22} />
              <span className="t-meta text-center text-ink-3">{name}</span>
            </span>
          ))}
        </Pair>
      </Group>
    </div>
  );
}
