/**
 * Generates `app/styles/tokens.css` from `docs/design-system/tokens.json`.
 *
 * The design system is the source of truth for every colour, space, radius, shadow, blur and
 * type style (root CLAUDE.md, "Frontend"). Hand-copying those values into CSS would let the
 * two drift silently, so they are generated, the result is committed, and a unit test fails
 * the build if the committed file no longer matches the JSON.
 *
 * Run with `npm run tokens`.
 */

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export const TOKENS_JSON = resolve(here, "../../docs/design-system/tokens.json");
export const TOKENS_CSS = resolve(here, "../app/styles/tokens.css");
export const THEME_COLORS_TS = resolve(here, "../app/styles/theme-colors.ts");

/**
 * Maps a design-system font family onto the CSS variable `next/font` generates for it.
 *
 * The stacks in tokens.json name the webfont directly (`"Geist", Helvetica, …`), which is how
 * the mockups loaded it from Google Fonts. We self-host through `next/font` instead (LLD F4),
 * so the first family is swapped for the generated variable and the fallbacks are kept.
 */
const FONT_VARIABLES = {
  '"Geist"': "var(--font-geist-sans)",
  '"Geist Mono"': "var(--font-geist-mono)",
};

function toNextFontStack(stack) {
  for (const [family, variable] of Object.entries(FONT_VARIABLES)) {
    if (stack.startsWith(family)) return variable + stack.slice(family.length);
  }
  return stack;
}

const BANNER = `/*
 * Generated from docs/design-system/tokens.json by scripts/build-tokens.mjs.
 * Do not edit by hand — run \`npm run tokens\`.
 *
 * This is the only file in the client that holds colour values. Everything else reads these
 * variables, directly or through a Tailwind utility mapped in globals.css.
 */`;

function block(selector, lines) {
  // Separator lines stay genuinely empty: an indented blank line is trailing whitespace, which
  // editors and formatters strip, which would then fail the staleness test for no real reason.
  const body = lines.map((l) => (l === "" ? "" : `  ${l}`)).join("\n");
  return `${selector} {\n${body}\n}`;
}

/** Builds the stylesheet in memory. Exported so the staleness test can compare without I/O. */
export function buildTokensCss(tokens = JSON.parse(readFileSync(TOKENS_JSON, "utf8"))) {
  const themed = tokens.color.tokens;
  const shadows = tokens.shadow.tokens;

  // `:root` carries dark as well, so a document with no `data-theme` yet — the moment before
  // the theme script runs — is already dark rather than unstyled.
  const dark = [
    ...themed.map((t) => `--${t.name}: ${t.value.dark};`),
    ...shadows.map((t) => `--${t.name}: ${t.value.dark};`),
  ];
  const light = [
    ...themed.map((t) => `--${t.name}: ${t.value.light};`),
    ...shadows.map((t) => `--${t.name}: ${t.value.light};`),
  ];

  const scalars = [
    ...tokens.spacing.tokens.map((t) => `--${t.name}: ${t.value};`),
    "",
    ...tokens.radius.tokens.map((t) => `--${t.name}: ${t.value};`),
    "",
    ...tokens.blur.tokens.map((t) => `--${t.name}: ${t.value};`),
    "",
    `--font-sans: ${toNextFontStack(tokens.type.families.sans)};`,
    `--font-mono: ${toNextFontStack(tokens.type.families.mono)};`,
  ];

  // One variable per part of a type style, so a utility class can assemble them and a
  // component can override just the size without restating weight and tracking.
  const type = [];
  for (const group of tokens.type.groups) {
    for (const style of group.styles) {
      type.push(`--${style.name}-size: ${style.fontSize};`);
      type.push(`--${style.name}-line: ${style.lineHeight};`);
      type.push(`--${style.name}-weight: ${style.fontWeight};`);
      if (style.letterSpacing) type.push(`--${style.name}-track: ${style.letterSpacing};`);
      type.push("");
    }
  }
  type.pop();

  return [
    BANNER,
    "",
    block(":root,\n[data-theme=\"dark\"]", dark),
    "",
    block('[data-theme="light"]', light),
    "",
    "/* Theme-independent: spacing, shape, blur and the font stacks. */",
    block(":root", scalars),
    "",
    "/* Type styles, as parts. Assembled into `.t-*` classes in globals.css. */",
    block(":root", type),
    "",
  ].join("\n");
}

/**
 * Builds `app/styles/theme-colors.ts`.
 *
 * `<meta name="theme-color">` is read by the browser chrome before any stylesheet is parsed,
 * so it has to be a literal colour — `var(--bg)` means nothing there. Generating it is what
 * keeps the browser chrome matching the page ground without a second hand-copied hex value
 * sitting in layout.tsx.
 */
export function buildThemeColorsTs(tokens = JSON.parse(readFileSync(TOKENS_JSON, "utf8"))) {
  const bg = tokens.color.tokens.find((t) => t.name === "bg");
  if (!bg) throw new Error("tokens.json has no `bg` colour token");

  return `/**
 * Generated from docs/design-system/tokens.json by scripts/build-tokens.mjs.
 * Do not edit by hand — run \`npm run tokens\`.
 *
 * The page ground per theme, for \`<meta name="theme-color">\`. That tag is read before any
 * CSS is parsed, so it needs a literal value rather than a token reference.
 */

export const themeColors = {
  dark: "${bg.value.dark}",
  light: "${bg.value.light}",
} as const;
`;
}

function main() {
  mkdirSync(dirname(TOKENS_CSS), { recursive: true });

  writeFileSync(TOKENS_CSS, buildTokensCss(), "utf8");
  console.log(`Wrote ${TOKENS_CSS}`);

  writeFileSync(THEME_COLORS_TS, buildThemeColorsTs(), "utf8");
  console.log(`Wrote ${THEME_COLORS_TS}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
