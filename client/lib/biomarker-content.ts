import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { parse as parseYaml } from "yaml";

/**
 * Loading the curated biomarker pages.
 *
 * Server-only. Read and validated once per server instance and held in memory (B1): 21 small
 * files parsed on every request would be pointless work under a CSP that already forces
 * per-request rendering.
 *
 * Validation is hand-written rather than schema-library work, matching `lib/env.ts` and
 * `lib/guest-session.ts`. The schema is fixed and eleven fields wide, and what actually
 * matters is that a bad file fails loudly with its own name in the message — a content
 * mistake should stop the build, not reach a reader.
 *
 * The same markdown feeds the backend's RAG index (`docs/dataflow.md` 9.1), which splits on
 * the H2 headings, so the heading set is a contract rather than a layout choice.
 */

const CONTENT_DIR = join(process.cwd(), "content", "biomarkers");

/**
 * The headings every page carries, in this order.
 *
 * Five slots with fixed roles: what the test is, the result in one direction, the result in
 * the other, what disturbs it, and what to ask. Slots 2 and 3 take either the quantitative
 * wording ("What high LDL means") or the qualitative one ("What a positive result means"),
 * because a test reported as positive or negative has no high or low to describe. The LLD
 * allows the qualitative form in slot 3 only; a test like thyroid antibodies needs it in
 * both, and the structure the RAG index depends on is unchanged either way.
 */
export const REQUIRED_HEADINGS = [
  /^What it measures$/,
  /^(What high .+ means|What a positive result means)$/,
  /^(What low .+ means|What a negative result means)$/,
  /^What can affect the result$/,
  /^Questions to ask your doctor$/,
] as const;

export interface BiomarkerSource {
  title: string;
  url: string;
}

export interface BiomarkerHeading {
  id: string;
  title: string;
}

export interface BiomarkerPage {
  slug: string;
  title: string;
  panel: string;
  aliases: string[];
  measures: string;
  usualUnits: string;
  usuallyPartOf: string;
  related: string[];
  sources: BiomarkerSource[];
  /** `null` until a person has checked the page against its sources. */
  reviewedOn: string | null;
  reviewer: string | null;
  /** The markdown body, frontmatter removed. */
  body: string;
  headings: BiomarkerHeading[];
}

interface IndexEntry {
  slug: string;
  displayName: string;
  aliases: string[];
}

function fail(file: string, message: string): never {
  throw new Error(`content/biomarkers/${file}: ${message}`);
}

function requireString(file: string, data: Record<string, unknown>, key: string): string {
  const value = data[key];
  if (typeof value !== "string" || value.trim() === "") {
    fail(file, `frontmatter "${key}" must be a non-empty string`);
  }
  return value;
}

function requireStringArray(file: string, data: Record<string, unknown>, key: string): string[] {
  const value = data[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    fail(file, `frontmatter "${key}" must be an array of strings`);
  }
  return value as string[];
}

/** `null` or a date. Never a name this app put there: nothing in the app marks a page reviewed. */
function optionalString(file: string, data: Record<string, unknown>, key: string): string | null {
  const value = data[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") fail(file, `frontmatter "${key}" must be a string or null`);
  return value;
}

function requireSources(file: string, data: Record<string, unknown>): BiomarkerSource[] {
  const value = data.sources;
  if (!Array.isArray(value) || value.length === 0) {
    fail(file, `frontmatter "sources" must list at least one source`);
  }

  return value.map((entry, i) => {
    if (typeof entry !== "object" || entry === null) {
      fail(file, `sources[${i}] must be an object with a title and a url`);
    }
    const { title, url } = entry as Record<string, unknown>;
    if (typeof title !== "string" || title.trim() === "") {
      fail(file, `sources[${i}].title must be a non-empty string`);
    }
    // Rendered as a link on the page, so an http source would be a mixed-content warning at
    // best and a tampered citation at worst.
    if (typeof url !== "string" || !url.startsWith("https://")) {
      fail(file, `sources[${i}].url must be an https URL`);
    }
    return { title, url };
  });
}

const FRONTMATTER = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/;

function splitFrontmatter(file: string, raw: string): { data: Record<string, unknown>; body: string } {
  const match = FRONTMATTER.exec(raw.replace(/^﻿/, ""));
  if (!match) fail(file, "missing the --- frontmatter block at the top of the file");

  let parsed: unknown;
  try {
    parsed = parseYaml(match[1]);
  } catch (error) {
    fail(file, `frontmatter is not valid YAML: ${(error as Error).message}`);
  }

  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    fail(file, "frontmatter must be a block of key: value pairs");
  }

  return { data: parsed as Record<string, unknown>, body: match[2] };
}

