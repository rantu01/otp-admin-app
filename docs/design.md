# NesaAdmin: Design System

## Direction

A quiet operations console optimized for scanning and decisions. Use deep cobalt for navigation and primary actions, slate for hierarchy, soft blue for selected surfaces, emerald for approved actions, amber for pending work, and red only for rejection or destructive actions.

## Tokens

- Brand: `header_start` / `#243B8F`, `accent_blue` / `#3157C7`, `tab_active` / `#3157C7`.
- Canvas and surfaces: `screen_bg` / `#F5F7FB`, `card_bg` / `#FFFFFF`, `info_bg` / `#EEF3FF`.
- Text: `title_text` / `#172033`, `muted_text` / `#6B7280`, `stroke` / `#D8DFEC`.
- Status: `pill_pending_*` for review queues, `pill_ok_*` for confirmed states, `pill_bad_*` for rejected or blocked states.

## Type, spacing, and layout

Use the platform sans family with strong 20-24sp screen titles and 12-14sp dense row text. Use 8dp spacing increments and 12-16dp content gutters. Runtime cards use a consistent 16dp inset and 12dp vertical separation. Wide layouts may align numeric columns, while phone layouts stack the same information in scan order.

## Elevation

Use border-first surfaces and minimal elevation. The header may use a solid brand fill; avoid ornamental gradients and oversized dashboard cards. Status pills are compact, high-contrast, and always include text.
