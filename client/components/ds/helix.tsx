"use client";

import type { CSSProperties } from "react";
import dynamic from "next/dynamic";

export interface HelixProps {
  width?: number;
  height?: number;
  seed?: number;
  style?: CSSProperties;
}

/**
 * Client-only wrapper for the helix (LLD F9).
 *
 * Several hundred absolutely-positioned spans have no business in the server HTML: they are
 * decoration, they would be the largest thing in the document, and they would be streamed to
 * every visitor before any text. `ssr: false` keeps them out of it entirely — and because
 * nothing here is content, there is no placeholder and nothing to wait for.
 *
 * Under `prefers-reduced-motion` ds.css stops the drift and the twinkle, leaving the static
 * picture the design system allows.
 */
const HelixDots = dynamic(() => import("./helix-dots").then((m) => m.HelixDots), {
  ssr: false,
});

export function Helix({ width = 560, height = 860, seed = 7, style }: HelixProps) {
  return <HelixDots width={width} height={height} seed={seed} style={style} />;
}
