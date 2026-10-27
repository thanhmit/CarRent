# CarRent — Phase 4: Thanh toán thật và hoàn tiền — Implementation Plan

> **For agentic workers:** Dùng superpowers:executing-plans theo task, checkbox phản ánh kết quả thật. Không tự stage/commit/push. Chỉ dùng subagent khi được yêu cầu.

**Ngày cập nhật:** 2026-10-27. **Trạng thái:** P4.1 là checkpoint lịch sử đã hoàn thành; P4.2–P4.11 bên dưới là kế hoạch mới, chưa thực hiện.

**Goal:** Tích hợp SePay VietQR và payOS/Bảo Kim với ghi nhận tiền, đối soát và hoàn tự động có khả năng phục hồi sau restart.

**Architecture:** Tách nghĩa vụ phải trả, lần tạo QR, tiền ngân hàng đã nhận và nghĩa vụ hoàn. Webhook được xác thực/lưu inbox trước ACK; worker áp dụng một lần dưới khóa DB, qua Car queue khi liên quan xe. Lệnh chi được lưu trước gọi API và đối soát độc lập với trình duyệt.

**Tech Stack:** Java 21, Spring Boot 4.1.x MVC/JPA, MySQL 8, Thymeleaf, RestClient có sẵn từ Spring; không thêm broker hoặc SDK khi HTTP client hiện có đủ dùng.

**Spec:** [README — hợp đồng mới](README.md), đặc biệt mục 2–6. Thay nội dung mock payment chưa triển khai của plan cũ; Phase 6/8 nối nghiệp vụ vào nền này.

**Phân công:** `thanhmit` phụ trách toàn bộ task mới; `huy69332` review. Xem [phân công nhóm](team-assignment.md).

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
| Migration | `docs/migrations/2026-10-27-01-payment-core.sql`, `scriptDB.sql`; không chạy DDL trong lần viết plan |
| Model | `payment/PaymentChannel.java`, `PaymentObligation.java`, `PaymentIntent.java`, `PaymentReceipt.java`, `WebhookInbox.java`, `Refund.java`, `PayoutAttempt.java` và repository/enums tương ứng |
| Provider | `payment/provider/SePayWebhookVerifier.java`, `PayOsPayoutClient.java`, `ProviderPaymentProperties.java`, `PayoutCommand.java`, `PayoutResult.java`, `TransferOutcome.java` |
| Service | `payment/PaymentObligationService.java`, `SePayWebhookService.java`, `ReceiptProcessor.java`, `ReceiptApplicationService.java`, `RefundService.java`, `RefundRecipientService.java`, `PayoutWorker.java`, `PaymentMaintenanceJob.java` |
| Hợp đồng domain | `payment/PaymentDomainHandler.java`, `PaymentSubject.java`, `PaymentIntentView.java`, `RefundRecipientForm.java`, `RefundReason.java` |
| MVC/UI | `payment/PaymentController.java`, `SePayWebhookController.java`, `RefundController.java`; `config/SecurityConfig.java`; templates `payment/checkout.html`, `result.html`, `refund.html`; `static/js/payment-polling.js` |
| Test | `PaymentMigrationTest.java`, `PaymentIntentTest.java`, `SePayInboxTest.java`, `ReceiptApplicationTest.java`, `PayoutWorkerTest.java`, `PaymentSecurityTest.java` |

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

## Các task mới — chưa hoàn thành

