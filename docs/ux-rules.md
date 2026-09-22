# NesaAdmin: UX Rules

- Keep the pending queue visible from the main surface and make Payments a first-class navigation target.
- All controls are at least 48dp high with readable labels and accessible contrast.
- Never use color alone for pending, approved, rejected, disabled, or expired states.
- Refresh, polling, and mutation states must be visible without causing layout jumps.
- Approve, reject, disable, delete, and withdraw actions require confirmation with the affected user, amount, or balance stated.
- Reject requires a reason. Over-balance withdrawals are blocked or explicitly warned.
- Distinguish no pending records from no search results. Preserve the query when recovering from an error.
- Backend failures show a retry path and never claim a mutation succeeded.
- Toasts confirm short actions such as copied transaction IDs; inline messages explain durable errors.
- Respect safe-area insets and keep horizontally scrolling navigation keyboard and touch accessible.