/** Stable ids for "On this page", derived from the heading text. */
export function headingId(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

/** The H2s, in document order. Fenced code is skipped, though none of the content uses it. */
function readHeadings(file: string, body: string): BiomarkerHeading[] {
  const headings: BiomarkerHeading[] = [];
  let inFence = false;

  for (const line of body.split("\n")) {
    if (line.startsWith("```")) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;

    const match = /^##\s+(.+?)\s*$/.exec(line);
    if (match) headings.push({ id: headingId(match[1]), title: match[1] });
  }

  if (headings.length !== REQUIRED_HEADINGS.length) {
    fail(
      file,
      `expected ${REQUIRED_HEADINGS.length} H2 headings, found ${headings.length}: ` +
        headings.map((h) => `"${h.title}"`).join(", "),
    );
  }

  REQUIRED_HEADINGS.forEach((pattern, i) => {
    if (!pattern.test(headings[i].title)) {
      fail(file, `heading ${i + 1} is "${headings[i].title}", which does not match ${pattern}`);
    }
  });

  return headings;
}

function parsePage(file: string, raw: string): BiomarkerPage {
  const { data, body } = splitFrontmatter(file, raw);

  const slug = requireString(file, data, "slug");
  if (`${slug}.md` !== file) fail(file, `frontmatter slug "${slug}" does not match the file name`);

  return {
    slug,
    title: requireString(file, data, "title"),
    panel: requireString(file, data, "panel"),
    aliases: requireStringArray(file, data, "aliases"),
    measures: requireString(file, data, "measures"),
    usualUnits: requireString(file, data, "usualUnits"),
    usuallyPartOf: requireString(file, data, "usuallyPartOf"),
    related: requireStringArray(file, data, "related"),
    sources: requireSources(file, data),
    reviewedOn: optionalString(file, data, "reviewedOn"),
    reviewer: optionalString(file, data, "reviewer"),
    body: body.trim(),
    headings: readHeadings(file, body),
  };
}

/** The slugs the backend also knows about. Adding one here without adding it there breaks RAG. */
function readIndex(): IndexEntry[] {
  const raw = readFileSync(join(CONTENT_DIR, "index.json"), "utf8");
  return JSON.parse(raw) as IndexEntry[];
}

function loadAll(): Map<string, BiomarkerPage> {
  const index = readIndex();
  const known = new Set(index.map((entry) => entry.slug));

  const files = readdirSync(CONTENT_DIR).filter((name) => name.endsWith(".md"));
  const pages = new Map<string, BiomarkerPage>();

  for (const file of files) {
    const page = parsePage(file, readFileSync(join(CONTENT_DIR, file), "utf8"));

    if (!known.has(page.slug)) {
      fail(file, `slug "${page.slug}" is not in index.json, which the backend also reads`);
    }
    pages.set(page.slug, page);
  }

  for (const entry of index) {
    if (!pages.has(entry.slug)) {
      throw new Error(
        `content/biomarkers: index.json lists "${entry.slug}" but ${entry.slug}.md does not exist`,
      );
    }
  }

  // Only checkable once every page is loaded. A "related test" linking nowhere is a 404 the
  // reader walks into from a page that looked finished.
  for (const page of pages.values()) {
    for (const related of page.related) {
      if (!pages.has(related)) {
        fail(`${page.slug}.md`, `related slug "${related}" has no page`);
      }
      if (related === page.slug) fail(`${page.slug}.md`, `related lists the page itself`);
    }
  }

  return pages;
}

/**
 * Parsed once per server instance.
 *
 * Module scope lives for the life of the process, so the files are read on the first request
 * and never again.
 */
let cache: Map<string, BiomarkerPage> | null = null;

function pages(): Map<string, BiomarkerPage> {
  cache ??= loadAll();
  return cache;
}

export function getBiomarkerPage(slug: string): BiomarkerPage | null {
  return pages().get(slug) ?? null;
}

/** Every page, ordered as `index.json` orders them, which groups panels together. */
export function getAllBiomarkerPages(): BiomarkerPage[] {
  const all = pages();
  return readIndex()
    .map((entry) => all.get(entry.slug))
    .filter((page): page is BiomarkerPage => page !== undefined);
}

export function getBiomarkerSlugs(): string[] {
  return [...pages().keys()];
}

/** Tests only: drops the cache so a case can load a modified fixture. */
export function resetBiomarkerCacheForTests(): void {
  cache = null;
}
