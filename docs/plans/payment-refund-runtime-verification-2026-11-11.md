# Payment and refund runtime verification — 2026-11-11

## Conclusion

CarRent has been rebuilt and restarted at <http://localhost:8080>. Real-money payment and refund readiness is **not 100% verified**. PayOS rejects the backend IP, VietQR rejects account lookup under the configured plan, and no controlled bank payment/refund with reconciliation was performed.

Evidence is local under `.scratch/restart-payment-check-20261111/`. Private provider outputs must not be published without redaction.

## Restart and runtime

- Offline Maven package completed successfully before restart.
- Restarted only the identified CarRent process: PID 37268 → 30820.
- Runtime uses the copied immutable `carrent-runtime.jar`, SHA256 `5ED4D37A54A70E8552A649F18AA06D20D732B2F47A4CCB6590F0B714AAC385C7`.
- Consolidated local properties are loaded; payout worker, PayOS payout channels, and live recipient lookup remain disabled.
- HTTP checks: **14/14 passed**, including public pages, documented seed Admin login, finance/refund pages, authentication, role denial, CSRF denial, and rejection of unsigned SePay webhook requests.
- The first HTTP harness incorrectly checked `/admin` instead of `/admin/dashboard`; the corrected harness passed. The initial result is retained separately.
- Public HTTPS tunnel matches the configured origin, forwards to port 8080, and serves CarRent `/cars` successfully.
- A separate Playwright browser smoke check rendered `/cars` correctly (`cars-browser.png`). Its console reported a missing favicon (404) and the existing Tailwind CDN production warning; neither establishes a financial-flow failure. Authenticated finance checks above remain HTTP HTML/API checks, not a complete visual checkout/refund journey or proof of bank settlement.

## Local financial tests

The fresh run selects all 73 payment-package test classes explicitly. Database fixtures use disposable `carrent_p43_*` schemas. Three Spring tests outside this package that could use the primary datasource are excluded.

The full run completed **1,051 tests across 73 suites: 1,049 passed, 2 failed, 0 errors, 0 skips**. Both failures were in `PaymentPilotConfigurationTest`: the real root `application-local.properties` contaminated tests that expect disabled defaults and no selected participants.

The first attempted fix restricted configuration search to the classpath, but the targeted rerun still failed both assertions. A config-loading TRACE run proved that Spring processes imports per document: the packaged `application.properties` continued importing the root local file despite the command-line import. Both failed targeted runs are preserved (`financial-config-retest.log`, `financial-config-trace.log`).

The final test-only fix loads a temporary copy of the packaged defaults with its import directed only to the temporary test dotenv. All existing assertions are preserved. The affected suite rerun passed **3/3**, 0 failures/errors/skips (`financial-config-fixed.log`, `financial-config-fixed.xml`). Combining the unchanged 72 suite reports with the corrected suite gives **1,051/1,051 latest-per-suite results**. This is explicitly **not a second single full green run**. Original reports remain in `financial-original-surefire/`; independently checked totals are in `final-verification-summary.json`.

No application code or live configuration was changed by this fix; the restarted runtime artifact therefore does not need another rebuild/restart.

JavaScript tests: **18/18 passed**, no failures/skips (`node-tests-summary.json`).

Relevant covered scenarios include payment intent/receipt processing, webhook verification/deduplication, refund recipient confirmation and changed-name rejection, role/channel ownership, payout persistence, concurrency, crash/restart recovery, and reconciliation without another payout POST after an uncertain result. Provider adapters use local test doubles; these results cannot establish provider entitlement or real transfers.

## Live provider checks

| Gate | Fresh observation | Required action |
| --- | --- | --- |
| PayOS Owner payout access | `IP_DENIED` | Allowlist the current backend public IPv4 on the correct payout channel; confirm activation and payout permission. |
| PayOS Admin payout access | `IP_DENIED` | Verify the corresponding Admin channel/funding mapping and IP access. |
| VietQR recipient lookup | HTTP 200, business code `47`: `The Free Plan will no longer support from August 20, 2024.` | Activate/obtain an entitled lookup plan or confirm access with VietQR support, then repeat a non-transfer lookup. |
| Real SePay collection | Tunnel and local guards passed; no new live settlement tested | Perform an agreed controlled payment and verify authenticated delivery, one receipt, and correct allocation. |
| Real refund | No transfer sent; zero refund/payout-attempt rows in the pre-test application snapshot | After provider gates pass, define recipient/amount/funding scope, run a controlled refund, and reconcile provider and database results. |

VietQR credentials belong to the CarRent operator. Customers supply the destination bank/account and confirm the independently returned account name; they do not need a VietQR account. A typed account name cannot replace provider verification. PayOS beta features are not proof of a sandbox or fake-money mode.

Do not enable lookup/payout merely because keys exist. Keep `UNKNOWN` payouts query/reconcile-only, and never send another transfer to resolve uncertainty.

## Data preservation

The pre/post-test snapshots confirm **all 20 application tables unchanged**, both private configuration files unchanged, and **zero new fixture schemas remaining**. The 9 fixture schemas that already existed before this run remain untouched. Application financial rows are unchanged: 5 obligations, 5 intents, 1 webhook inbox entry, 1 receipt, 0 refunds, 0 payout attempts. No migrations, reseeding, commits, pushes, or live payout creation were performed.
