import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merges class names, with later Tailwind utilities beating earlier conflicting ones.
 *
 * `clsx` flattens conditionals and arrays; `twMerge` then resolves Tailwind conflicts, so
 * `cn("p-2", "p-4")` gives `"p-4"` rather than both. That is what makes a `className` prop
 * able to override a component's own defaults.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
