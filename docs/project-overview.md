# NesaAdmin: Project Overview

## Architecture

NesaAdmin is a native Android operations console sharing the backend with the customer app. `AdminAuthActivity` handles role-gated sign-in. `AdminMainActivity` owns tab selection, polling, notifications, and programmatic rendering. `AdminApi` is the network boundary; `AdminSession` persists authenticated state; `UiBusy` standardizes mutation loading.

## Operational flows

The admin signs in, lands on Dashboard, and can move through payment review, received payments, users, packages, payment methods, profit, withdrawals, and versions. Pending payment counts poll in the background and create a notification when the queue grows. Review actions approve or reject through backend calls and refresh the rendered tab.

## State management

The selected tab and rendered data are held by `AdminMainActivity`; authentication is persisted by `AdminSession`. Network work uses an executor and returns to the main looper before mutating views. The UI is server-authoritative: approval, access, status, and availability are never inferred solely from local controls.

## UI contract

Runtime-created controls depend on stable IDs in the root layouts and shared resource tokens. Preserve button listeners, tab names, endpoint paths, polling, notification behavior, and confirmation dialogs while refining styling helpers and resources.
