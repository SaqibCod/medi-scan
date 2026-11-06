import type { Metadata } from "next";

import { NotFoundScreen } from "@/components/system/screens";

export const metadata: Metadata = {
  title: "Page not found",
};

export default function NotFound() {
  return <NotFoundScreen />;
}
