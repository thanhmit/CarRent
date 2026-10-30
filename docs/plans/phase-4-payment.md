# CarRent — Phase 4: Thanh toán thật và hoàn tiền — Implementation Plan

> **For agentic workers:** Dùng superpowers:executing-plans theo task, checkbox phản ánh kết quả thật. Không tự stage/commit/push. Chỉ dùng subagent khi được yêu cầu.

**Ngày cập nhật:** 2026-10-30. **Trạng thái:** P4.1, P4.2 offline và P4.3–P4.8 đã hoàn thành/kiểm chứng nền/offline trên DB riêng. Full clean verify **344 test đạt**; browser thông báo/hoàn tiền desktop/mobile đạt. DB ứng dụng chưa migration; P4.2-A vẫn chờ tài khoản/provider/lookup đích/live. **Tiếp theo đợt 6/P4.9 + P4.10**. Tiến độ **52/86**, còn 34; chưa có live pass hoặc human review mới.

**Goal:** Tích hợp SePay VietQR và API chi hộ payOS (nguồn tiền ví Bảo Kim) với ghi nhận tiền, đối soát và hoàn tự động có khả năng phục hồi sau restart.

**Architecture:** Tách nghĩa vụ phải trả, lần tạo QR, tiền ngân hàng đã nhận và nghĩa vụ hoàn. Webhook được xác thực/lưu inbox trước ACK; worker áp dụng một lần dưới khóa DB, qua Car queue khi liên quan xe. Lệnh chi được lưu trước gọi API và đối soát độc lập với trình duyệt.

**Tech Stack:** Java 21, Spring Boot 4.1.x MVC/JPA, MySQL 8, Thymeleaf, RestClient có sẵn từ Spring; không thêm broker hoặc SDK khi HTTP client hiện có đủ dùng.

**Spec:** [README — hợp đồng mới](README.md), đặc biệt mục 2–6. Thay nội dung mock payment chưa triển khai của plan cũ; Phase 6/8 nối nghiệp vụ vào nền này.

**Phân công:** `thanhmit` phụ trách toàn bộ task mới; `huy69332` review. Xem [phân công nhóm](team-assignment.md).

## 7 đợt triển khai — cập nhật được duyệt 2026-10-30

Nguồn duyệt: SDD §14.1 và chấp thuận trong chat 2026-10-30: gộp P4.4 + P4.5 + P4.6 cùng phiên. Năm đợt đầu đã hoàn thành nền/offline; còn hai phiên theo bảng dưới và P4.2-A theo điều kiện tài khoản. Giữ nguyên ID/checklist từng task; gộp phiên không đồng nghĩa gộp điều kiện hoàn thành.

| Đợt | Task | Checkpoint |
|---|---|---|
| 1 | P4.2 — hoàn thành offline | Contract kỹ thuật từ nguồn chính thức, adapter/test offline phù hợp; các gate tài khoản/provider/live chuyển sang P4.2-A. |
| 2 | P4.3 — hoàn thành trên DB kiểm chứng | Schema/mapping/constraint và migration bảo toàn legacy; DB ứng dụng chưa migration. |
| 3 | P4.4 + P4.5 + P4.6 | Ba checkpoint riêng: QR/checkout/quyền → inbox commit trước ACK → receipt apply một lần, lease và khóa aggregate. Handler fixture, chưa bật checkout domain thật. |
| 4 | P4.7 | Refund/recipient đúng quyền, nguồn tiền, amount và deadline; chưa dispatch. |
| 5 | P4.8 | Dispatch/reconcile, hai worker, timeout/crash và idempotency; giữ độc lập. |
| 6 | P4.9 + P4.10 | Scheduler/restart/cảnh báo và UI trạng thái/quyền tích hợp. |
| 7 | P4.11 | Build/hồi quy/race hai connection, bảo toàn legacy, tài liệu và handoff; phân biệt offline/live. |

**Cập nhật phạm vi được chủ dự án duyệt:** ban đầu 8 đợt ngày 2026-10-29; ngày 2026-10-30 gộp P4.4 + P4.5 + P4.6 thành đợt 3, còn 7 đợt phát triển offline. P4.2-A là task riêng theo điều kiện tài khoản, không chặn phát triển offline nhưng phải đạt trước khi bật live. Checkout domain vẫn tắt khi Phase 6/8 chưa có handler thật. Từng task giữ checkpoint/test riêng; test gọi processor trực tiếp và restart không thay job tự quét toàn luồng ở P4.9/P4.11. Không tự stage/commit/push. Lịch sử thay đổi bộ đếm 45/85 → 46/86 → 47/86 được giữ ở checkpoint cũ; đợt 3 hoàn thành ba task đưa tiến độ lên 50/86, không suy ra live readiness.

## Global Constraints

- Chạy sau [Phase 7](phase-7-car-queue.md), trước Phase 6/8; không bật checkout mới cho người dùng khi các handler nghiệp vụ chưa được kiểm chứng.
- Tiền thuê vào Owner, phí gói vào Admin. Kênh COLLECT và PAYOUT thuộc đúng chủ thể, không dùng nguồn tiền Admin hoàn thay Owner.
- Một Owner thật + Admin trước. Credential reference phía server; không in token/secret hoặc đưa vào HTML/QR/polling.
- Incoming bank webhook chỉ ghi nhận tiền vào. Kết quả chi dùng API payout; không dùng debit webhook chung để chứng minh Customer đã nhận.
- Payout tiền thật phụ thuộc nhà cung cấp kích hoạt. Mọi trạng thái/provider signature phải kiểm chứng theo tài liệu chính thức; không đoán mapping từ tên trạng thái approval của batch.
- Giữ legacy payments/seed; bỏ yêu cầu “một booking chỉ có một PAID” đối với workflow mới bằng mô hình nghĩa vụ mới, không sửa hàng lịch sử.
- CSRF cho POST người dùng; endpoint webhook cụ thể xác thực provider. Mọi GET chỉ đọc.
- Áp dụng khóa, retry, mốc thời gian, backlog và gate tại [README](README.md).

## Map file

Đường dẫn Java dưới đây tương đối với `src/main/java/com/carrent/`; test dưới `src/test/java/com/carrent/payment/`.

| Nhóm | Create / Modify khi thực hiện |
|---|---|
| Migration | `docs/migrations/2026-10-27-01-payment-core.sql`, `2026-10-30-01-refund-accounts.sql`, `scriptDB.sql`; không chạy DDL trong lần viết plan |
| Model | `payment/PaymentChannel.java`, `PaymentObligation.java`, `PaymentIntent.java`, `PaymentReceipt.java`, `WebhookInbox.java`, `Refund.java`, `PayoutAttempt.java` và repository/enums tương ứng |
| Provider | `payment/provider/SePayWebhookVerifier.java`, `PayOsPayoutClient.java`, `HttpPayOsPayoutClient.java`, `PayOsSignature.java`, `ProviderPaymentProperties.java`, `PayoutCommand.java`, `PayoutResult.java`, `TransferOutcome.java` |
| Cấu hình / fixture P4.2 | `config/PaymentProviderConfiguration.java`, `application-local.properties.example`; `SePayWebhookVerifierTest.java`, `ProviderPaymentPropertiesTest.java`, `PayOsPayoutClientTest.java`, `src/test/resources/payment/sepay-incoming.json`, `payos-payouts.json` |
| Service | `payment/PaymentObligationService.java`, `SePayWebhookService.java`, `ReceiptProcessor.java`, `ReceiptApplicationService.java`, `RefundService.java`, `RefundRecipientService.java`, `PayoutWorker.java`, `PayoutTransactions.java`, `PayoutChannelResolver.java`, `PaymentMaintenanceJob.java` |
| Hợp đồng domain | `payment/PaymentDomainHandler.java`, `PaymentSubject.java`, `PaymentIntentView.java`, `RefundRecipientForm.java`, `RefundReason.java` |
| MVC/UI | `payment/PaymentController.java`, `SePayWebhookController.java`, `RefundController.java`; `config/SecurityConfig.java`; templates `payment/checkout.html`, `result.html`, `refund.html`; `static/js/payment-polling.js` |
| Test | `PaymentMigrationTest.java`, `RefundServiceTest.java`, `RefundRecipientTest.java`, `RefundAccountMigrationTest.java`, `PaymentIntentTest.java`, `SePayInboxTest.java`, `ReceiptApplicationTest.java`, `PayoutWorkerTest.java`, `PaymentSecurityTest.java` |

