# NesaAdmin: Component Rules

## Naming

XML IDs use lower camel case (`pendingBadge`, `refreshBtn`). Runtime labels use sentence case except for compact navigation labels. Shared colors and shapes belong in `res/values` and `res/drawable`; do not hard-code new colors in Java.

## Atomic components

- **Header:** brand, current section, pending count, and quick actions.
- **Navigation tab:** filled cobalt active state, quiet surface inactive state, 48dp touch target.
- **Metric:** label above value, aligned numerals, and no unnecessary illustration.
- **Data card/row:** identity, key amount/status, timestamp, and the next action in a predictable order.
- **Status pill:** semantic background and text, rendered by the shared helper.
- **Mutation action:** explicit label, busy state, confirmation where destructive, and server refresh afterward.

Keep rendering helpers small and reusable. Do not duplicate status color logic in individual screens. New operational surfaces must cover loading, empty, search-empty, error, disabled, and success states while preserving the existing API and listener contracts.
