import type { Metadata } from "next";

import { AccountView } from "@/components/account/account-view";
import { PageContainer } from "@/components/shell/page-container";

/**
 * The account page.
 *
 * `noindex`, like every page that only means something to one person. The title carries no
 * name: a page title reaches browser history and the window switcher, and the display name is
 * the one piece of identity this app holds.
 */
export const metadata: Metadata = {
  title: "Your account",
  robots: { index: false, follow: false },
};

export default function AccountPage() {
  return (
    <PageContainer>
      <AccountView />
    </PageContainer>
  );
}
