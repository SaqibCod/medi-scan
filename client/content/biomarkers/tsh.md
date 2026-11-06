---
slug: tsh
title: TSH (Thyroid Stimulating Hormone)
aliases:
  - TSH
  - Thyroid Stimulating Hormone
  - Thyrotropin
summary: A pituitary hormone that tells the thyroid how much thyroid hormone to make.
draft: true
reviewedOn: null
sources: []
---

<!--
  STRUCTURE EXAMPLE — not real content. Replace the body before clearing `draft: true`.

  Why the headings matter: this same folder is the backend's RAG index, and chunks are split
  by heading (docs/dataflow.md 9.1). A heading is what the chat answer cites as its source, so
  each one needs to name a question a reader would actually ask, and its section needs to be
  answerable on its own without the heading above it.

  Keep these five headings and this order across every biomarker page. Consistency is what
  lets retrieval compare like with like, and it is why "Overview" is a bad heading and
  "What TSH Measures" is a good one.

  Front matter:
    slug        must match the filename and the backend's biomarkerSlug
    aliases     names as they appear on real reports, for matching extracted test names
    summary     one sentence; used as the meta description and the card blurb
    draft       true hides the page from the index and from the RAG index
    reviewedOn  ISO date; set when a human has checked it against the sources
    sources     reputable references, required before draft can be cleared
-->

## What TSH Measures

What the marker is, in one short paragraph, and what the body uses it for. Plain language at
roughly a sixth-grade reading level: short sentences, no jargon that is not immediately
explained.

Note the counter-intuitive part here, since it is the single most common misreading of this
panel: TSH goes **up** when the thyroid is **under**active, because the pituitary is pushing
harder.

## Why It Gets Tested

The reasons a clinician orders it, and what it is usually ordered alongside — for TSH, free T4
and sometimes free T3.

## What the Numbers Mean

How to read a result against the reference range printed on the report, and why that range is
the one that matters rather than any number quoted here.

State explicitly that reference ranges differ between laboratories, and that pregnancy and age
shift them. Do not put a specific numeric range in this file: the app reads the range off the
user's own report, and a hardcoded one here could contradict it.

## What Can Move It

Common, non-alarming reasons a value sits outside the range — recent illness, time of day,
medication, pregnancy, biotin supplements interfering with the assay.

The point of this section is to keep a single out-of-range value from reading as a diagnosis.

## When to Talk to a Clinician

Close on the limits of this page: it explains what a test measures, it does not interpret
anyone's results. Say plainly that a clinician reads these numbers together with symptoms and
history, and that this page is not a substitute for that.

No diagnosis, no treatment advice, and no "normal"/"abnormal" verdicts anywhere on the page.
