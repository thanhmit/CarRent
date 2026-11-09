# Local SePay environment setup implementation plan

**Goal:** Upgrade the authorized local `carrent` schema without changing existing data, load private configuration from `.env`, and provide an explicit command that creates disabled collection channels and prints their real webhook URLs after the user supplies bank details.

**Architecture:** Keep `ddl-auto=none` and manually apply the five existing migrations after restoring and verifying a fresh backup. Import `.env` using Spring's properties file hint. Use an opt-in Spring profile for transactional, idempotent collection channel provisioning; normal application startup never provisions channels.

**Tech stack:** Java 21, Spring Boot 4.1.1, MySQL 8, Maven Wrapper, PowerShell.

**Spec:** User authorization in this session to migrate local DB and create channels, with sensitive fields filled by the user in `.env`; `docs/local-ngrok-sepay-setup.md`; `docs/migrations/README.md`.

## Constraints

- Preserve the dirty working tree, legacy column values, password hashes, timestamps and existing IDs.
- Keep secrets out of tracked files, logs, process arguments and this plan.
- Keep financial schedulers and live payout channels disabled.
- Bank/account fields are required by the existing schema even for disabled COLLECT rows. An empty `.env` cannot produce valid channel IDs.
- Owner/Admin use the same bank account but separate internal collector channels. Shared webhook routing and payment-code length compatibility remain integration work; do not claim this provisioning command completes those flows.

## Tasks

- [x] Save baseline hashes/status under ignored `.scratch/sepay-local-setup-20261107/`; stop the app for a stable backup.
- [x] Dump `carrent`; restore to a unique `carrent_sepay_rehearsal_<hex>` schema. Compare every original column ordered by primary key before running any migration.
- [x] Apply payment-core, refund-accounts, rental-workflow, cancellation-reason and owner-subscriptions in dependency order using their backup confirmation variables. Compare original columns and ensure added tables are empty.
- [x] Repeat the approved migrations against `carrent`; compare original data again and retain backup/evidence.
- [x] Write failing tests for empty config, valid shared-bank provisioning, disabled rows, idempotent reruns and rejection of account/collector changes without partial inserts.
- [x] Add `LocalSePaySetup` under profile `sepay-setup`, consuming `CARRENT_SEPAY_BANK_BIN`, `CARRENT_SEPAY_ACCOUNT_NUMBER`, `CARRENT_SEPAY_OWNER_ID`, `CARRENT_SEPAY_ADMIN_ID`, `CARRENT_PUBLIC_BASE_URL`. Validate before writes, lock collectors, create `owner-live` / `admin-live` disabled channels transactionally, and print only IDs, role and URL.
- [x] Import optional `.env[.properties]` and add `.env.example`. Create `.env` with empty private fields, keeping existing local configuration intact.
- [x] Run focused MySQL tests and the provisioning CLI against a disposable fixture; verify missing `.env` values leave application channels empty. Restart normal app and verify local/ngrok HTTP connectivity.
- [x] Update the local setup guide and record migrated schema/evidence and remaining user inputs. Retain changes locally for review.


## Result ? 2026-11-07

- Applied all five migrations to local `carrent` after fresh backup, restore verification and rehearsal. All original columns/rows across seven legacy tables retained their hashes. All thirteen new tables remain empty.
- Backup and detailed evidence are private under `.scratch/sepay-local-setup-20261107/`; verified generated rehearsal/smoke schemas were removed.
- Added `.env.example`, created ignored `.env` with only collector IDs and ngrok origin prefilled, and kept bank BIN/account/HMAC empty for the user. Existing MySQL credentials and unrelated dirty files were preserved.
- Added explicit `sepay-setup` profile and transactional provisioning. New rows are disabled, reruns retain IDs, mismatches roll back without repointing channels. Empty private bank fields deliberately leave the application channel table empty.
- RED: 11 assertions failed before implementation. GREEN: 11/11 new tests; bounded migration/provider/inbox regression 124/124, no failures/errors/skips.
- Real CLI smoke against an isolated schema loaded collector/domain values from `.env`, created two disabled fixture channels, exited successfully, and left application channels untouched.
- Restarted the normal app with financial/domain schedulers disabled. Local and ngrok home GET both returned HTTP 200; an unsigned POST to nonexistent channel 0 returned HTTP 401 without financial work.
- User action: fill `CARRENT_SEPAY_BANK_BIN` and `CARRENT_SEPAY_ACCOUNT_NUMBER` in `.env`, then run the documented provisioning command to obtain real channel URLs. `CARRENT_SEPAY_WEBHOOK_SECRET` can be supplied after creating the HMAC webhook. No real channel ID is claimed before those bank inputs exist.
- Shared-bank routing and payment-code length compatibility remain pending. No webhook/provider/payout activation, real transfer, commit or push was performed.


## C?p nh?t ti?p theo ? 2026-11-07

Ng?n h?ng ?? ???c cung c?p ri?ng; provisioning local t?o SePay OWNER channel 3, ADMIN channel 4 (??u t?t), m?t URL chung `/webhooks/sepay/3`. M? CR + 30 k? t?, ??nh tuy?n t?i kho?n chung v? profile ch? thu ti?n ?? tri?n khai. C?c tr?ng th?i pending/bank tr?ng trong m?c Result ph?a tr?n l? snapshot tr??c ??t t?ch h?p n?y. Xem [b?n giao SePay](sepay-local-2026-11-07-handoff.md) ?? ti?p t?c; kh?ng ch?y l?i migration.
