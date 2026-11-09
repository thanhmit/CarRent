# P5.8 — Ma trận quyền, biên thời gian và hồi quy

Phạm vi theo [P5.8](phase-5-admin-polish.md#task-p58--ma-trận-quyền-biên-thời-gian-và-hồi-quy). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`. Báo cáo này không đóng P5.9, P8.8 hoặc P4.2-A và không cho phép kích hoạt workflow/provider.

## 1. Cách kiểm chứng

- Dùng source đang làm việc, gồm các thay đổi P5.5–P5.7 chưa commit. Không dùng HEAD sạch thiếu prerequisite làm source kiểm chứng.
- HTTP qua Spring MVC/Security thật trong `PaymentFlowFixture`; DB MySQL/schema ngẫu nhiên do fixture sở hữu. Provider chỉ fake/offline; không mock endpoint nghiệp vụ.
- Ma trận phân biệt role, ownership, trạng thái tài khoản hiện tại, method, CSRF và trạng thái đối tượng. HTTP redirect khi chưa đăng nhập khác lỗi từ chối quyền của tài khoản đã đăng nhập.
- Với GET hoặc request bị từ chối, so sánh snapshot dữ liệu trước/sau; kiểm tra read không đưa việc vào queue và không gọi provider. Mutation hợp lệ dùng số tiền, thời điểm, trạng thái và channel do server quyết định.
- Evidence riêng của đợt ở `.scratch/phase5/p58/`; kết quả của đợt trước chỉ là baseline, không thay lần kiểm chứng source cuối.

## 2. Các biên thời gian phải giữ

| Quy tắc | Hành vi tại biên | Interface/test hiện có để đối chiếu |
| --- | --- | --- |
| QR 15 phút | Hết hạn tại `expiresAt`; GET/polling ẩn QR, không tự ghi expiry hoặc gia hạn | `PaymentIntentTest`; `RentalAdvanceTest`; `SubscriptionOrderTest` |
| Hủy trong 1 giờ sau trả trước | Đúng 1 giờ vẫn miễn phí; lấy thời điểm tiền được ngân hàng ghi nhận | `RentalPolicy`, `RentalPolicyTest`, `RentalPaidCancellationTest` |
| Chờ Owner nhận xe sau AFTER 1 giờ | Chỉ nhắc; không tự ghi nhận trả xe hoặc tạo nợ | `RentalAfterReminderJobTest`, `RentalAfterReturnTest` |
| Nợ 70% sau nhận lại xe 1 giờ | Đúng hạn phát cảnh báo; tạo/hủy QR không đổi hạn nợ hoặc khóa tài khoản | `RentalBalanceTest`, `RentalBalanceDeadlineTest` |
| Hoàn tiền 1 giờ | Hạn bắt đầu từ `readyAt`, giữ qua retry/restart; chờ recipient chưa bắt đầu hạn | `RefundRecipientTest`, `PaymentMaintenanceJobTest`, `PaymentMaintenancePayoutTest` |
| Phí hủy theo 3 ngày lịch | Dùng `Asia/Ho_Chi_Minh`; đúng 3 ngày là mức 50% sau cửa sổ miễn phí, không dùng 72 giờ | `RentalPolicyTest`, `RentalPaidCancellationTest` |
| Gói 30 ngày | Cấp/gia hạn đúng một lần; hết quyền public/admission tại `validUntil`, không xóa booking/lịch sử | `SubscriptionPaymentTest`, `SubscriptionQuotaTest`, `SubscriptionVisibilityTest`, `SubscriptionDowngradeTest` |

## 3. Queue, transaction và phục hồi

- Read payment status đi thẳng vào read transaction, không gọi `PaymentRoutes.execute`. Dashboard, finance, profile và private image reads phải giữ tính chỉ đọc.
- Provider payout chạy ngoài DB transaction (`PayoutWorker` dùng `Propagation.NEVER`), sau bước claim/prepare đã commit. Không giữ car queue trong lúc chờ HTTP provider.
- `UNKNOWN` truy vấn attempt hiện có theo provider ID/reference; không gửi lại create chỉ vì timeout.
- Quota khóa theo Owner và đọc current để hai carId không vượt suất cuối; các Owner khác vẫn tiến triển độc lập.
- Nguồn công việc bền vững là DB inbox/intent/refund/attempt. Mất queue/process memory không được làm mất việc đã commit; fixture restart cần chứng minh recovery và không chuyển tiền lặp.
- Test trọng tâm: `SubscriptionQuotaTest.differentCarsRaceLastSlotUsingCurrentReadsEvenWithOldSnapshot`, `PaymentRecoveryTest`, `PaymentMaintenanceJobTest`, `PaymentMaintenancePayoutTest`, `RentalBalanceDeadlineTest`.

## 4. Tài liệu hiện hành và lịch sử

Hợp đồng hiện hành ở [README kế hoạch](README.md), Phase 4/6/8/5 và D-25A/D-27. Workflow `VIETQR_V1` nhận đủ 30% trước giao, Owner xác nhận hợp đồng/giao xe, trả phần 70% sau nhận lại xe; bất đồng AFTER được lưu nhưng không chặn COMPLETED. Phí hủy dùng ngày lịch Việt Nam.

Các checkpoint Phase 0–3/P4.1 và D-25 cũ là lịch sử/legacy, không phải mệnh lệnh tạo mock payment mới hoặc mở nhận xe chưa thanh toán. Giữ bằng chứng lịch sử; các tham chiếu trang chủ hiện hành phải trỏ P5.7, không gán tính năng đã làm sang P5.8.

## 5. Giới hạn vận hành

Không suy ra human review hoặc live readiness từ test tự động. Production refund destination verifier còn từ chối cho tới khi có hợp đồng lookup không chuyển tiền được xác minh. Provider activation, SePay query/reconciliation, nguồn tiền/phí/tối thiểu, migration vận hành, human review và giao dịch thật vẫn theo [P4.2-A](phase-4-payment.md#task-p42-a--tích-hợp-tài-khoản-thực-tế-và-kiểm-chứng-providerlive--hoãn) và [pilot runbook](phase-8-pilot-runbook.md).

Trong P5.8 không migrate/reset/seed DB ứng dụng, bật flags/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push/merge. P5.9 có checklist và evidence riêng trên source cuối.


## 6. Ma trận HTTP thực thi

Bảng dưới được kiểm tra bằng PaymentAccessAuditTest; kết quả trên source cuối ghi tại bàn giao đợt6. Không suy xác thực tài khoản/provider thật từ fixture.


Executed by `PaymentAccessAuditTest` with `PaymentFlowFixture` / MockMvc and the real Spring Security filter chain. The suite verifies these fixture identities against SQL at startup: Admin=1; booking Owner=2; unrelated Owner=3; booking Customer/payer=4; unrelated Customer=5; anonymous=0. Booking 1 belongs to Customer 4 and Owner 2.

All POST observations below include a valid CSRF token unless the row explicitly describes CSRF/HMAC. `302 login` means the redirect target is checked to end in `/login`. Forbidden responses are 403; private inspection nonmembers get 404. A 302 response from a controller is not by itself proof of success: state assertions distinguish cancellation, pilot rejection and immutable report errors.

## Payment and refund

| Method / route | Admin 1 | Owner 2 | Other Owner 3 | Customer 4 | Other Customer 5 | Anonymous |
|---|---:|---:|---:|---:|---:|---|
| GET `/payment/intents/{customerIntent}` | 403 | 403 | 403 | 200 | 403 | 302 login |
| GET same + `/status` | 403 | 403 | 403 | 200 | 403 | 302 login |
| GET same + `/result` | 403 | 403 | 403 | 200 | 403 | 302 login |
| POST `/payment/obligations/201/attempts` | 403 | 403 | 403 | 302 existing attempt | 403 | 302 login |
| POST `/payment/intents/{customerIntent}/cancel` | 403 | 403 | 403 | 302; CANCELLED | 403 | 302 login |
| GET `/payment/refunds/{customerRefund}` recipient form | 403 | 403 | 403 | 200 | 403 | 302 login |
| POST same + `/recipient/preview` | 403 | 403 | 403 | 200; no DB change | 403 | 302 login |
| POST same + `/recipient` | 403 | 403 | 403 | 302; READY | 403 | 302 login |
| GET same + `/status` financial status | 200 | 200 | 403 | 200 | 403 | 302 login |

The refund status reader permits Admin and the collecting Owner; editing the receiving account remains restricted to the recipient. The allowed preview calls the offline destination verifier exactly once and writes nothing. Confirmation calls it again, writes the server READY/deadline snapshot, and creates no payout attempt. Every denied/read request checks a digest for every table in the disposable schema and checks that it caused no destination-provider call, payout-provider call or payment-domain executor submission.

## Subscription

| Method / route | Admin 1 | Owner 2 | Other Owner 3 | Customer 4 | Other Customer 5 | Anonymous |
|---|---:|---|---|---:|---:|---|
| GET `/owner/subscriptions` | 403 | 200 | 200 | 403 | 403 | 302 login |
| GET `/owner/subscriptions/orders/{owner2Order}` | 403 | 200 | 403 | 403 | 403 | 302 login |
| POST same + `/cancel` | 403 | 302; CANCELLED | 403 | 403 | 403 | 302 login |
| POST `/owner/subscriptions/orders`, `plan=SMALL` | 403 | 302 reuse pending order | 302 pilot rejection | 403 | 403 | 302 login |

Owner 3 may browse the catalog but cannot access Owner 2's order. The fixture pilot selects Owner 2; Owner 3's create request redirects without creating an order or money state. Subscription tests elsewhere cover actual applied entitlements and Owner/Admin refund channels. This matrix enables the real subscription handler and uses only the existing fake ADVANCE handler to create the generic customer payment/refund fixture; it is not rental lifecycle evidence.

## Private inspection images

Fixture: one BEFORE image in a report authored by Owner 2. The upload storage root is JUnit `@TempDir`, never the application upload directory.

| Method / report state | Admin 1 | Owner 2 | Other Owner 3 | Customer 4 | Other Customer 5 | Anonymous |
|---|---:|---|---:|---:|---:|---|
| GET `/bookings/1/inspections/BEFORE/images/{id}`, DRAFT | 404 | 200 | 404 | 404 | 404 | 302 login |
| POST same + `/delete`, DRAFT, nonauthor requests | 404 | author delete covered by existing InspectionPrivacyTest | 404 | 404 | 404 | 302 login |
| GET image, SUBMITTED | 200 | 200 | 404 | 200 | 404 | 302 login |
| POST image + `/delete`, SUBMITTED | 404 | 302 error; unchanged image | 404 | 404 | 404 | 302 login |
| GET `/uploads/{privateStorageKey}` | public mapping has no private file; 404 for anonymous |
| GET `/private-uploads/inspections/{privateStorageKey}` | 404 for authenticated Owner |

The submitted image response is checked byte-for-byte and has `Cache-Control: no-store`. The private image row and temporary file remain after every denied deletion. Report state is seeded to SUBMITTED for access checks; this does not bypass the separate submission-validation tests or claim that a one-image report can be submitted through the application.

## Profile and Admin

| Method / routes | Admin 1 | Owner 2 | Other Owner 3 | Customer 4 | Other Customer 5 | Anonymous |
|---|---:|---:|---:|---:|---:|---|
| GET `/profile`, `/profile/edit`, `/profile/change-password` | 200 | 200 | 200 | 200 | 200 | 302 login |
| POST `/profile/edit`, invalid name/phone | 200 validation | 200 validation | 200 validation | 200 validation | 200 validation | 302 login |
| POST `/profile/change-password`, missing fields | 200 validation | 200 validation | 200 validation | 200 validation | 200 validation | 302 login |
| GET Admin routes below | 200 | 403 | 403 | 403 | 403 | 302 login |
| POST Admin routes below, nonexistent target | 404 authorized target lookup | 403 | 403 | 403 | 403 | 302 login |

Admin GET routes: `/admin/dashboard`, `/admin/users`, `/admin/users/4`, `/admin/cars`, `/admin/cars/1`, `/admin/bookings`, `/admin/bookings/1`, `/admin/finance`.

Admin POST routes: `/admin/cars/999999/approve`, `/admin/cars/999999/reject`, `/admin/cars/999999/activate`, `/admin/cars/999999/deactivate`, `/admin/users/999999/status` with `status=BANNED`. The nonexistent target demonstrates authorization before target lookup without changing real fixture records. Successful mutations, profile protected-field binding and password verification are covered by existing Admin/Profile tests and the new service-seam stale authorization tests.

## CSRF inventory and webhook path/method distinctions

The CSRF test discovers all application controller mappings accepting POST (including unrestricted `@RequestMapping`), removes only `/webhooks/sepay/{channelId}`, adds filter-owned `/login` and `/logout`, and submits every remaining path with both a missing token and an invalid token using an appropriately privileged actor. Both requests must return 403 and leave all schema tables/provider counters/domain task count unchanged. Concrete values replace path variables; phase is BEFORE. This catches future added POST routes without a source-string assertion.

Inventory (40 user POST mappings, mỗi path kiểm tra missing và invalid CSRF):

- `/access-denied`, `/register`, `/login`, `/logout`.
- `/profile/edit`, `/profile/change-password`.
- `/notifications/{id}/read`, `/notifications/read-all`.
- `/bookings`, `/bookings/{id}/cancel`, `/bookings/{id}/accept-before`.
- `/owner/bookings/{id}/{confirm,cancel,complete,handover,return,reject}`.
- `/owner/cars/new`, `/owner/cars/{id}/edit`, `/owner/cars/{id}/images/{imageId}/delete`, `/owner/cars/{id}/{deactivate,delete,activate}`.
- `/admin/cars/{id}/{approve,reject,deactivate,activate}`, `/admin/users/{id}/status`.
- `/payment/obligations/{id}/attempts`, `/payment/intents/{id}/cancel`.
- `/payment/refunds/{id}/recipient/preview`, `/payment/refunds/{id}/recipient`.
- `/owner/subscriptions/orders`, `/owner/subscriptions/orders/{id}/cancel`.
- `/bookings/{bookingId}/inspections/{phase}/{draft,images,submit,addendum,dispute}`, same prefix + `/images/{imageId}/delete`.

| Webhook request, repeated for all six identities | Observation |
|---|---|
| POST `/webhooks/sepay/101`, no CSRF, no HMAC | 401; unchanged DB |
| Exact POST, no CSRF, incorrect HMAC secret | 401; unchanged DB |
| Exact POST, valid HMAC | 200; one durable inbox per unique transaction, no receipt/domain application |
| POST `/webhooks/sepay`, `/webhooks/sepay/`, `/webhooks/sepay/101/`, `/webhooks/sepay/101/extra`, `/webhooks/other/101`; valid HMAC, no CSRF | 403; unchanged DB |
| PUT/PATCH/DELETE `/webhooks/sepay/101`; valid HMAC, no CSRF | 403; unchanged DB |
| PUT/PATCH/DELETE same, valid CSRF + authenticated actor | 405; unchanged DB |
| GET same, authenticated | 405; `Allow: POST`; Vietnamese method error view |
| GET same, anonymous | 302 login |

The unsupported-method checks found the prior catch-all advice returning 500 for an otherwise unsupported method. The dedicated 405 handler preserves the framework Allow header and renders the house error view.

## Tampering and queue evidence

- Checkout HTTP POST supplies forged amount, amountDue, channelId, status, payer/collector IDs, createdAt and expiresAt. Stored amount remains 600000, channel 101, ACTIVE, creation `2026-10-30T03:00:00Z`, expiry `03:15:00Z`. GET query parameters cannot alter these values or the status response.
- Recipient HTTP POST supplies forged amount, payout channel, recipient, status, readyAt and dueAt. Stored values remain 540000, channel 103, recipient 4, READY, server readyAt `03:00:00Z` and dueAt `04:00:00Z`.
- `readPagesCompleteWhileTheirCarCommandLaneIsOccupied` occupies car 1's command lane with a latch, then completes checkout/status/result, Admin booking detail, profile, subscription order detail and BEFORE draft GETs while the original command is still blocked. The full schema digest remains identical. This is direct behavioral evidence that these reads do not wait behind their car lane. Domain-executor counters in the remaining matrix are a separate no-domain-enqueue assertion, not a claim about every car lane.


## 7. Lỗi đã sửa sau regression RED

- Guard mutation xe kiểm tra Owner/Admin hiện tại dưới khóa trước aggregate. Owner dùng WRITE để cùng mutex quota; Admin dùng READ, tương thích FK notification. GET không lấy khóa mutation. Cross-Owner request bị từ chối trước khi chờ mutex Owner khác, vẫn kiểm tra ownership sau khóa. RED20/21 trước sửa; các lượt lỗi setup/cleanup ghi riêng trong task-report.md.
- Vòng chờ Owner-create giữ Owner WRITE rồi notification cần Admin SHARE, trong khi Admin status giữ Admin WRITE rồi chờ Owner: regression tạo deadlock thực ở cả subscription bật/tắt. Giữ thứ tự User-ID tăng dần, đổi riêng authorization actor Admin sang READ và target status giữ WRITE. RED2/2 trước sửa; focused45/45 gồm test Admin chặn lẫn nhau, stale Admin và quota.
- Method webhook không hỗ trợ trước đây rơi catch-all500. Handler405 riêng giữ Allow: POST và view tiếng Việt; CSRF vẫn trả403 khi unsafe method thiếu token.

Review độc lập đã phát hiện vòng khóa và yêu cầu sửa; báo cáo finding cũ được giữ với bằng chứng trước/sau trong .scratch/phase5/p58/. Kết quả source cuối gồm full/browser nằm ở bàn giao đợt6, không suy pass toàn bộ từ focused.
