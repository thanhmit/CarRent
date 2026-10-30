# Bàn giao nền tài chính — P4.11

Phạm vi nghiệm thu: nền kỹ thuật trên MySQL riêng và provider fixture. Tài khoản, lookup tài khoản nhận và capability/live thuộc **P4.2-A**; nghiệp vụ thuê/gói thuộc **Phase 6/8**. Đăng ký tài khoản hoặc fixture pass không thay bằng chứng provider thật. Các gate bắt buộc phải đạt trước khi bật workflow thật; P4.11 không bật `VIETQR_V1`.

## 1. Interface đã có

| Interface | Trách nhiệm / điều kiện gọi |
|---|---|
| `PaymentObligationService.openAttempt(Long obligationId, CustomUserPrincipal actor)` → `PaymentIntentView` | Server đã tạo nghĩa vụ đúng subject/payer/collector/amount. Chỉ payer ACTIVE; một intent ACTIVE, tối đa 15 phút và không vượt dueAt. |
| `cancelAttempt(Long intentId, CustomUserPrincipal actor)` | Đóng lần QR; handler quyết định hiệu ứng domain. Không đảo giao dịch ngân hàng. |
| `status(Long intentId, CustomUserPrincipal actor)` → `PaymentIntentView` | Chỉ payer; tổng hợp trạng thái receipt/refund đã commit. Danh sách tài chính dùng cùng phép tổng hợp; không đọc enum intent đơn lẻ để kết luận tiền. |
| `SePayWebhookService.receive(Long channelId, HttpHeaders headers, byte[] rawBody)` | Xác thực/đối chiếu kênh; durable inbox commit trước ACK. Retry cùng channel/transaction ID giữ payload đầu. |
| `ReceiptProcessor.process(Long inboxId)` / `expire(Long intentId)` | Orchestrator ngoài transaction; claim commit trước queue. Rental qua Car queue rồi transactional application service; missing handler giữ việc để retry. |
| `ReceiptApplicationService.apply(Long inboxId, String leaseOwner)` / `expire(Long intentId)` | Spring proxy riêng. Kiểm tra token và current locked rows; allocation và handler cùng commit/rollback. |
| `RefundService.request(String businessKey, Long receiptId, long amount, RefundReason reason)` → `Long` | Handler đã quyết định sự kiện/phí và khóa aggregate; service khóa Receipt → Refund, giới hạn budget, resolve đúng nguồn collector, tạo notification atomic. |
| `RefundRecipientService.preview/submit(Long refundId, RefundRecipientForm form, CustomUserPrincipal actor)` | Chỉ recipient; lookup ngoài transaction/queue, submit xác minh lại. Clock UTC, readyAt một lần, dueAt +1 giờ; không sửa đích khi đã có attempt. |
| `PayoutWorker.dispatch(Long refundId)` / `reconcile(Long attemptId)` | Durable attempt/reference/key/nguồn/đích trước HTTP. HTTP ngoài DB transaction/Car queue; finish có lease fencing. |
| `PaymentMaintenanceJob.scan()` | Scheduler 30 giây opt-in, discovery bounded/keyset, executors domain/payout riêng; reject giữ việc trong DB. Không phụ thuộc browser. |
| `FinancialViews` | Payer/recipient/collector/Admin ACTIVE; status refund chỉ id/status/dueAt/overdue. Receipt REVIEW_REQUIRED chỉ đọc theo đúng kênh/chủ thể, phân trang và không raw payload/bank/secret. |

`PaymentIntentView`: id, code, amount, expiresAt, status, qrUrl. `RefundRecipientForm`: bankBin, accountNumber, saveForLater, confirmedAccountName. Actor, amount, source channel và beneficiary name xác minh không được tin từ client.

## 2. Đăng ký handler Phase 6/8

Mỗi `PaymentSubject` có đúng một Spring bean `PaymentDomainHandler`: `BOOKING_ADVANCE`, `BOOKING_BALANCE`, `SUBSCRIPTION`. `PaymentRoutes` nhận `List<PaymentDomainHandler>`; bean trùng subject làm boot thất bại. Không thêm handler production rỗng để vượt gate.

