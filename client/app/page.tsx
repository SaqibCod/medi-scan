import Link from "next/link";
import { FileText, MessageSquare, ShieldCheck } from "lucide-react";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const FEATURES = [
  {
    icon: FileText,
    title: "Checked, Not Guessed",
    description:
      "Every value has to appear in your report before it is shown, and the low/normal/high flags are worked out in code from the range printed on the page — never taken from the model's word.",
  },
  {
    icon: MessageSquare,
    title: "Ask Follow-Ups",
    description:
      "Ask about your results and get answers grounded in your own report plus reviewed reference pages. Diagnosis and treatment questions get pointed to a clinician.",
  },
  {
    icon: ShieldCheck,
    title: "Private by Default",
    description:
      "Personal details are removed before any text reaches a model, uploads are deleted straight after the text is read, and guest reports disappear after 24 hours.",
  },
] as const;

export default function HomePage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-12 sm:px-6 sm:py-16">
      <section>
        <h1 className="max-w-3xl text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
          Understand What Your Lab Report Actually Says
        </h1>
        <p className="mt-4 max-w-prose text-pretty text-lg leading-relaxed text-muted-foreground">
          Medi&#8209;Scan turns a lab report into plain language: each result with its reference
          range, what is in or out of range, and a summary written at a sixth&#8209;grade reading
          level. No login needed.
        </p>

        {/*
          No upload control yet. An input that accepted a file and then did nothing would be
          worse than not having one — this says where the feature is instead.
        */}
        <p className="mt-8 inline-flex rounded-md border border-dashed border-border px-3 py-1.5 text-xs text-muted-foreground">
          Upload and sample reports arrive in phase 2. Phase 1 set up the foundation: guest
          sessions, the error contract, and the app shell.
        </p>
      </section>

      <section aria-labelledby="how-it-works" className="mt-14">
        <h2 id="how-it-works" className="scroll-mt-20 text-xl font-semibold tracking-tight">
          How It Works
        </h2>

        <ul className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((feature) => (
            <li key={feature.title}>
              <Card className="h-full">
                <CardHeader>
                  <feature.icon aria-hidden="true" className="size-5 text-primary" />
                  <CardTitle className="mt-2 text-base">{feature.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <CardDescription className="text-pretty leading-relaxed">
                    {feature.description}
                  </CardDescription>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="synthetic-only" className="mt-14 max-w-prose">
        <h2 id="synthetic-only" className="scroll-mt-20 text-xl font-semibold tracking-tight">
          Please Use Synthetic Data
        </h2>
        <p className="mt-3 text-pretty leading-relaxed text-muted-foreground">
          This is a portfolio demo, not a medical service. Masking personal details is
          best&#8209;effort and can miss things, and the default AI provider&rsquo;s free tier may
          use what it is sent to improve its models. Use the bundled sample reports or a made-up
          one.{" "}
          <Link
            href="/biomarkers"
            className="rounded-sm text-foreground underline underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            Read the biomarker reference pages
          </Link>{" "}
          to see the kind of explanation it produces.
        </p>
      </section>
    </div>
  );
}
