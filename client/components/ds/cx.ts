type ClassValue = string | false | null | undefined;

/**
 * Joins the class names that survive, dropping the falsy ones.
 *
 * The design-system components compose fixed `ms-*` class names rather than Tailwind
 * utilities, so there is nothing for `tailwind-merge` to resolve here and no reason to pull
 * `cn()` in. Same helper as the design system's own bundle.
 */
export function cx(...values: ClassValue[]): string {
  return values.filter(Boolean).join(" ");
}
