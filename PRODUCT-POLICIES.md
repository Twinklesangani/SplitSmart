# Product data policies

## Dashboard

Group expenses means the sum of all recorded expense totals in the authenticated user's active groups. Each group is counted once. It is not the user's personal share. Debts and receivables are summed separately from the existing group balance calculation. Archived groups are excluded. Activity shows the ten most recently recorded expenses and repayments in active groups.

## Expense dates and categories

An expense date is an ISO calendar date, without a timezone or time of day. New API requests that omit it default to today's date in UTC. Updates that omit it preserve the existing date. Future dates are rejected against UTC. Displaying a date never shifts it to a browser timezone.

V6 backfills from the existing recorded creation timestamp's calendar date. This is the best available genuine record, not a claim about when the underlying purchase happened. Run MySQL migrations with the session timezone set to UTC so historical TIMESTAMP values are interpreted consistently. V1–V4 are unchanged. Old stored idempotency responses may lack the new optional fields and remain valid replay outcomes.

Categories default to Other. No category is inferred from historical descriptions. An omitted category on update preserves its existing value.

## Group history

Only the creator can remove another member or delete a group. A member may leave only when their balance is zero and they have no expense, split, or settlement history. Historical members are retained even after repayment to preserve attribution and existing balance invariants. The creator cannot leave or be removed, including when they are the last member.

Deleting a group archives it after all member balances reach zero. The group, memberships, expenses, settlements and invitations are preserved. Archived groups remain readable by their members, and financial or membership mutations are rejected. The owner can archive an empty last-member group. No ownership transfer is performed.

V5 contains the existing invitation feature. V6 adds expense metadata. V7 adds group archival. Applying these migrations to hosted infrastructure requires separate approval before deployment.