## Checkpoint lịch sử P4.1 — giữ nguyên nội dung đã kiểm chứng

Hợp đồng sau mô tả legacy; repository cũ không dùng để suy ra đã đủ 30% + 70% cho booking VIETQR_V1.

### Task P4.1: Payment entity + enums + repo

**Files:**
- Create: `src/main/java/com/carrent/payment/Payment.java`, `PaymentStatus.java`, `PaymentMethod.java`, `PaymentRepository.java`.

**Interfaces:**
- Produces: `Payment` per SDD §7.2 payments: `@ManyToOne Booking` (FK booking_id, KHÔNG unique — D-06); amount Long; method enum; status enum; transactionReference String ≤50; paidAt LocalDateTime nullable; timestamps.
- Produces: `PaymentStatus` = {PENDING, PAID, FAILED, REFUNDED}; `PaymentMethod` = {MOCK_CARD, MOCK_BANK_TRANSFER, CASH_SIMULATED}.
- Produces: `PaymentRepository`:
  - `@Modifying(flushAutomatically = true) @Query("update Payment p set p.status = com.carrent.payment.PaymentStatus.REFUNDED where p.booking.id = :bookingId and p.status = com.carrent.payment.PaymentStatus.PAID") int refundByBookingId(@Param("bookingId") Long bookingId)` — dùng tại P3.8 sau khóa Car/Booking/Payment, reload managed Payment sau bulk update, không clear toàn context.
  - `@Lock(PESSIMISTIC_WRITE)` + `@Query("select p from Payment p where p.booking.id = :bookingId order by p.id") List<Payment> findByBookingIdForUpdate(@Param("bookingId") Long bookingId)` — current read cho guard PAID/refund, khóa Payment sau Car/Booking; refresh/reload entity đã managed theo D-13.
  - `List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId)`
  - `boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status)` (hiển thị đã PAID; mutation dùng current locking read ở trên).

- [x] **Step 1: Viết entity + 2 enum + repo.**
- [x] **Step 2: Verify** — boot đọc đúng PAID/REFUNDED seed; DESCRIBE/SHOW CREATE TABLE khớp schema, booking_id FK không unique; query current lock là FOR UPDATE. Trên fixture trong transaction rollback: giữ pending Booking update, bulk refund đổi PAID→REFUNDED, managed Payment reload thấy REFUNDED, amount/paid_at giữ nguyên; rollback giữ baseline. Đánh dấu P4.1 sau checkpoint đợt 3, không triển khai PaymentService/UI ở task này.

**Đã thực hiện/verify ngày 2026-10-11 trong đợt 3 Phase 3.** 118 assertions mapping/repository/MVC + 117 HTTP + 47 browser đọc đúng payment seed, bulk refund rollback và baseline đạt; SHOW CREATE TABLE FK BIGINT không unique, SQL lock `FOR UPDATE`. Xem mục kết quả đợt 3 trong [plan Phase 3](phase-3-booking.md). P4.1 không chạy lại; Phase 3 đã hoàn tất cả 5 đợt, tiếp theo P4.2–P4.6 (chưa triển khai).

## Các task mới — P4.2 offline hoàn thành, P4.2-A hoãn theo tài khoản