Handler bắt buộc triển khai `subject()`, `lockSubject(Long)`, `apply(Long receiptId)`, `expire(Long intentId)`, `cancel(Long intentId)`.

- Rental: Car queue **trước** transaction; khóa current Car → Booking trước các hàng tài chính. Subscription: khóa current Owner/aggregate gói theo Phase 8 trước tiền.
- `lockSubject` xác minh subject hiện tại và các điều kiện nghiệp vụ. `apply/expire/cancel` không lấy thêm khóa aggregate ngược chiều, mở queue/transaction lồng hoặc gọi provider.
- Core đã flush receipt, allocatedAmount và amountSettled trước `apply`. Handler **không cộng tiền lần nữa**; chỉ cấp quyền lợi một lần và gọi RefundService khi policy đòi hoàn.
- Business keys: `CUSTOMER_CANCEL:<bookingId>:<receiptId>`, `OWNER_CANCEL:<bookingId>:<receiptId>`, `DUPLICATE:<receiptId>`, `EXCESS:<receiptId>`. Các reason khác dùng `<reason>:<receiptId>`. Cùng key phải có cùng amount/reason.
- ADVANCE hết hạn: Phase 6 giải phóng giữ chỗ; BALANCE hết QR không xóa nợ; SUBSCRIPTION hết hạn không cấp gói. Webhook đúng hạn nhưng expiry đã commit không tự phục hồi domain.
- Phase 6 sở hữu phí hủy/30–70/handover/return/complete; Phase 8 sở hữu giá snapshot/kích hoạt/gia hạn/quota. Test handler trong Phase 4 không chứng minh các policy này đã triển khai.

## 3. Trạng thái và bất biến tiền

| Dữ liệu | Ý nghĩa |
|---|---|
| Obligation OPEN / SETTLED / CANCELLED / EXPIRED | Nghĩa vụ phải trả và amountSettled; không dùng để suy ra mọi khoản hoàn đã thành công. |
| Intent ACTIVE / RECEIVED / CANCELLED / EXPIRED / REVIEW_REQUIRED | Lần QR. REFUND_PENDING / REFUNDED / UNAVAILABLE là trạng thái hiển thị tổng hợp, không thêm enum DB. |
| Receipt APPLIED / REFUND_REQUIRED / REVIEW_REQUIRED | Tiền nhận được lưu riêng, exact VND BIGINT. Unknown code/lệch tiền không xác nhận đủ tiền hoặc tự cấp quyền lợi. |
| Refund WAITING_RECIPIENT / READY / WAITING_FUNDS / PROCESSING / REVIEW_REQUIRED / SUCCEEDED | Nghĩa vụ hoàn. Quá hạn là thuộc tính thời gian; success không biến thành thất bại. |
| Attempt PREPARED / UNKNOWN / PROCESSING / NOT_SENT / SUCCEEDED | Một lần chi có snapshot/source/key/reference bất biến. Chỉ kết quả khớp SUCCEEDED được hoàn tất. |

- Retry webhook cùng transaction không tạo receipt/refund mới; hai chuyển khoản thật có receipt riêng.
- Tổng hoàn không vượt budget receipt. Owner trả Customer từ kênh Owner; Admin trả Owner từ kênh Admin. Không fallback nguồn hoặc repoint credential reference đã có lịch sử; đổi nguồn phải tạo kênh mới.
- UNKNOWN/timeout/crash/mất response: GET theo provider ID hoặc lookup reference trên cùng nguồn; không POST lại, kể cả cùng key. Lookup rỗng không chứng minh chưa chi.
- Chỉ NOT_SENT chắc chắn + INSUFFICIENT_FUNDS/RETRYABLE mới cho attempt tiếp; PERMANENT/không phân loại cần review. Production adapter hiện chưa phát NOT_SENT; capability này vẫn phải xác minh ở P4.2-A.
- Crash sau PREPARED trước gửi có thể chờ UNKNOWN dài hạn; không đánh đổi an toàn bằng tự resend. dueAt không reset khi retry/restart/thiếu tiền. Cảnh báo quá hạn một lần atomic, không khóa Owner.

