import { readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative, resolve, sep } from "node:path";

import { describe, expect, it } from "vitest";

import {
  buildThemeColorsTs,
  buildTokensCss,
  THEME_COLORS_TS,
  TOKENS_CSS,
} from "../scripts/build-tokens.mjs";

const CLIENT = resolve(__dirname, "..");

/** Line endings differ between a fresh checkout and a local write; the content is what matters. */
function normalise(css: string): string {
  return css.replace(/\r\n/g, "\n");
}

describe("generated tokens", () => {
  it("app/styles/tokens.css is up to date with docs/design-system/tokens.json", () => {
    const onDisk = normalise(readFileSync(TOKENS_CSS, "utf8"));
    const fromSource = normalise(buildTokensCss());

    expect(
      onDisk,
      "tokens.css is stale. Run `npm run tokens` and commit the result.",
    ).toBe(fromSource);
  });

  it("app/styles/theme-colors.ts is up to date", () => {
    const onDisk = normalise(readFileSync(THEME_COLORS_TS, "utf8"));
    const fromSource = normalise(buildThemeColorsTs());

    expect(
      onDisk,
      "theme-colors.ts is stale. Run `npm run tokens` and commit the result.",
    ).toBe(fromSource);
  });
});

/**
 * Colour literals, in the forms that actually turn up: #abc, #aabbcc, #aabbccdd, and the
 * functional notations. Anchors like `/#how` do not match — the hex form requires the whole
 * run to be hex digits and to end at a non-word character.
 */
const COLOUR_LITERAL =
  /(#[0-9a-fA-F]{3,4}\b|#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{8}\b|\b(?:rgba?|hsla?|oklch|oklab|lab|lch|color-mix)\s*\()/;

const SCANNED_EXTENSIONS = new Set([".ts", ".tsx", ".css"]);

/**
 * The one file allowed to hold colour values (root CLAUDE.md, "Frontend"), because it is
 * generated from the design system rather than written.
 */
const ALLOWED = new Set([
  join("app", "styles", "tokens.css"),
  // Generated from the same source for `<meta name="theme-color">`, which cannot take a
  // variable. Kept honest by the staleness test above rather than by hand.
  join("app", "styles", "theme-colors.ts"),
]);

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === "node_modules" || entry === ".next") continue;
      walk(full, out);
    } else if (SCANNED_EXTENSIONS.has(extname(entry))) {
      out.push(full);
    }
  }
  return out;
}

describe("no raw colours", () => {
  it("every colour in app/ and components/ comes from a token", () => {
    const roots = ["app", "components"]
      .map((d) => join(CLIENT, d))
      .filter((d) => {
        try {
          return statSync(d).isDirectory();
        } catch {
          return false;
        }
      });

    const offenders: string[] = [];

    for (const file of roots.flatMap((root) => walk(root))) {
      const rel = relative(CLIENT, file);
      if (ALLOWED.has(rel)) continue;

      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, i) => {
          // `color-mix(in oklch, var(--helix-violet) …)` mixes two tokens and names no colour
          // of its own, which is how the design system draws the helix gradient.
          if (line.includes("color-mix(in oklch, var(--")) return;
          if (COLOUR_LITERAL.test(line)) {
            offenders.push(`${rel.split(sep).join("/")}:${i + 1}  ${line.trim()}`);
          }
        });
    }

    expect(
      offenders,
      `Raw colour values found. Use a token from docs/design-system/tokens.json:\n${offenders.join("\n")}`,
    ).toEqual([]);
  });
});

/**
 * Utilities that look like design-system tokens but do not exist in Tailwind.
 *
 * `rounded-pill` is the trap this catches: the design system calls the token
 * `--radius-pill`, and `ds.css` consumes it as `var(--radius-pill)`, so writing
 * `rounded-pill` in a component reads as obviously right. Tailwind's class for it is
 * `rounded-full`, and an unknown utility compiles to nothing at all — the corner silently
 * stays square and no build step complains.
 *
 * The radius scale itself is fine: `tokens.css` redefines `--radius-sm/md/lg/xl` on `:root`,
 * so `rounded-md` really does resolve to the design system's 20px.
 */
const DEAD_UTILITIES: Array<{ name: string; instead: string }> = [
  { name: "rounded-pill", instead: "rounded-full" },
  { name: "shadow-cta", instead: "shadow-[var(--shadow-cta)]" },
  { name: "backdrop-blur-bar", instead: "backdrop-blur-[var(--blur-bar)]" },
  { name: "blur-bar", instead: "blur-[var(--blur-bar)]" },
];

/**
 * Matches the bare class only.
 *
 * The lookarounds keep `backdrop-blur-[var(--blur-bar)]` out of it: there, `blur-bar` is
 * preceded by a hyphen and is part of the variable's name, which is the correct way to
 * reach the token.
 */
function classPattern(name: string): RegExp {
  return new RegExp(`(?<![\\w-])${name}(?![\\w-])`);
}

describe("no utilities that compile to nothing", () => {
  it("components use Tailwind's name, not the design system's, for radius and effects", () => {
    const offenders: string[] = [];

    for (const root of ["app", "components"]) {
      for (const file of walk(join(CLIENT, root))) {
        const rel = relative(CLIENT, file).split(sep).join("/");
        // ds.css is the port of the design system's own stylesheet and uses the variables
        // directly, which is correct there.
        if (rel.endsWith("styles/ds.css")) continue;

        readFileSync(file, "utf8")
          .split("\n")
          .forEach((line, i) => {
            for (const { name, instead } of DEAD_UTILITIES) {
              if (classPattern(name).test(line)) {
                offenders.push(`${rel}:${i + 1}  ${line.trim()}  → use ${instead}`);
              }
            }
          });
      }
    }

    expect(
      offenders,
      `These classes compile to nothing:\n${offenders.join("\n")}`,
    ).toEqual([]);
  });
});