### Task P4.2 — Contract provider và adapter/test offline — hoàn thành

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** Create provider interfaces/properties ở map file; ghi kết quả tại cuối plan này. Nguồn: [SePay webhooks](https://developer.sepay.vn/vi/sepay-webhooks), [payOS API](https://payos.vn/docs/api/), [kênh chi](https://payos.vn/dang-ky-dich-vu-chi-ho/).

**Cập nhật phạm vi đã được chủ dự án duyệt:** P4.2 chỉ gồm contract kỹ thuật và adapter/test offline; cấu hình và điều kiện tài khoản/provider/live chuyển nguyên sang **P4.2-A**, thực hiện sau khi đăng ký/kích hoạt tài khoản. SDK/plugin chưa cần; dùng HMAC JDK cho request vào và `RestClient` sẵn có cho API ra. Chủ dự án đã chốt API **payOS** cho hoàn tiền, ví Bảo Kim làm nguồn tiền; không tích hợp API chi hộ Bảo Kim trực tiếp. Xem [mapping provider](payment-provider-contract.md); hoàn thành P4.2 không phải live pass hoặc human review của contributor khác.

- [x] SePay offline: cấu hình theo credential reference, mặc định không có kênh hoạt động; verifier HMAC trên raw bytes, kiểm tra ±5 phút, từ chối header bảo mật lặp, secret sai/khác kênh. Chưa mở endpoint nhận tiền.
- [x] Fixture UTF-8 đã loại dữ liệu thật; test verifier/configuration, không gọi ngân hàng hoặc provider thật. Chi tiết bằng chứng ở checkpoint cuối plan.

- [x] Đọc hợp đồng provider hiện hành và lập bảng xác thực, transaction ID/múi giờ, payout state/idempotency/recovery tại [provider contract](payment-provider-contract.md). Đợt 1 ngày 2026-10-29 phân biệt điều có tài liệu và điều chưa xác minh; phí/tối thiểu, equality ID SePay và quyền kênh thật chuyển sang P4.2-A, chưa phải live pass.
- [x] Định nghĩa client và records có kiểu; thêm lookup theo reference để phục hồi mất response tạo:

```java
public interface PayOsPayoutClient {
    PayoutResult create(Long payoutChannelId, PayoutCommand command);
    PayoutResult get(Long payoutChannelId, String providerPayoutId);
    PayoutResult findByReference(Long payoutChannelId, String referenceId);
}
public record PayoutCommand(String referenceId, String idempotencyKey,
                     String bankBin, String accountNumber,
                     long amount, String description) {}
public record PayoutResult(String providerPayoutId, String referenceId, TransferOutcome outcome,
                    String bankBin, String accountNumber, long amount) {}
public enum TransferOutcome { PROCESSING, SUCCEEDED, NOT_SENT, UNKNOWN }
```

Mỗi public type ở file tương ứng trong map file. payoutChannelId do server lấy từ refund, dùng credential của đúng Owner/Admin; không nhận kênh chi từ form người nhận. Mapping NOT_SENT chỉ dùng khi provider bảo đảm chưa chuyển. Không lấy approvalState làm bằng chứng SUCCEEDED. Kết quả đã xác thực trả referenceId để worker so với attempt bền vững; fallback UNKNOWN không chứng minh dữ liệu response đúng hoặc tiền chưa gửi.

- [x] Test provider: `SePayWebhookVerifierTest`, `ProviderPaymentPropertiesTest`, `PayOsPayoutClientTest` kiểm tra chữ ký, state, timeout và request/response sai. `SePayInboxTest`/`PayoutWorkerTest` thuộc các task inbox/worker sau. Bằng chứng ở checkpoint payOS cuối plan.
- [x] Mọi test provider tự động dùng server stub/fixture; không gửi lệnh chi thật từ test suite.

**Hoàn thành theo phạm vi mới:** contract kỹ thuật + verifier/client/records/configuration + test offline có bằng chứng tại checkpoint đợt 1. Những kết luận 45/85 và P4.2 chưa hoàn tất trong checkpoint trước lần tách là lịch sử, không bị viết lại thành live pass.

### Task P4.2-A — Tích hợp tài khoản thực tế và kiểm chứng provider/live — hoãn

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Trạng thái:** task mới theo yêu cầu chủ dự án, **chưa triển khai**. Chỉ bắt đầu sau khi chủ dự án báo đã đăng ký/kích hoạt đủ tài khoản; nhận định hạng mục có vẻ đủ không phải bằng chứng activation, credential hoặc live pass. Không chặn P4.3–P4.10 phát triển offline.

**Đầu vào:** một Owner và Admin, mỗi chủ thể có ngân hàng thu SePay, webhook secret và API token truy vấn; kênh chi payOS và bộ client-id/api-key/checksum-key; ví nguồn Bảo Kim liên kết đúng chủ thể, tiền nguồn/credit dịch vụ phù hợp. Credential chỉ ở cấu hình local/biến môi trường; không đưa key/OTP/mật khẩu vào tài liệu, fixture hoặc log.

**Phụ thuộc kỹ thuật:** cấu hình và kiểm tra quyền có thể làm sau khi đủ tài khoản; webhook vào CarRent cần P4.3/P4.5, dispatch/reconcile cần P4.7/P4.8, phục hồi theo job cần P4.9. Phần phụ thuộc chưa có phải giữ pending, không thay bằng mock rồi đánh dấu live pass. P4.11 tổng hợp gate; E2E rental/gói thật còn phụ thuộc handler Phase 6/8.

**Files khi thực hiện:** `application-local.properties`/biến môi trường (không commit), cấu hình payment_channels sau P4.3, các verifier/client/service/controller/job/test thực sự cần sửa theo kết quả provider; bằng chứng redacted tại provider contract/checkpoint. Không tạo stub implementation cho task này trong lần tách plan.

- [ ] Kiểm tra một bộ thu/chi của Owner và một bộ riêng của Admin đã được provider kích hoạt, đúng chủ thể và quyền API chi hộ; không dùng credential kênh thu payOS thay kênh chi.
- [ ] Cấu hình credential references, tài khoản/collector, webhook tiền vào và public HTTPS URL/tunnel sau P4.5; cấu hình IP egress allowlist nếu provider yêu cầu. Giữ tách Test/Live và mặc định tắt cho chủ thể chưa được duyệt.
- [ ] Kiểm chứng cùng giao dịch SePay: webhook.id/API v1.id, account/sub-account, chiều, amount, thời gian và quyền query. Chưa có alias thì không ingest UUID v2 hoặc khớp mờ để hợp nhất giao dịch.
- [ ] Xác minh retention/replay/idempotency và bảo đảm chưa chuyển cho NOT_SENT bằng tài liệu/xác nhận provider; kiểm chứng timeout/lost response/recovery an toàn. Nếu provider không bảo đảm, giữ UNKNOWN/đối soát và ghi rõ phần không được retry tự động.
- [ ] Xác nhận phí/VAT, hạn mức, số tiền tối thiểu (kể cả hoàn 5.000 VND), credit dịch vụ chi và tiền nguồn ví Bảo Kim. Xác minh khả năng tự động chi và điều kiện duyệt lệnh trên kênh thực tế.
- [ ] Xác minh cách kiểm tra tên/tài khoản nhận trước dispatch cho P4.7; không dùng payout create để lookup hoặc coi tên người nhận tự khai là provider xác minh.
- [ ] Nối cấu hình thật và sửa adapter/validation/mapping khi có bằng chứng khác contract offline; cập nhật test redacted rồi chạy lại kiểm chứng phù hợp.
- [ ] Kiểm chứng webhook Test mode qua durable inbox, duplicate/retry/ACK và recovery; thử payout/đối soát trên môi trường provider cho phép. Mỗi kiểm chứng live có khoản tiền/kênh/đích được người dùng chủ động xác định; không gửi tiền lúc boot/test suite.
- [ ] Ghi bảng evidence cho từng Owner/Admin và Test/Live, kết quả và giới hạn; reviewer kiểm tra. Không đánh dấu live-ready khi còn capability bắt buộc chưa xác minh. P4.11/Phase 6/8 vẫn có gate tích hợp riêng.

**Hoàn thành khi:** các cấu hình/quyền/capability cần dùng có bằng chứng, tích hợp thật trong phạm vi đã có code được kiểm chứng, không còn gate bắt buộc chưa giải quyết cho luồng live dự định bật. Tài khoản đã đăng ký hoặc test fixture pass riêng lẻ chưa đủ hoàn thành P4.2-A. Chỉ đổi nguồn ví khỏi Bảo Kim khi có kênh thay thế được payOS xác nhận và chủ dự án duyệt; hiện không đổi kiến trúc.

### Task P4.3 — Migration và model tài chính mới

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Consumes:** data contract README mục 6.2. **Produces:** bảng/repository mới, không thay thế entity Payment legacy.

- [x] Viết schema preflight: source phải có bảng legacy hiện hành và chưa có schema đích sai phiên bản. Backup trước migration; MySQL DDL implicit commit, không coi rollback transaction là phục hồi DDL.
- [x] Tạo 7 bảng core trong README: channels, obligations, intents, inbox, receipts, refunds, payout_attempts. VND BIGINT, FK/check amount dương và ràng buộc unique business key/provider transaction/idempotency.
- [x] Dùng business key `BOOKING:<id>:ADVANCE`, `BOOKING:<id>:BALANCE`, `SUBSCRIPTION:<orderId>`; purpose và subject phải nhất quán, không dùng bookingId giả cho phí gói.
- [x] Bổ sung kênh nhận/chi gắn owner/admin user, secret chỉ là reference cấu hình server. Không copy key vào DB dump hoặc response.
- [x] Viết PaymentMigrationTest: tồn tại dữ liệu cũ, migration giữ nguyên row/hash/amount/paidAt; unique chặn inbox/receipt/obligation trùng; bootstrap máy mới có cùng contract. Không tự seed tài khoản thật.
- [x] Chạy migration trên bản DB kiểm chứng trước, compile/validate mappings; chưa kích hoạt workflow mới.

**Đã thực hiện 2026-10-29:** migration one-shot, bootstrap 14 bảng, 7 entity/repository và 11 enum mới. `PaymentMigrationTest` 46 ca + `PaymentModelTest` 1 ca đạt trong full clean verify 217 test. Xem checkpoint đợt 2 cuối file và [hướng dẫn migration](../migrations/README.md). DB kiểm chứng được dựng từ fixture legacy cố định chứa đầy đủ dữ liệu cũ; không migration DB ứng dụng hoặc seed tài khoản provider thật. Chưa ghi nhận human review của contributor được phân công.

### Task P4.4 — Nghĩa vụ thanh toán và vòng đời QR

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** PaymentObligationService/PaymentIntentView theo README; receipt không được tạo từ form thanh toán.

- [x] Viết PaymentIntentTest với Clock cố định: tạo QR có expiry +15 phút, refresh GET không gia hạn, hai POST cạnh tranh chỉ có một intent ACTIVE cho obligation.
- [x] Khoá obligation trước khi tạo/lấy lại ACTIVE intent; amount và channel từ obligation/subject phía server. QR chứa ngân hàng đúng collector, số tiền và code duy nhất.
- [x] Implement POST tạo attempt, POST hủy attempt và GET status. CancelAttempt kết thúc lần thử; handler domain quyết định có hủy booking hay chỉ đóng QR, không làm service QR tự hủy mọi loại subject.
- [x] Checkout polling 2 giây, dừng ở kết quả cuối; ẩn QR khi expiry/cancel, trang kết quả phân biệt đã nhận tiền/đã hủy/hết hạn/chờ hoàn.
- [x] Test sửa amount, subjectId, bank account trong request không đổi dữ liệu server; Owner/Admin không được mở checkout thuê của Customer khác.

### Task P4.5 — Xác thực và lưu inbox trước ACK

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** SePayWebhookService.receive(channelId, headers, rawBody). Route POST `/webhooks/sepay/{channelId}`.

- [x] Viết test double webhook: sau hai lần gọi, chỉ một inbox được commit; provider retry vẫn được ACK theo hợp đồng. Invalid auth không tạo inbox.
- [x] Xác thực signature/API key bằng mode đã xác minh ở P4.2, constant-time compare khi phù hợp; resolve channel từ server, đối chiếu tài khoản nhận, transferType=in, transaction ID, amount/code.
- [x] Payload hợp lệ nhưng chưa tìm thấy code: lưu inbox/receipt để review, không bỏ giao dịch. Amount phải parse chính xác tới VND, không float.
- [x] Lưu raw payload và receivedAt trong transaction ngắn, loại secret auth khỏi log. Commit trước HTTP success. ACK theo tài liệu SePay, ví dụ:

```json
{"success":true}
```

- [x] Inject DB failure trước commit: HTTP không được báo đã nhận thành công; retry tạo đúng một inbox. Sau commit rồi process crash: inbox RECEIVED/lease hết hạn còn trong DB để processor phục hồi; job tự quét thuộc P4.9.
- [x] SecurityConfig chỉ exempt CSRF route webhook chính xác; không exempt toàn `/payment/**` hoặc endpoint refund.

### Task P4.6 — Apply receipt một lần và adapter domain

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** ReceiptProcessor, ReceiptApplicationService, PaymentDomainHandler; test ReceiptApplicationTest.

```java
public enum PaymentSubject { BOOKING_ADVANCE, BOOKING_BALANCE, SUBSCRIPTION }
public interface PaymentDomainHandler {
    PaymentSubject subject();
    void lockSubject(Long subjectId); // khóa aggregate trước các hàng tài chính
    void apply(Long receiptId); // chạy trong transaction của application service
    void expire(Long intentId); // cùng transaction kết thúc intent và trạng thái domain
    void cancel(Long intentId); // handler quyết định hủy subject hay chỉ đóng QR
}
```

- [x] Orchestrator đọc route không giữ lock, với rental thì gọi CarCommandExecutor rồi transactional application service; subscription handler sẽ khóa Owner theo Phase 8. Không tự invoke transaction qua this.
- [x] Claim inbox bằng transaction lease ngắn rồi commit trước khi chờ queue. Trong apply transaction, gọi lockSubject(subjectId) trước, sau đó mới đọc/khóa current obligation/intent/receipt rồi apply(receiptId); unique receipt bảo đảm cùng giao dịch không được apply hai lần. Handler apply không được lấy thêm khóa aggregate ngược chiều.
- [x] Nối handler Phase 6/8 qua danh sách bean; subject chưa bật thì giữ việc bền vững, không ACK processed rồi bỏ qua. Khi phase domain chưa có, test bằng handler fixture; feature checkout domain vẫn tắt.
- [x] ADVANCE lệch tiền: REVIEW_REQUIRED, không cộng dồn hoặc tự hoàn lệch tiền trong backlog. SUBSCRIPTION lệch tiền cũng ghi review, không cấp gói. BALANCE đủ/vượt số còn nợ: core phân bổ phần phải trả, giữ phần dư REFUND_REQUIRED và gọi handler cùng transaction; RefundService tạo nghĩa vụ hoàn ở P4.7, handler domain thật nối ở Phase 6/8.
- [x] Cùng amount/code nhưng hai providerTransactionId thật: khoản thứ hai phải có receipt riêng, xử lý trả trùng; cùng transactionId retry chỉ dedupe.
- [x] Test apply-vs-expiry/cancel, job retry và restart; lỗi notify rollback apply, inbox được retry chứ không mất. Code không khớp collector không được cấp quyền lợi.

### Task P4.7 — Nghĩa vụ hoàn và thông tin người nhận

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** RefundService.request và RefundRecipientService.submit theo README; test trong RefundServiceTest, RefundRecipientTest và RefundAccountMigrationTest.

- [x] Tạo refund bằng business key theo sự kiện và receipt: `CUSTOMER_CANCEL:<bookingId>:<receiptId>`, `DUPLICATE:<receiptId>`, `EXCESS:<receiptId>`. Số hoàn tổng không vượt phần tiền đủ điều kiện của receipt; không thay amount gốc.
- [x] Cùng sự kiện gọi lại trả cùng refundId. payout_channel_id được resolve từ collector của receipt: tiền thuê dùng Owner, tiền gói dùng Admin; không lấy kênh do client chọn. Chưa có đích → WAITING_RECIPIENT, gửi notification cho đúng người nhận.
- [x] Form nhận bankBin/accountNumber/saveForLater và confirmedAccountName để đối chiếu tên vừa xác minh; xác thực quyền recipient từ server, kiểm tra đích qua RefundDestinationVerifier (fixture offline; adapter thật bị chặn cho tới gate P4.2-A), yêu cầu người nhận kiểm tra thông tin trước submit. Không yêu cầu liên kết SePay/payOS.
- [x] readyAt ghi một lần khi đủ điều kiện và đích hợp lệ; dueAt=readyAt+1 giờ. Snapshot đích của lần chi không được đổi khi đã dispatch; thay tài khoản mặc định không sửa giao dịch đang chạy.
- [x] Test người khác đoán refundId không xem/sửa được, CSRF sai bị chặn, submit lặp không reset dueAt; không claim có tiền vào chỉ vì lưu form thành công.

**Phạm vi hoàn thành:** nghĩa vụ, quyền, biểu mẫu hai bước, lưu tài khoản tùy chọn và snapshot/deadline đã kiểm chứng offline. Production verifier mặc định từ chối; chưa có lệnh chi, worker hoặc domain handler thật. P4.2-A giữ nguyên checkbox xác minh API lookup và live; không dùng payout create để tra tên.

### Task P4.8 — Dispatch và đối soát payOS an toàn

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** PayoutWorker, PayoutTransactions, PayoutChannelResolver, PayoutResult, PaymentProviderConfiguration, NotificationService/template và PayoutWorkerTest/PaymentFlowFixture.

- [x] Viết fixture test timeout sau provider đã nhận lệnh, trạng thái PROCESSING, SUCCEEDED, NOT_SENT; assert không có hai lần chi chưa rõ cho cùng refund.
- [x] Transaction 1 claim refund, tạo attemptNo/idempotencyKey và snapshot bank/amount/source, commit. HTTP create sau đó dùng key đã lưu:

```text
referenceId = refund:<refundId>:attempt:<attemptNo>
idempotencyKey = giá trị ổn định lưu cùng attempt đó
```

- [x] Transaction 2 ghi provider ID/kết quả. Timeout, mất response hoặc process chết sau gửi → UNKNOWN/PROCESSING; ưu tiên GET provider ID hoặc list theo reference với cùng payoutChannelId, không tạo attempt mới. Khi retention/replay chưa được provider xác nhận, không tự POST lại kể cả cùng key; query rỗng không chứng minh chưa chi.
- [x] Chỉ tạo attemptNo tiếp khi đã có kết quả chắc chắn chưa chuyển và lỗi có thể retry. Thiếu tiền → WAITING_FUNDS, nhắc đúng Owner/Admin; nạp tiền rồi tự thử lại, không bắt người nhận tạo yêu cầu lần nữa.
- [x] Đối soát success phải khớp source context, provider ID, amount và beneficiary snapshot; chỉ SUCCEEDED mới ghi hoàn tất. Không suy ra success từ HTTP 200/approval flag.
- [x] Hai worker/lease hết hạn không được cùng dispatch; test crash ở trước gửi, sau gửi, sau provider success trước local commit. Test cả refund Owner và Admin dùng đúng hai credential/kênh độc lập. Không giữ Car queue hoặc DB transaction trong mạng provider.

**Phạm vi hoàn thành:** dispatch/reconcile và retry khi gọi worker đã kiểm chứng offline. NOT_SENT/retry/funding dùng fixture; production adapter chưa có mapping NOT_SENT được provider bảo đảm. Scheduler tự gọi worker theo lịch thuộc P4.9. Định danh nguồn kênh không được repoint; P4.2-A vẫn chặn lookup/provider/live. Xem checkpoint đợt 5 bên dưới.

### Task P4.9 — Job hết hạn, khôi phục và cảnh báo

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** PaymentMaintenanceJob; Clock bean cấu hình rõ ở config.

- [ ] Quét inbox chưa xong, intent hết hạn, attempt chưa rõ và refund đến lịch retry từ DB mỗi 30 giây; dùng lease và mốc sự kiện lưu bền vững.
- [ ] Hết QR gọi transaction expiry: lockSubject(subjectId) trước các hàng tài chính rồi handler.expire(intentId). ADVANCE giải phóng booking; BALANCE chỉ đóng QR; SUBSCRIPTION kết thúc yêu cầu mua. Cùng carId qua queue; hàng đợi đầy giữ việc retry trong DB. Subject chưa có handler vẫn giữ việc chờ domain, không đánh dấu đã làm.
- [ ] Refund quá 1 giờ từ readyAt: cảnh báo một lần cho các bên/Admin, không khóa Owner hoặc giả lập hoàn. App dừng qua deadline rồi mở lại phải quét bù.
- [ ] Transport backoff tối đa 5 phút, giữ dueAt; error log redacted, không busy loop.
- [ ] Test fake Clock vượt biên, restart/recovery và race job/webhook, không dùng chờ 1 giờ thật.

### Task P4.10 — UI refund và quyền truy cập

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** PaymentController/RefundController, templates và polling script.

- [ ] Người trả xem intent của mình; recipient xem refund của mình; collector xem nghĩa vụ gắn đúng xe/kênh; Admin xem theo quyền quản trị, không lộ secret hoặc thông tin ngân hàng đầy đủ trong list.
- [ ] Render WAITING_RECIPIENT/WAITING_FUNDS/PROCESSING/OVERDUE/SUCCEEDED rõ ràng; quá hạn là thuộc tính thời gian, không thay SUCCEEDED thành thất bại.
- [ ] UI success điều hướng từ backend đã commit; cancel không tuyên bố tiền ngân hàng đã được đảo. Polling GET bị giới hạn tần suất, tab đóng không dừng worker.
- [ ] Test desktop/mobile, XSS nội dung webhook/note, CSRF/IDOR, không JS vẫn xem được trạng thái qua reload. Không thêm websocket.

### Task P4.11 — Gate kết thúc nền thanh toán

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

- [ ] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`.
- [ ] Kiểm chứng DB hai connection: duplicate inbox/apply, duplicate refund dispatch, stale managed entity, lost provider response. Đối chiếu seed legacy nguyên trạng.
- [ ] Tổng hợp contract offline P4.2 và gate tài khoản/provider/live P4.2-A; fixture pass và live capability được phân biệt. Nếu P4.2-A chưa đạt, ghi rõ core đã kiểm chứng offline nhưng live bị chặn, không tick live hoặc bật workflow.
- [ ] Handoff Phase 6/8: các service signatures, money state và handler registration theo đúng README; không mở endpoint thanh toán legacy thành tiền thật.
- [ ] Trước workflow cutover, đồng bộ SDD FR-12/BR-09/schema/sequence diagram và decisions mới. Chưa bật VIETQR_V1 chỉ vì core phase pass.

**Hoàn thành khi:** inbox durable + apply idempotent + payout/recovery + quyền UI có bằng chứng; schema legacy giữ nguyên; các gate nhà cung cấp được ghi trung thực. Luồng end-to-end rental/subscription được chốt ở Phase 6/8.

## Checkpoint P4.2 — SePay offline, 2026-10-27

- Nhánh: `feat/payment-provider-adapters`; `thanhmit` phụ trách, `huy69332` được phân công review nhưng chưa ghi nhận review của contributor khác. Chưa commit/push trong checkpoint này.
- Có `ProviderPaymentProperties`, `SePayWebhookVerifier`, `PaymentProviderConfiguration` và cấu hình mẫu cho Owner/Admin Test. Secret lấy từ biến môi trường; mặc định tắt. Không thêm dependency, endpoint, DDL/model tài chính hoặc client chi tiền.
- RED verifier: thiếu verifier/configuration; RED timestamp: request HMAC đúng quá 5 phút vẫn được nhận trước bản sửa; RED cấu hình: bật kênh thiếu secret chưa bị chặn. GREEN: 15 test verifier + 4 test cấu hình đạt. Chữ ký fixture tính độc lập bằng Python HMAC, giữ UTF-8/LF qua `.gitattributes`.
- Test bao gồm raw bytes/Unicode/số tiền bị sửa, chữ ký sai, replay ngoài ±300 giây và boundary, sai kênh Owner/Admin, kênh tắt/không tồn tại, header thiếu/lặp/sai định dạng, binding cấu hình, mặc định không kênh, thiếu secret và che secret trong `toString`.
- Fresh `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` đạt **122 test, 0 failure/error/skipped**: 103 hồi quy + 19 mới. Log local: `.scratch/phase4/provider-clean-verify.log`; RED/GREEN ở cùng thư mục. Các lần chạy chọn lọc nằm trong `sepay-*.log`; build sạch là bằng chứng cuối để tránh bytecode incremental từ IDE.
- Kiểm tra đọc DB trước/sau: fingerprint dữ liệu/schema 7 bảng và ảnh seed không đổi; không còn fixture queue/date/transaction. Thay đổi riêng có sẵn ở `phase-0-init.md` giữ nguyên.
- Đã ghi [mapping và nguồn chính thức](payment-provider-contract.md). Chưa gọi sandbox/provider thật hoặc nối ngân hàng; chưa chọn kênh chi, chưa kiểm chứng phí/tối thiểu, payout status/idempotency/timeout và mapping ID API đối soát. **P4.2 chưa hoàn tất**, roadmap giữ **45/85**; tiếp tục các mục này trước khi xác nhận gate provider.

## Checkpoint P4.2 — chủ dự án chốt payOS, 2026-10-27

- Quyết định tiếp nối checkpoint SePay: **API payOS** cho hoàn tiền; nguồn tiền là ví Bảo Kim của đúng Owner/Admin. Không tích hợp API chi hộ Bảo Kim trực tiếp. Vẫn cho phép liên kết ngân hàng/ví sau, chưa commit/push.
- Đã thêm interface `PayOsPayoutClient`, các record/enum theo plan, `HttpPayOsPayoutClient`, HMAC canonical format cho payout và cấu hình credential riêng theo reference. Không thêm SDK/dependency, schema, endpoint hoặc worker tiền thật. Chưa có bean client nối DB khi chưa có payment_channels; P4.3/P4.8 sẽ cấp resolver kênh và kiểm tra quyền sở hữu.
- Create đối chiếu reference/amount/bank/account; get kiểm tra payout ID. Chỉ transaction SUCCEEDED trả thành công. State lỗi/không biết, timeout, HTTP lỗi, chữ ký hoặc dữ liệu sai → UNKNOWN. Chưa map NOT_SENT khi chưa có bảo đảm provider về việc chưa chuyển.
- Bổ sung `findByReference` theo API list chính thức để tìm lại lệnh khi mất response create và chưa biết payout ID. 0 hoặc nhiều kết quả không cho phép tự động chi lại. `idempotencyKey` do caller truyền, không tự đổi key hoặc tự retry.
- RED create do thiếu client/record; RED lookup do thiếu phương thức; RED kiểm tra tiền không dương trước sửa. GREEN gồm **41 test payOS**, 6 test cấu hình và 15 test SePay. Fixture chữ ký Python độc lập theo SDK chính thức commit trong [provider contract](payment-provider-contract.md); test không gọi API thật.
- Fresh `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`: **165 test, 0 failure/error/skipped** (103 hồi quy + 62 provider/configuration). Log local `.scratch/phase4/payos-clean-verify.log`; các RED/GREEN chọn lọc trong `payos-*.log` cùng thư mục. Cảnh báo deprecation Jackson không làm build thất bại.
- Kiểm tra đọc DB sau build: fingerprint dữ liệu/schema 7 bảng và seed images không đổi, không còn fixture. Thay đổi riêng của chủ dự án ở `phase-0-init.md` giữ hash cũ.
- **P4.2 vẫn chưa hoàn tất**, roadmap **45/85**: còn xác minh phí/tối thiểu/khả năng tự động của kênh, thời gian provider giữ idempotency key, điều kiện NOT_SENT, mapping ID đối soát SePay và gate Live. Chưa kiểm chứng sandbox, tiền thật hoặc review từ `huy69332`.

## Checkpoint đợt 1 — P4.2 kỹ thuật/offline, 2026-10-29

- Đã cập nhật SDD v2.23/§14.1 và plan theo 8 đợt được chủ dự án duyệt. Đợt 1 đạt đầu ra kỹ thuật/offline; không đánh dấu toàn bộ P4.2 hoặc live pass, giữ roadmap **45/85**.
- Đối chiếu lại nguồn chính thức tại [provider contract](payment-provider-contract.md). SePay v1 numeric ID là hướng đối soát tương thích; equality với webhook ID và quyền query vẫn cần chứng minh trên cùng giao dịch/kênh thực tế. Không ingest UUID v2 hoặc dùng referenceCode rỗng/khớp mờ để tự hợp nhất giao dịch. Chưa tạo query client ngoài phạm vi adapter đang có.
- Chốt recovery payOS: giữ attempt/reference/key/source/đích/số tiền bền vững, ưu tiên GET/list sau mất response; query rỗng/lỗi không cho phép create lại. Chưa có bảo đảm retention/replay/NOT_SENT nên giữ UNKNOWN, không tự retry POST. Tài liệu xác nhận credit dịch vụ chi và tiền nguồn ví Bảo Kim là hai khoản riêng; phí thực tế/tối thiểu và lookup tên tài khoản trước chi chưa được xác minh.
- Bổ sung `referenceId` cho `PayoutResult` và cập nhật mọi caller/expected result hiện có. Adapter trả signed reference để P4.8 đối chiếu durable attempt; từ chối reference rỗng, thiếu, null hoặc sai kiểu ngay cả khi chữ ký hợp lệ. Không thay đổi cấu hình bật kênh, legacy Payment, schema, endpoint hoặc worker.
- RED đầu tiên: test yêu cầu `PayoutResult.referenceId()` chưa compile (`batch1-reference-red.log`); GREEN sau bổ sung field: 42 test payOS đạt (`batch1-reference-green.log`). RED tiếp theo: bốn fixture ký độc lập có reference thiếu/rỗng/null/số đều bị nhận SUCCEEDED trước sửa, **4/4 fail** (`batch1-invalid-reference-red.log`). Sau sửa, cả bốn trả UNKNOWN. Generator Python local tạo fixture HMAC độc lập, không dùng code Java để tính expected signature.
- Chạy build sạch với bộ test không cần DB/provider:

```powershell
.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=CarCommandExecutorTest,SePayWebhookVerifierTest,ProviderPaymentPropertiesTest,PayOsPayoutClientTest' clean verify
```

  Lần đầu compile sạch toàn bộ production/test nhưng suite có **86 test, 1 failure** ở test queue cũ `queuedCommandTimesOutAndNeverRunsAfterTheWorkerIsReleased`: latch không mở trong deadline của test (`batch1-clean-verify.log`). Không sửa production/test queue. Chạy lại cùng bộ bằng lệnh trên **bỏ `clean`**, dùng classes vừa build, đạt **BUILD SUCCESS, 86 test, 0 failure/error/skip**, đóng gói/repackage JAR (`batch1-verify-rerun.log`): 19 queue + 46 payOS + 6 cấu hình + 15 SePay. Giữ cả log fail/rerun; không gọi lần clean đầu là pass. Lỗi timing queue cần theo dõi nếu tái diễn.
- Log nằm ở `.scratch/phase4/`, bị Git bỏ qua. `git diff --check` đạt. Không chạy lại 84 test tích hợp MySQL; không truy cập DB, migration/seed, browser hoặc provider có xác thực, không chuyển tiền, không kiểm chứng fingerprint DB trong đợt này. Cảnh báo Jackson deprecation và lỗi cấu hình cố ý trong test không làm lần verify cuối thất bại.
- Chưa commit/push, giữ các thay đổi có sẵn kể cả `phase-0-init.md`; chưa có human review từ reviewer được phân công. **Tiếp theo đợt 2/P4.3**, phát triển offline theo contract đã chốt; các gate ở provider contract phải đạt trước khi bật live.

## Checkpoint đợt 2 — P4.3 schema và model tài chính, 2026-10-29

- Tạo `docs/migrations/2026-10-27-01-payment-core.sql`, hướng dẫn vận hành và DDL bootstrap đồng nhất: 7 bảng core mới, không thay cột/seed/Payment legacy. Preflight yêu cầu backup-confirmation marker, MySQL 8.0.16+, strict mode, 7 bảng InnoDB, 70 cột nguồn đúng kiểu/nullability, primary/unique keys, 7 FK và 6 CHECK nguồn. Từ chối schema đích đã tồn tại hoặc còn dở; không tự sửa/xóa bảng. MySQL chuẩn hóa DATEDIFF thành to_days trong metadata; đã đối chiếu đúng biểu thức này thay vì từ chối nhầm source hợp lệ.
- Tạo 7 entity/repository và 11 enum dưới `payment/`. VND BIGINT; định danh phân biệt hoa/thường; composite receipt FK khớp inbox/channel/transaction và channel của intent; tham chiếu subject nghiệp vụ không tạo booking giả hoặc FK tới bảng Phase 8 chưa có. Channel chỉ lưu credential reference, mặc định disabled; không có resolver/endpoint/worker mới.
- Các mốc mới dùng Instant/TIMESTAMP_UTC và UTC DATETIME(6), không đổi timezone mapping legacy. Refund/payout giữ amount/source/recipient snapshots, ready/due, lease/retry/reconcile/notification timestamps. Các guard tổng hoàn, collector/role, một ACTIVE intent hoặc một lần chi chưa rõ vẫn thuộc service/transaction P4.4–P4.9, không suy ra đã có từ schema.
- RED SQL: `migration-red.log` thất bại vì chưa có migration. RED model: `model-red.log` thiếu các type/repository mới. Lượt trung gian phát hiện khác biệt CHECK metadata và một fixture thử thêm unique vào booking_id vốn có lịch sử retry; đã sửa preflight/fixture tương ứng, không sửa dữ liệu legacy ứng dụng. Lượt clean chọn lọc đầu đạt 45 test (`schema-model-green.log`); sau đó bổ sung strict-mode, unique reference và round-trip ghi/đọc cả 7 model.
- Full verification dùng DB tạm `carrent_p43_cfaaaf0d4d614b25bcdf2f0b0f4020b8` dựng từ bootstrap. Truyền URL qua **biến môi trường SPRING_DATASOURCE_URL** trong process Maven, khôi phục biến sau lệnh. Lượt thử truyền URL bằng `-D...` bị Windows CMD tách ký tự `&`, dừng trước chạy test (`full-clean-verify.log`); không gọi đó là build/test pass.

```powershell
# SPRING_DATASOURCE_URL trỏ tới DB kiểm chứng đã chuẩn bị, không phải DB ứng dụng.
.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
```

- Lượt cuối **BUILD SUCCESS, 217 test, 0 failure/error/skip**, JAR được đóng gói (`full-clean-verify-env.log`). Gồm 46 migration + 1 model + 34 date-search + 19 queue + 9 transaction + 41 HTTP queue + 46 payOS + 6 properties + 15 SePay. Test MySQL kiểm chứng bảo toàn mọi row legacy, bootstrap parity/rerun, FK/CHECK/unique, sai schema/partial/backup gate; JPA `ddl-auto=validate`, payload bytes bất kỳ, tài khoản có số 0 đầu, BIGINT 9007199254740993 và Instant chính xác tới microsecond khi JVM America/Los_Angeles, JDBC giờ Việt Nam. Các log constraint/config lỗi cố ý trong suite không phải test thất bại.
- Helper local chỉ tạo/xóa schema có tên ngẫu nhiên `carrent_p43_<32 hex>` và kiểm tra tên trước xóa. Tất cả DB của đợt verify đã được dọn. Fingerprint schema, danh sách bảng và toàn bộ row của DB ứng dụng trước/sau giống nhau (`source-before.sha256`, `source-after.sha256`); DB ứng dụng vẫn 7 bảng legacy. Fixture SQL legacy cố định nằm trong test resources, không chứa credential provider thật. Log/helper local nằm trong `.scratch/phase4/batch2/`, bị Git bỏ qua.
- **P4.3 hoàn thành trong phạm vi schema/model trên DB kiểm chứng; tiến độ 47/86, còn 39. Tiếp theo P4.4.** DB ứng dụng chưa migration; không browser/provider live, tiền thật, commit/push hoặc human review mới. P4.2-A giữ 9 mục chờ tài khoản/provider/live.

## Checkpoint đợt 3 — P4.4 + P4.5 + P4.6, 2026-10-30

**Phê duyệt:** chủ dự án đồng ý gộp ba task cùng phiên, giữ checkpoint riêng. SDD/README đã cập nhật thành 7 đợt; ID/task tổng không đổi. Giữ nhánh `feat/payment-provider-adapters` và mọi thay đổi có sẵn, không stage/commit/push.

### P4.4 — intent/QR/checkout

- PaymentObligationService route ngoài transaction → Car queue cho rental → PaymentIntentTransactions trên Spring proxy. Handler khóa current aggregate trước obligation/intent; quyền payer, role và trạng thái tài khoản được đọc lại khi thực thi. Core đối chiếu payer/collector với Booking; amount/channel chọn phía server, bỏ qua các tham số sửa tiền/subject/bank từ client.
- Khóa obligation và locking read ACTIVE intent; hai connection chỉ tạo một intent. PaymentLocks evict rồi locking-load để tránh snapshot/managed entity cũ. QR dùng amount/code duy nhất và ngân hàng collector; mặc định 15 phút, không vượt dueAt của nghĩa vụ; refresh GET không ghi hoặc gia hạn.
- POST `/payment/obligations/{id}/attempts`, POST `/payment/intents/{id}/cancel`; GET checkout/result/status. Cancel/expiry gọi handler theo loại nghĩa vụ; BALANCE không bị tự hủy như ADVANCE. Checkout chỉ hiện QR khi domain handler và kênh/provider đang khả dụng; missing/disabled trả UNAVAILABLE trên view, không sửa trạng thái DB để giả hủy.
- Thymeleaf dùng fragment hiện có, polling 2 giây theo backend, ẩn QR khi hết hạn, dừng ở kết quả cuối/mất quyền/đóng tab; có reload khi không JS. Cache-Control no-store, CSRF cho POST, kiểm thử IDOR và request tampering. `PaymentIntentTest`: **15 test đạt**.

### P4.5 — durable inbox trước ACK

- POST `/webhooks/sepay/{channelId}`: resolver kênh DB/credential reference phía server, HMAC trên bytes gốc bằng verifier P4.2; đối chiếu account, chiều in, numeric transaction ID, amount VND chính xác, transactionDate giờ Việt Nam. Reject JSON duplicate keys/trailing payload, fractional/overflow/non-number amount, sub-account chưa được cấu hình; không đoán code từ content.
- Ghi raw bytes/receivedAt UTC bằng transaction REQUIRES_NEW ngắn. MySQL atomic insert-on-duplicate giữ payload lần đầu; chỉ trả `{"success":true}` sau commit. Invalid auth không lưu; DB failure trả 503/false để retry. Controller không log body/header/credential hoặc exception chứa chúng.
- CSRF chỉ exempt POST đúng một segment `/webhooks/sepay/{channelId}`; payment/refund và route liền kề vẫn được bảo vệ. `SePayInboxTest`: **20 test đạt**, gồm double/concurrent delivery, invalid auth/payload, exact BIGINT/decimal nguyên VND và trigger lỗi trước commit → retry thành công.

### P4.6 — claim/queue/apply/expiry

- InboxWorkService claim lease UUID 60 giây rồi commit trước queue. ReceiptProcessor không giữ transaction khi chờ queue; ReceiptApplicationService.apply(inboxId, leaseOwner) là proxy transactional riêng, bắt buộc token fencing và lease còn hạn sau khi lấy aggregate/financial locks. Không tự gọi transaction qua this.
- Core tạo một receipt cho mỗi channel/provider ID, amountSettled/allocation và processed cùng commit với handler apply/notification. Retry cùng transaction ID dedupe; hai giao dịch thật cùng code có hai receipt và khoản sau REFUND_REQUIRED. ADVANCE/SUBSCRIPTION lệch tiền giữ review, không cấp lợi ích/cộng dồn/tự hoàn lệch; BALANCE đủ/vượt chỉ phân bổ phần còn nợ, phần dư chuyển handler qua receipt REFUND_REQUIRED.
- Bank time có độ chính xác giây; đối chiếu mốc tạo intent sau khi truncate createdAt về giây, không từ chối giao dịch hợp lệ cùng giây do microsecond. Expiry đã commit không phục hồi attempt; tiền đến sau hủy/hết hạn giữ REFUND_REQUIRED. Tiền nhận đúng hạn nhưng xử lý chậm chỉ áp dụng khi expiry chưa commit.
- Missing handler giữ inbox bền vững để retry, không đánh dấu PROCESSED hoặc mở QR. Lỗi apply/notification rollback toàn bộ receipt/allocation/domain; retry dùng mốc DB với backoff 30 giây tăng đến 5 phút và mã lỗi đã rút gọn. Queue rejection và worker cũ không làm mất việc hoặc xóa lease mới.
- `ReceiptApplicationTest`: **21 test đạt**; `PaymentRecoveryTest`: **2 test đạt**. Kiểm chứng hai connection apply-vs-cancel/expiry, hai giao dịch tranh allocation, hai worker claim, stale token, Owner/Admin collect riêng, lỗi notification, đóng/mở Spring context sau ACK/claim, missing handler rồi đăng ký và phục hồi. Handler chỉ là fixture trong test; không có production handler thuê/gói hoặc nghĩa vụ hoàn tự động từ domain.

### Bằng chứng và giới hạn

- Baseline mới: **217 test đạt** trên DB tạm trước chỉnh sửa. RED/GREEN ở `.scratch/phase4/batch3/`: intent-red/intent-green, checkout-red, inbox-red, receipt-red, core-green1, core-expanded, edge-red và full-clean-verify. Giữ cả lượt thất bại: race snapshot MySQL đã sửa bằng current locking read; fixture Instant/JDBC sai timezone đã sửa về UTC LocalDateTime; edge-red tái hiện QR kênh tắt và timestamp cùng giây trước khi sửa.
- Full clean verify lần đầu (`full-clean-verify-first.log`) chạy 275 test, 58 test mới đạt nhưng một test queue HTTP cũ lỗi cleanup: filler no-op chờ quá 2 giây trong lúc render HTTP độc lập. Tái hiện riêng tại `queue-diagnostic.log`: HTTP 200/302 và dữ liệu đúng, chỉ waiting.get của filler báo timeout. Sửa test chấp nhận đúng CarQueueRejectedException timeout của filler; giữ mọi assertion bypass và không đổi timeout/code queue production. Lượt `queue-diagnostic-green.log` vẫn thất bại: blocker bị timeout trước khi bắt đầu và finally che lỗi gốc; bỏ get lặp trong finally, vẫn release luôn và kiểm tra blocker trong luồng thành công. Lượt `queue-diagnostic-unmasked.log` đạt; full suite được chạy lại toàn bộ sau sửa test.
- `target` bị Java tooling của VS Code ghi lại trong lượt chạy ban đầu (mất class/resource). Dùng POM kiểm chứng local dưới `.scratch`, giữ nguyên source pom.xml/dependency, chỉ đổi source/resource đường dẫn tuyệt đối, output build và Surefire workingDirectory về root. Không chạy nhiều Maven verify đồng thời trên output đó. Lệnh cuối:

```powershell
# SPRING_DATASOURCE_URL trỏ DB bootstrap kiểm chứng đã chuẩn bị; khôi phục env sau lệnh.
.\mvnw.cmd -f .scratch/phase4/batch3/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
node .scratch/phase4/batch3/polling-check.cjs
git diff --check
```

- **BUILD SUCCESS — 275 test, 0 failure/error/skip**, JAR đóng gói tại `.scratch/phase4/batch3/build/`. Polling self-check **8 kịch bản đạt**: trạng thái cuối, pagehide, expiry khi offline, mất quyền. Không chạy browser desktop/mobile; đó còn là gate UI tổng ở P4.10.
- Toàn bộ migration/test write chỉ trên DB ngẫu nhiên do fixture sở hữu; DB kiểm chứng đã dọn. Fingerprint schema, danh sách bảng và từng row legacy của DB ứng dụng trước/sau giống nhau; DB ứng dụng vẫn 7 bảng. Không provider Test/Live có xác thực, không chuyển tiền, chưa human review mới.
- **P4.4/P4.5/P4.6 hoàn thành theo phạm vi nền/offline; tiến độ 50/86, còn 36. Tiếp theo đợt 4/P4.7.** P4.2-A hoãn theo tài khoản/provider. Scheduler tự quét/retry/expiry thuộc P4.9, RefundService/PayoutWorker thuộc P4.7/P4.8, domain thật/cutover thuộc Phase 6/8. Chưa bật workflow tiền thật hoặc sửa legacy payments/booking policy.

## Checkpoint đợt 4 — P4.7, 2026-10-30

### Nghĩa vụ hoàn và nguồn tiền

- RefundService.request khóa Receipt → Refund, đọc locking/current budget; cùng business key chỉ trả lại ID khi amount/reason trùng. Giữ amount receipt gốc, giới hạn riêng phần đã phân bổ cho hủy và phần chưa phân bổ cho dư/trùng/đến muộn, đồng thời giới hạn tổng hoàn. Receipt chưa xác định hoặc REVIEW_REQUIRED không tự hoàn.
- Kênh PAYOS/PAYOUT chọn từ chính collector của receipt: Owner hoàn tiền thuê cho Customer, Admin hoàn tiền gói cho Owner. Không fallback sang Admin cho tiền Owner. Tạo WAITING_RECIPIENT và notification trong cùng transaction, không dispatch; lỗi notification rollback toàn bộ. Domain Phase 6/8 quyết định policy/phí và khóa aggregate trước khi gọi service.

### Tài khoản nhận và biểu mẫu

- GET `/payment/refunds/{id}`, POST `/payment/refunds/{id}/recipient/preview` và POST `/payment/refunds/{id}/recipient`: chỉ recipient active đúng role xem/sửa, CSRF cho cả hai POST, no-store và whitelist form. Không nhận amount/source/recipient từ client.
- Preview không ghi; người dùng kiểm tra tên provider và tick xác nhận. Submit xác minh lại bank/account/name ngoài transaction và ngoài Car queue, sau đó kiểm tra lại actor/source/credential reference/status/lease/attempt dưới khóa trước commit READY. confirmedAccountName chỉ dùng đối chiếu, tên lưu lấy từ verifier; không tin tên tự khai.
- readyAt chỉ ghi lần đầu, dueAt=readyAt+1 giờ, kể cả sửa READY không gia hạn. Mọi attempt (kể cả UNKNOWN), lease hoặc trạng thái đã xử lý chặn đổi đích. Regression RED tái hiện stale REPEATABLE_READ che attempt đã commit; GREEN dùng locking query sau Refund lock.
- saveForLater là opt-in; bảng `refund_recipient_accounts` lưu một tài khoản đã xác minh cho mỗi user. Chỉ điền sẵn bank/account lần sau, vẫn phải xác minh/xác nhận; không sửa refund/attempt cũ. Lỗi ghi default rollback thay đổi refund. Thymeleaf escape tên provider; UI lưu xong không báo chuyển tiền thành công.
- `RefundDestinationVerifier` có default production fail-closed. Fixture chỉ nằm trong test; **P4.2-A phải xác minh và triển khai lookup không chuyển tiền trước khi xác nhận tài khoản thật**. Không thêm dependency hoặc gọi payout để tra tên.

### Migration, kiểm chứng và bàn giao

- Migration mới `docs/migrations/2026-10-30-01-refund-accounts.sql` sau payment-core v1; không sửa file migration cũ. Bootstrap đồng nhất, UTC DATETIME(6), FK RESTRICT, ASCII bank/account, CHECK và marker backup riêng. Kiểm chứng parity, giữ legacy/core, từ chối thiếu core/backup, strict mode sai và chạy lại.
- Test mới: RefundServiceTest **13**, RefundRecipientTest **19**, RefundAccountMigrationTest **4**; tổng **36 kiểm thử mới đạt**. Có concurrency/idempotency, current budget, rollback notification/default, Owner/Admin, IDOR/CSRF/tampering, provider fail/name mismatch, state đổi trong khi verify, restart/prefill và deadline không reset.
- Full clean verify: **311 test, 0 failure/error/skip**, JAR đóng gói. Lệnh dùng POM kiểm chứng `.scratch/phase4/batch4/verification-pom.xml` để output riêng khỏi Java tooling của IDE; dependency/source pom.xml giữ nguyên. Log `.scratch/phase4/batch4/full-clean-verify.log`; các log RED/GREEN trung gian được giữ.
- Browser offline trên DB fixture riêng: đăng nhập, thông báo → refund, preview → xác nhận READY, số tài khoản giữ số 0 đầu, deadline đúng một giờ, desktop/mobile không tràn ngang và no-JS vẫn dùng được. Ảnh/bằng chứng ở `.scratch/phase4/batch4/`; fixture đã dọn. Không coi browser fixture là live.
- Full-suite DB tạm đã dọn; fingerprint schema/danh sách bảng/toàn bộ row legacy của DB ứng dụng trước/sau giống nhau, vẫn **7 bảng legacy**. Không migration DB ứng dụng, chuyển tiền, commit/push hoặc human review mới. Bản JAR mới chạy trên DB ứng dụng để xem home/catalog, không bật refund thật.
- **P4.7 hoàn thành nền/offline; tiến độ 51/86, còn 35. Tiếp theo P4.8 dispatch/reconcile**, giữ đợt riêng. P4.9 scheduler, P4.10 UI tổng, P4.11 gate và domain Phase 6/8 vẫn pending; P4.2-A vẫn là gate tài khoản/provider/live.

## Checkpoint đợt 5 — P4.8, 2026-10-30

### Dispatch và đối soát bền vững

- PayoutWorker không nhận ambient transaction; PayoutTransactions dùng REQUIRES_NEW/READ_COMMITTED và khóa Receipt → Refund → Attempt. Commit PREPARED, attemptNo, UUID idempotency key, reference `refund:<refundId>:attempt:<attemptNo>` và snapshot nguồn/amount/bank/account/name trước HTTP. beginSend commit UNKNOWN/submittedAt trước gọi create; HTTP nằm ngoài transaction và Car queue.
- Refund có lease token/expiry 60 giây; ghi kết quả phải còn sở hữu lease và khớp snapshot/source context. Hai worker không cùng dispatch; kết quả từ lease cũ không ghi đè worker mới. SUCCEEDED khớp reference/provider ID/amount/beneficiary mới hoàn tất và thông báo người nhận trong cùng transaction; lỗi notification rollback kết quả để đối soát lại.
- Runtime exception/timeout/mất response giữ UNKNOWN. Đối soát theo provider ID đã biết, nếu chưa có thì reference trên đúng kênh; query rỗng vẫn UNKNOWN. Không tự POST lại kể cả cùng key, không tạo attempt mới khi còn trạng thái chưa rõ. Backoff UNKNOWN 30/60/120/240/300 giây. Crash sau PREPARED nhưng trước HTTP có thể để khoản hoàn chờ đối soát lâu dài; không suy ra chưa chuyển từ query rỗng.
- Chỉ NOT_SENT chắc chắn kèm lý do retry được mới tạo attempt tiếp. INSUFFICIENT_FUNDS → WAITING_FUNDS, nhắc đúng collector Owner/Admin một lần trên refund, đặt nextRetryAt; RETRYABLE → READY; permanent/thiếu phân loại → REVIEW_REQUIRED. Retry không yêu cầu nhập lại đích hoặc reset readyAt/dueAt. Worker thực hiện retry khi được gọi đúng lịch; **scheduler tự quét chưa có, thuộc P4.9**.
- PayoutResult thêm NotSentReason có kiểu và giữ constructor sáu tham số tương thích. Production HTTP adapter vẫn không phát NOT_SENT; các nhánh retry/funding hiện chỉ được chứng minh bằng fixture. P4.2-A phải xác minh bảo đảm chưa chuyển, phân loại lỗi, replay/retention và kênh thật trước mapping production.
- PayoutChannelResolver nối ID kênh DB → credential reference server → PayOsChannel và từ chối kênh thu/tắt/không cấu hình/sai role. Owner và Admin dùng hai nguồn riêng. ID/user/role/credential reference của kênh là định danh nguồn bất biến theo vận hành: thay tài khoản phải tạo kênh mới, không repoint kênh có lịch sử tài chính; chưa có endpoint sửa kênh. Không bổ sung schema/migration hoặc dependency.

### Kiểm chứng và giới hạn

- PayoutWorkerTest: **33 kiểm thử đạt** trên Spring/MySQL thật, fake tại biên provider; có kiểm tra lưu trước HTTP, source/beneficiary mismatch, account/channel guard, hai dispatch/reconciler, lease hết hạn, crash trước gửi/sau nhận/success trước commit, restart context, rollback notification, Owner/Admin và retry/funding đúng phân loại. Network callback xác nhận không có DB transaction; queue xe và khóa refund vẫn dùng được lúc provider chờ.
- RED/GREEN được giữ tại `.scratch/phase4/batch5/`: worker, recovery, funds và snapshot. `snapshot-red.log` có 5 lỗi chủ đích chứng minh phải chặn snapshot bị sửa trước provider query; bản sửa dùng chung guard claim/frozenMatches và chuyển review.
- Hồi quy đầu phát hiện 3 lỗi ở ProviderPaymentPropertiesTest: context chỉ nạp cấu hình nay thiếu resolver DB cho default payout bean. Fixture binding cung cấp mock tại biên PayOsPayoutClient, không thay wiring production; config-red/config-green giữ bằng chứng và full clean verify được chạy lại sau sửa.
- Full isolated **clean verify 344 test, 0 failure/error/skip**, JAR đóng gói. Lệnh: `.\mvnw.cmd -f .scratch/phase4/batch5/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`; POM kiểm chứng chỉ tách output khỏi IDE, không đổi dependency/root pom. Log `.scratch/phase4/batch5/full-clean-verify.log`.
- Browser trên DB fixture riêng: Owner thấy nhắc bổ sung số dư, không lộ tài khoản người nhận hoặc link tới trang chỉ người nhận được phép xem; sau retry fixture, Customer thấy thông báo và trang hoàn SUCCEEDED, không còn form sửa đích. Kiểm tra desktop/mobile; ảnh và kết quả ở `.scratch/phase4/batch5/`. Fixture provider không phải giao dịch live.
- DB tạm đã dọn; fingerprint schema/danh sách bảng/mọi row legacy của DB ứng dụng trước/sau giống nhau, vẫn 7 bảng legacy. Không migration DB ứng dụng, chuyển tiền thật, commit/push hoặc human review mới. Bản JAR mới chạy lại home/catalog trên DB hiện có.
- **P4.8 hoàn thành nền/offline; tiến độ 52/86, còn 34. Tiếp theo đợt 6: P4.9 + P4.10**. P4.2-A giữ nguyên gate tài khoản/provider/lookup/live; P4.11 và domain Phase 6/8 còn pending. Test gọi worker/restart context không chứng minh scheduler runtime tự phục hồi.