## 4. Sequence nền tài chính

```mermaid
sequenceDiagram
    participant Bank as SePay
    participant API as Webhook
    participant DB as MySQL
    participant Job as Maintenance
    participant Core as ReceiptApplicationService
    participant Handler as Domain handler
    participant Provider as payOS
    Bank->>API: Signed raw bytes
    API->>DB: Validate channel + commit inbox (dedupe)
    API-->>Bank: ACK after commit
    Job->>DB: Claim inbox + commit lease
    Job->>Core: Queue rental, begin apply transaction
    Core->>Handler: lockSubject (current aggregate)
    Core->>DB: Lock money rows, allocate receipt, flush
    Core->>Handler: apply(receiptId)
    Handler->>DB: Domain benefit/refund
    Core->>DB: Commit financial and domain changes
    Note over Handler,DB: Failure rolls back financial and domain writes together
    Job->>DB: Claim ready refund, persist attempt/snapshot/key + commit
    Job->>Provider: Create outside transaction
    alt Verified matched success
        Job->>DB: Fenced success + notification + commit
    else Response lost or uncertain
        Job->>DB: UNKNOWN, persisted reconciliation time
        Job->>Provider: Query original ID/reference on same source
        Job->>DB: Commit verified result; never resend UNKNOWN
    end
```

## 5. Schema và cutover

Nguồn schema vật lý: `scriptDB.sql`; không dùng Hibernate/startup DDL. Hai migration one-shot: `2026-10-27-01-payment-core.sql`, sau đó `2026-10-30-01-refund-accounts.sql`; xem `docs/migrations/README.md` cho backup/preflight/restore verification. Bảy bảng core + refund_recipient_accounts thuộc nền đã có; bảng/cột booking và subscription của Phase 6/8 vẫn là target.

DB ứng dụng hiện chưa migration. `payments`/seed legacy không được đổi thành hai đợt 30/70, không làm historical refund thành giao dịch ngân hàng. Các route `/payment/**` là core mới có guard; không biến endpoint mock cũ thành lệnh chi thật.

Trước cutover: migration diễn tập/backup, handler Phase 6/8 và E2E nghiệp vụ đạt, P4.2-A đủ bằng chứng Owner/Admin + lookup + capability/live, chọn chủ thể demo và review gate. `carrent.payment.maintenance.enabled=false` mặc định; không chuyển tiền lúc boot/test suite hoặc bật cho user khác chỉ vì core pass.

## 6. Bằng chứng nghiệm thu

Checkpoint đợt 7 trong `phase-4-payment.md` ghi kết quả chạy cuối. Test giữ trong repo:

- `SePayInboxTest`: signature/ACK sau commit, DB failure/retry và concurrent dedupe.
- `ReceiptApplicationTest`, `PaymentRecoveryTest`: apply/expiry/cancel, lease fencing, rollback, queue/restart.
- `PaymentIntentTest`: current read với entity cũ; xác nhận hai connection ID độc lập.
- `RefundServiceTest`, `RefundRecipientTest`: budget/idempotency/source/deadline/snapshot/quyền/CSRF.
- `PayoutWorkerTest`: dispatch/reconcile cạnh tranh, HTTP ngoài lock/transaction, crash/lost response/query-only.
- `PaymentMaintenance*Test`: scheduler thật, bounded queues, backoff/deadlines, overdue atomic và recovery.
- `FinancialViewsTest`: review receipts đúng chủ thể, phân trang, redaction, cùng trạng thái checkout, IDOR/role/ACTIVE guard.
- `PaymentFoundationTest`: HTTP webhook trùng → handler/refund → recipient HTTP → scheduler → lost response → restart/query → committed status/UI, trên cả nguồn Owner và Admin; rollback receipt/refund khi domain lỗi.

Không có human review hoặc live pass được suy ra từ automated tests. Reviewer được phân công: huy69332; thực hiện ngoài checkpoint kỹ thuật nếu chưa có bằng chứng review.