### Task P4.2 — Kiểm chứng điều kiện tích hợp và chốt mapping provider

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** Create provider interfaces/properties ở map file; ghi kết quả tại cuối plan này. Nguồn: [SePay webhooks](https://developer.sepay.vn/vi/sepay-webhooks), [payOS API](https://payos.vn/docs/api/), [kênh chi](https://payos.vn/dang-ky-dich-vu-chi-ho/).

- [ ] Đọc hợp đồng provider hiện hành; lập bảng xác thực webhook, transaction ID, múi giờ transactionDate, payout final state, idempotency, phí/số tiền tối thiểu và truy vấn kết quả. Dùng fixture JSON đã bỏ dữ liệu nhạy cảm.
- [ ] Kiểm tra một kênh thu/kênh chi của Owner và một bộ của Admin được phép hoạt động. Nếu chưa được duyệt, ghi rõ gate live chưa đạt; vẫn có thể phát triển adapter bằng fixture, không đánh dấu live pass.
- [ ] Định nghĩa client và records có kiểu:

```java
public interface PayOsPayoutClient {
    PayoutResult create(Long payoutChannelId, PayoutCommand command);
    PayoutResult get(Long payoutChannelId, String providerPayoutId);
}
public record PayoutCommand(String referenceId, String idempotencyKey,
                     String bankBin, String accountNumber,
                     long amount, String description) {}
public record PayoutResult(String providerPayoutId, TransferOutcome outcome,
                    String bankBin, String accountNumber, long amount) {}
public enum TransferOutcome { PROCESSING, SUCCEEDED, NOT_SENT, UNKNOWN }
```

Mỗi public type ở file tương ứng trong map file. payoutChannelId do server lấy từ refund, dùng credential của đúng Owner/Admin; không nhận kênh chi từ form người nhận. Mapping NOT_SENT chỉ dùng khi provider bảo đảm chưa chuyển. Không lấy approvalState làm bằng chứng SUCCEEDED.

- [ ] Viết test chữ ký với raw body đúng/sai, chuyển trạng thái provider và lỗi timeout. Run `.\mvnw.cmd '-Dtest=SePayInboxTest,PayoutWorkerTest' test`; RED phải do chưa có verifier/client, không do gọi nhầm live.
- [ ] Mọi test tự động dùng server stub/fixture; không gửi lệnh chi thật từ test suite.

### Task P4.3 — Migration và model tài chính mới

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Consumes:** data contract README mục 6.2. **Produces:** bảng/repository mới, không thay thế entity Payment legacy.

- [ ] Viết schema preflight: source phải có bảng legacy hiện hành và chưa có schema đích sai phiên bản. Backup trước migration; MySQL DDL implicit commit, không coi rollback transaction là phục hồi DDL.
- [ ] Tạo 7 bảng core trong README: channels, obligations, intents, inbox, receipts, refunds, payout_attempts. VND BIGINT, FK/check amount dương và ràng buộc unique business key/provider transaction/idempotency.
- [ ] Dùng business key `BOOKING:<id>:ADVANCE`, `BOOKING:<id>:BALANCE`, `SUBSCRIPTION:<orderId>`; purpose và subject phải nhất quán, không dùng bookingId giả cho phí gói.
- [ ] Bổ sung kênh nhận/chi gắn owner/admin user, secret chỉ là reference cấu hình server. Không copy key vào DB dump hoặc response.
- [ ] Viết PaymentMigrationTest: tồn tại dữ liệu cũ, migration giữ nguyên row/hash/amount/paidAt; unique chặn inbox/receipt/obligation trùng; bootstrap máy mới có cùng contract. Không tự seed tài khoản thật.
- [ ] Chạy migration trên bản DB kiểm chứng trước, compile/validate mappings; chưa kích hoạt workflow mới.

### Task P4.4 — Nghĩa vụ thanh toán và vòng đời QR

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** PaymentObligationService/PaymentIntentView theo README; receipt không được tạo từ form thanh toán.

- [ ] Viết PaymentIntentTest với Clock cố định: tạo QR có expiry +15 phút, refresh GET không gia hạn, hai POST cạnh tranh chỉ có một intent ACTIVE cho obligation.
- [ ] Khoá obligation trước khi tạo/lấy lại ACTIVE intent; amount và channel từ obligation/subject phía server. QR chứa ngân hàng đúng collector, số tiền và code duy nhất.
- [ ] Implement POST tạo attempt, POST hủy attempt và GET status. CancelAttempt kết thúc lần thử; handler domain quyết định có hủy booking hay chỉ đóng QR, không làm service QR tự hủy mọi loại subject.
- [ ] Checkout polling 2 giây, dừng ở kết quả cuối; ẩn QR khi expiry/cancel, trang kết quả phân biệt đã nhận tiền/đã hủy/hết hạn/chờ hoàn.
- [ ] Test sửa amount, subjectId, bank account trong request không đổi dữ liệu server; Owner/Admin không được mở checkout thuê của Customer khác.

### Task P4.5 — Xác thực và lưu inbox trước ACK

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** SePayWebhookService.receive(channelId, headers, rawBody). Route POST `/webhooks/sepay/{channelId}`.

- [ ] Viết test double webhook: sau hai lần gọi, chỉ một inbox được commit; provider retry vẫn được ACK theo hợp đồng. Invalid auth không tạo inbox.
- [ ] Xác thực signature/API key bằng mode đã xác minh ở P4.2, constant-time compare khi phù hợp; resolve channel từ server, đối chiếu tài khoản nhận, transferType=in, transaction ID, amount/code.
- [ ] Payload hợp lệ nhưng chưa tìm thấy code: lưu inbox/receipt để review, không bỏ giao dịch. Amount phải parse chính xác tới VND, không float.
- [ ] Lưu raw payload và receivedAt trong transaction ngắn, loại secret auth khỏi log. Commit trước HTTP success. ACK theo tài liệu SePay, ví dụ:

```json
{"success":true}
```

- [ ] Inject DB failure trước commit: HTTP không được báo đã nhận thành công; retry tạo đúng một inbox. Sau commit rồi process crash: job phải tìm lại PENDING.
- [ ] SecurityConfig chỉ exempt CSRF route webhook chính xác; không exempt toàn `/payment/**` hoặc endpoint refund.

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
}
```

- [ ] Orchestrator đọc route không giữ lock, với rental thì gọi CarCommandExecutor rồi transactional application service; subscription handler sẽ khóa Owner theo Phase 8. Không tự invoke transaction qua this.
- [ ] Claim inbox bằng transaction lease ngắn rồi commit trước khi chờ queue. Trong apply transaction, gọi lockSubject(subjectId) trước, sau đó mới đọc/khóa current obligation/intent/receipt rồi apply(receiptId); unique receipt bảo đảm cùng giao dịch không được apply hai lần. Handler apply không được lấy thêm khóa aggregate ngược chiều.
- [ ] Nối handler Phase 6/8 qua danh sách bean; subject chưa bật thì giữ việc bền vững, không ACK processed rồi bỏ qua. Khi phase domain chưa có, test bằng handler fixture; feature checkout domain vẫn tắt.
- [ ] ADVANCE lệch tiền: REVIEW_REQUIRED, không cộng dồn hoặc tự hoàn lệch tiền trong backlog. SUBSCRIPTION lệch tiền cũng ghi review, không cấp gói. BALANCE đủ/vượt số còn nợ: áp dụng phần phải trả, tạo refund phần dư theo handler.
- [ ] Cùng amount/code nhưng hai providerTransactionId thật: khoản thứ hai phải có receipt riêng, xử lý trả trùng; cùng transactionId retry chỉ dedupe.
- [ ] Test apply-vs-expiry/cancel, job retry và restart; lỗi notify rollback apply, inbox được retry chứ không mất. Code không khớp collector không được cấp quyền lợi.

### Task P4.7 — Nghĩa vụ hoàn và thông tin người nhận

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** RefundService.request và RefundRecipientService.submit theo README; test trong PayoutWorkerTest/PaymentSecurityTest.

- [ ] Tạo refund bằng business key theo sự kiện và receipt: `CUSTOMER_CANCEL:<bookingId>:<receiptId>`, `DUPLICATE:<receiptId>`, `EXCESS:<receiptId>`. Số hoàn tổng không vượt phần tiền đủ điều kiện của receipt; không thay amount gốc.
- [ ] Cùng sự kiện gọi lại trả cùng refundId. payout_channel_id được resolve từ collector của receipt: tiền thuê dùng Owner, tiền gói dùng Admin; không lấy kênh do client chọn. Chưa có đích → WAITING_RECIPIENT, gửi notification cho đúng người nhận.
- [ ] Form nhận bankBin/accountNumber/saveForLater; xác thực quyền recipient từ server, kiểm tra đích qua khả năng provider đã kiểm chứng, yêu cầu người nhận kiểm tra thông tin trước submit. Không yêu cầu liên kết SePay/payOS.
- [ ] readyAt ghi một lần khi đủ điều kiện và đích hợp lệ; dueAt=readyAt+1 giờ. Snapshot đích của lần chi không được đổi khi đã dispatch; thay tài khoản mặc định không sửa giao dịch đang chạy.
- [ ] Test người khác đoán refundId không xem/sửa được, CSRF sai bị chặn, submit lặp không reset dueAt; không claim có tiền vào chỉ vì lưu form thành công.

### Task P4.8 — Dispatch và đối soát payOS an toàn

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** PayoutWorker, PayOsPayoutClient, PayoutAttempt/repository.

- [ ] Viết fixture test timeout sau provider đã nhận lệnh, trạng thái PROCESSING, SUCCEEDED, NOT_SENT; assert không có hai lần chi chưa rõ cho cùng refund.
- [ ] Transaction 1 claim refund, tạo attemptNo/idempotencyKey và snapshot bank/amount/source, commit. HTTP create sau đó dùng key đã lưu:

```text
referenceId = refund:<refundId>:attempt:<attemptNo>
idempotencyKey = giá trị ổn định lưu cùng attempt đó
```

- [ ] Transaction 2 ghi provider ID/kết quả. Timeout, mất response hoặc process chết sau gửi → UNKNOWN/PROCESSING; dùng cùng key hoặc GET provider ID với cùng payoutChannelId để đối soát theo P4.2, không tạo attempt mới.
- [ ] Chỉ tạo attemptNo tiếp khi đã có kết quả chắc chắn chưa chuyển và lỗi có thể retry. Thiếu tiền → WAITING_FUNDS, nhắc đúng Owner/Admin; nạp tiền rồi tự thử lại, không bắt người nhận tạo yêu cầu lần nữa.
- [ ] Đối soát success phải khớp source context, provider ID, amount và beneficiary snapshot; chỉ SUCCEEDED mới ghi hoàn tất. Không suy ra success từ HTTP 200/approval flag.
- [ ] Hai worker/lease hết hạn không được cùng dispatch; test crash ở trước gửi, sau gửi, sau provider success trước local commit. Test cả refund Owner và Admin dùng đúng hai credential/kênh độc lập. Không giữ Car queue hoặc DB transaction trong mạng provider.

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
- [ ] Ghi bảng provider gate từ P4.2: fixture pass và live capability được phân biệt. Chưa có credential/enrollment thì ghi giới hạn thực tế, không tick live.
- [ ] Handoff Phase 6/8: các service signatures, money state và handler registration theo đúng README; không mở endpoint thanh toán legacy thành tiền thật.
- [ ] Trước workflow cutover, đồng bộ SDD FR-12/BR-09/schema/sequence diagram và decisions mới. Chưa bật VIETQR_V1 chỉ vì core phase pass.

**Hoàn thành khi:** inbox durable + apply idempotent + payout/recovery + quyền UI có bằng chứng; schema legacy giữ nguyên; các gate nhà cung cấp được ghi trung thực. Luồng end-to-end rental/subscription được chốt ở Phase 6/8.
