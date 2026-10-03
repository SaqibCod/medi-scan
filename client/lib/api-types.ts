/**
 * Shared API types, copied from `docs/api-contract.md` section 9.1.
 *
 * The contract is the source of truth. Any change to an endpoint, field, enum or error code
 * must update the contract and this file in the same change; if they disagree, that is a bug
 * in one of them, not a detail to work around.
 *
 * Compatibility rules (contract section 9.2):
 * - Adding an optional field, endpoint, enum value or error code is compatible.
 * - Removing or renaming a field, changing a type, or changing what a status code means is
 *   breaking: bump the contract version and update this file in the same commit.
 * - Unknown enum values: treat an unknown `flag` as `UNKNOWN`, an unknown `status` as
 *   `PROCESSING` (keep polling), and an unknown error `code` as a generic error.
 */

export type ReportStatus = "PENDING" | "PROCESSING" | "DONE" | "FAILED";
export type SourceType = "PDF" | "IMAGE" | "TEXT" | "SAMPLE";
export type Flag = "LOW" | "NORMAL" | "HIGH" | "UNKNOWN";

export interface SessionResponse {
  token: string;
  expiresAt: string;
}

export interface Sample {
  id: string;
  title: string;
  description: string;
  markerCount: number;
}

export interface CreateReportResponse {
  id: string;
  status: ReportStatus;
  sourceType: SourceType;
  createdAt: string;
  expiresAt: string;
}

export interface Biomarker {
  id: string;
  testName: string;
  rawValue: string;
  numericValue: number | null;
  unit: string | null;
  referenceRangeText: string | null;
  refLow: number | null;
  refHigh: number | null;
  flag: Flag;
  biomarkerSlug: string | null; // links to /biomarkers/{slug} when a curated page exists
}

export interface ReportResult {
  collectedOn: string | null; // YYYY-MM-DD
  biomarkers: Biomarker[];
  summary: string;
  highlights: string[];
  counts: { total: number; low: number; normal: number; high: number; unknown: number };
}

export interface ReportError {
  code: string;
  message: string;
}

export interface ReportResponse extends CreateReportResponse {
  error: ReportError | null; // set only when status is FAILED
  result: ReportResult | null; // set only when status is DONE
}

export interface ChatTurn {
  role: "user" | "assistant";
  content: string;
}

export interface ChatRequest {
  question: string;
  history?: ChatTurn[];
}

export type ChatEvent =
  | { event: "meta"; data: { answerId: string; cached: boolean } }
  | { event: "token"; data: { text: string } }
  | { event: "sources"; data: { report: boolean; pages: { slug: string; title: string; heading: string }[] } }
  | { event: "done"; data: { finishReason: "COMPLETE" | "DECLINED" | "NOT_IN_CONTEXT" } }
  | { event: "error"; data: { code: string; message: string } };

// ---- Auth ----

export type Role = "USER" | "ADMIN";

export interface User {
  id: string;
  displayName: string;
  role: Role;
  createdAt: string;
}

export interface Me extends User {
  reportCount: number;
}

export interface GoogleSignInRequest {
  idToken: string;
  guestSessionToken?: string;
}

export interface TokenResponse {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: User;
}

export interface GoogleSignInResponse extends TokenResponse {
  claimedReportCount: number;
  newUser: boolean;
}

export interface RefreshRequest {
  refreshToken: string;
}

// ---- History ----

export type FlagCounts = { total: number; low: number; normal: number; high: number; unknown: number };

export interface ReportListItem {
  id: string;
  status: ReportStatus;
  sourceType: SourceType;
  createdAt: string;
  expiresAt: string;
  collectedOn: string | null;
  counts: FlagCounts | null;
  errorCode: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// ---- Trends ----

export interface TrendPoint {
  reportId: string;
  date: string; // YYYY-MM-DD
  dateSource: "COLLECTED" | "UPLOADED";
  value: number;
  refLow: number | null;
  refHigh: number | null;
  flag: Flag;
}

export interface TrendResponse {
  marker: string;
  displayName: string;
  series: { unit: string | null; points: TrendPoint[] }[];
}

export interface TrendMarker {
  marker: string;
  displayName: string;
  reportCount: number;
}

// ---- Admin ----

export interface AdminStats {
  from: string;
  to: string;
  dailyLlmCap: number;
  days: {
    date: string;
    llmCalls: number;
    inputTokens: number;
    outputTokens: number;
    reportsCreated: number;
    reportsFailed: number;
    rateLimitRejections: number;
    maskingConflicts: number;
  }[];
  failuresByCode: Record<string, number>;
  avgProcessingMsBySource: Partial<Record<SourceType, number>>;
  users: { total: number; activeLast7Days: number };
  activeReports: { guest: number; user: number };
}

// ---- Errors ----

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  requestId: string;
  errors?: { field: string; message: string }[];
}
