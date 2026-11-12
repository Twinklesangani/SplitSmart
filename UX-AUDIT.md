# UX audit — 12 November 2026

Active application tree audit-output/m1a, baseline a8663cd. Separate offline demo excluded. Existing pending preservation/email documentation retained.

Verified local navigation issues: medium: group members only appear below overview content with no group-section entry; reproduce by opening group tabs, looking for Members. Fixed with Members deep link. Medium: hash links depend on browser scroll timing despite asynchronous group loading; focus was not transferred to the target. Fixed explicit restricted hash handling and focusable member/invite sections. Medium: client route navigation leaves keyboard focus on the old navigation control; fixed main-content focus on pathname changes. Low: main Overview and group Overview labels do not distinguish global context; renamed main link Dashboard.

Existing routes cover login/register/recovery, groups, group overview/member management/invites, expense list/create/detail/edit, balances and settlements. Notifications remain a bell/panel; do not invent separate Activity/Settings destinations without implemented backing functionality. No profile editing route currently exists; this is a product gap, not a broken existing route. Live login previously lacked the local recovery link; this is deployment divergence, not missing local implementation.

Remaining audit: actual synthetic registration-to-settlement journeys, notification deep links, account isolation, cold starts, mobile/desktop and live comparison need fresh scoped execution before claiming this full milestone complete. No real/customer data used.
Confirmed mobile regression: at375px the Members section link was outside the viewport in the horizontal tab strip (real backend responsive test, offscreenControls=[Members]). Fixed group tabs to wrap instead of horizontal scrolling. Full affected responsive journey PASS at375/768/1024/1440. Existing profile editing/Settings route absent; no unsupported navigation control added.
