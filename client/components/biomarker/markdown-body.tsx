import Markdown from "react-markdown";

import { headingId } from "@/lib/biomarker-content";

/**
 * The body of a curated page, rendered from markdown.
 *
 * Three independent guards, because this content is rendered into a page whose origin holds
 * tokens in browser storage:
 *
 * 1. `skipHtml` drops raw HTML nodes rather than showing them as text. (react-markdown does
 *    not render HTML by default — this removes it entirely.)
 * 2. `allowedElements` is a whitelist. Anything a future edit introduces that is not on the
 *    list does not render, instead of rendering unstyled.
 * 3. `urlTransform` narrows links to `https:` and site-relative paths. The default already
 *    blocks `javascript:`, but it also permits `irc:`, `xmpp:` and `mailto:`, none of which
 *    this content has any business using.
 *
 * `dangerouslySetInnerHTML` appears nowhere, here or anywhere else (root CLAUDE.md).
 */

const ALLOWED = ["h2", "h3", "p", "ul", "ol", "li", "strong", "em", "a", "code", "blockquote"];

function safeUrl(url: string): string {
  if (url.startsWith("/")) return url;
  return url.startsWith("https://") ? url : "";
}

export function MarkdownBody({ markdown }: { markdown: string }) {
  return (
    <div className="flex flex-col gap-space-5">
      <Markdown
        skipHtml
        allowedElements={ALLOWED}
        urlTransform={safeUrl}
        components={{
          // Ids come from the same function the page index uses, so the anchors agree.
          h2: ({ children }) => (
            <h2
              id={headingId(String(children))}
              className="t-title scroll-mt-24 pt-space-7 text-ink first:pt-0"
            >
              {children}
            </h2>
          ),
          h3: ({ children }) => <h3 className="t-body mt-space-5 text-ink">{children}</h3>,
          p: ({ children }) => <p className="t-body max-w-[68ch] text-ink-2">{children}</p>,
          ul: ({ children }) => <ul className="flex flex-col">{children}</ul>,
          ol: ({ children }) => <ol className="flex flex-col">{children}</ol>,
          li: ({ children }) => (
            <li className="t-body border-t border-line py-space-4 text-ink-2">{children}</li>
          ),
          /*
           * The design system bans bold for emphasis in running text and asks for a colour
           * change instead. The mockup shows emphasised phrases, and markdown spells those
           * `**like this**` — so `strong` renders at normal weight in `ink` against the
           * `ink-2` body. Both rules are kept: idiomatic markdown, design-system styling.
           */
          strong: ({ children }) => <strong className="font-normal text-ink">{children}</strong>,
          a: ({ href, children }) => (
            <a
              href={href}
              className="text-accent-ink underline underline-offset-4"
              {...(href?.startsWith("https://")
                ? { target: "_blank", rel: "noreferrer noopener" }
                : {})}
            >
              {children}
            </a>
          ),
        }}
      >
        {markdown}
      </Markdown>
    </div>
  );
}
