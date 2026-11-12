# Bank/account-only PayOS refunds implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan in the current session. Steps use checkbox syntax for tracking.

**Goal:** A refund recipient selects a bank and enters an account number, confirms, and the existing PayOS worker dispatches/reconciles the eligible refund without any account-name lookup.

**Architecture:** Keep refund creation, source ownership, durable payout snapshots, leases, idempotency and signed response validation. Replace the two-step lookup/name-confirmation form with one bank/account confirmation. Store NULL for names that were never verified; historical names remain compatible. Use a bundled bank directory for human-readable choices, without a runtime bank-name lookup.

**Tech stack:** Existing Java 21, Spring Boot, Thymeleaf, MySQL 8, Maven/JUnit and Node tests. No new dependency.

**Spec and authorization:** The user selected bank + account → PayOS, explicitly omitting account-holder names/ownership lookup, and requested implementation and complete testing in one session. This document records that selected short design and execution scope; it does not authorize a real transfer with an unspecified recipient/amount.

## Constraints and approach

- Reuse the existing PayOS refund path; do not introduce manual settlement or a second payout method.
- Only the active recipient may confirm their own refund. Customer controls bank/account only, never amount/source/recipient/status.
- No VietQR/CAS lookup call, no name field or fabricated verified name. The bundled bank list contains public routing metadata only.
- Retain optional saved accounts. Widen only `refund_recipient_accounts.account_name` to nullable; its legacy `verified_at` column records confirmation time for new rows and is never presented as provider verification.
- Keep historical lookup adapters/diagnostics isolated from the normal recipient workflow; do not delete private keys or unrelated work.
- Existing one-hour deadline starts on first confirmation and does not reset on edits/retries.
- No automatic resend for UNKNOWN, no bank/account changes after attempts start, and no success from HTTP 200 or submitted status alone.
- Test with disposable MySQL schemas. The primary application data/config must remain unchanged, except a narrowly scoped, backed-up schema widening if needed to run the new saved-account workflow.
- Do not commit/push. Keep provider activation disabled until operational access is verified; no live payout is part of testing.

## Review focus

1. An injected name/amount/source must not override server data or fabricate verified identity.
2. No lookup bean/network invocation may be necessary, including when lookup is unavailable or returns business code 47.
3. Null names must work through saved accounts, payout preparation, context restart and reconciliation.
4. Stale actors, lease/attempt races, CSRF and terminal-state edits must remain rejected under current locks.
5. Bank choices and account strings must validate routing/format while preserving leading zeros; unknown banks cannot be silently accepted.

## Task 1: Recipient workflow and UI

Files: `RefundRecipientForm`, `RefundRecipientService`, `RefundRecipientTransactions`, `RefundController`, new `RefundDestination` and `RefundBankDirectory`, bundled public bank JSON, `payment/refund.html`, recipient tests.

- [x] Add focused failing tests proving confirmation without a name and without lookup, bank choices, authorization/CSRF, tamper protection, and locked destinations.
- [x] Run the focused test and record the expected failures.
- [x] Replace preview/verified-name confirmation with one protected POST, keeping the first deadline and clearing stale names on newly submitted destinations.
- [x] Remove the preview route; bind only bank/account/save option. Render a named bank select and account input, plus the existing explicit confirmation.
- [x] Run the focused tests and update old workflow consumers/tests to the selected contract.

## Task 2: Persistence and payout compatibility

Files: `RefundAccountRepository`, `PayoutTransactions`, `scriptDB.sql`, guarded incremental migration, migration and payout/integration tests, setup docs.

- [x] Add failing tests for a null-name saved destination and a complete signed fake-provider refund/recovery journey without lookup.
- [x] Permit NULL names in saved accounts without weakening nonblank checks for historical non-NULL values; preserve all existing rows.
- [x] Save only bank/account as the recipient declaration. Remove the name prerequisite for PayOS dispatch while retaining snapshot matching and all source/amount checks.
- [x] Verify the migration against a populated original schema and fresh bootstrap, refusal without backup confirmation, and preservation of data.
- [x] Update obsolete name-setting test helpers and docs; run affected suites.

## Task 3: Full verification and runtime

- [x] Obtain independent review of the selected diff while useful verification runs locally; resolve material findings with tests.
- [x] Run every Java test against isolated databases, including the Spring integration tests outside the payment package. Run all Node and launcher tests. Build a fresh artifact.
- [x] Compare primary data/config fingerprints and fixture-schema inventory; verify cleanup.
- [x] If the primary saved-account table needs the tested nullable-name change, create and verify a private exact-table backup, apply only that guarded migration, and verify rows unchanged.
- [x] Restart only the identified CarRent process with the new artifact and unchanged financial activation settings; verify HTTP and the new refund UI on a disposable browser fixture.
- [x] Recheck PayOS with GET only, report actual provider blockers and distinguish local completion from real-money evidence.

Evidence and progress: `.scratch/refund-bank-only-20261112/`.
