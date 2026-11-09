# UI-03 — Bàn giao checkout, kết quả và hoàn tiền

Ngày 2026-11-08, root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. **UI-03 đạt kiểm chứng cục bộ; dừng trước UI-04.** Human visual review và native zoom 200% còn chờ; chưa nghiệm thu provider/tiền thật.

## 1. Đã thay đổi

- `payment/checkout.html`: layout A, hai nhóm desktop/stack mobile, số tiền attempt/nội dung/hạn QR rõ; QR giữ src backend/tỉ lệ, bối cảnh advance30% và balance70%/hạn nợ riêng. Giữ nút hủy đặt xe khác hủy attempt, cảnh báo và tải lại no-JS.
- `payment/result.html`: card kết quả có icon/message, mã/tiền, nhóm nợ/booking độc lập, CTA backend cho phép. ACTIVE của cả advance/balance/non-rental có đúng một link mở lại QR hiện hành; không tạo intent/deadline mới. Giữ terminal expression, cancel/retry và message thật.
- `payment/refund.html`: tóm tắt khoản hoàn, hai bước nhập → verifier preview → checkbox xác nhận; input/nút có target/focus/label, field error được nối `aria-invalid/aria-describedby`. Thông báo lỗi/flash và trạng thái chờ/theo dõi tách rõ. READY còn editable khi chưa lease/attempt; không đổi guard hoặc tự báo SUCCEEDED.
- `payment/refund-summary.html`: card tiền/status/quá hạn/hạn; model account luôn che, null có nhãn; chỉ recipient có link mở thông tin nhận hoàn.
- `payment/refund-links.html`: card từng refund, giữ signature `links`, null/empty guard và IDs, CTA WAITING_RECIPIENT; render lại cả result và subscription order, order APPLIED vẫn độc lập với refund pending.

Thay đổi chỉ năm template và tests: `PaymentIntentTest`, `RefundRecipientTest`, `src/test/js/payment-polling.test.cjs`. Không đổi Java main/controller/query, schema/seed ứng dụng, root POM, provider/config, CSS/JS production, header/workspace hoặc nội dung subscription order.

## 2. Interface phải giữ khi làm UI-04

`refund-links :: links` không có tham số, đọc `intentRefunds: List<FinancialViews.RefundRow>`, có hai caller `payment/result.html` và `subscription/detail.html`. Null/rỗng thì không render; không đưa `RefundView` chứa account/name đầy đủ vào fragment.

Recipient page dùng `RefundView` và chỉ recipient hiện tại được phép xem account/name đầy đủ. Summary/fragment dùng `RefundRow.account` đã che kể cả recipient/Admin. Quyền đọc summary/status của collector/Admin không cấp quyền xem/chỉnh recipient detail hoặc intent.

`PaymentIntentView.rental` có thể null. Không có field `canCreate`, orderId/tier hoặc bank label. Link ACTIVE hiện hành dùng GET intent; balance retry giữ `remainingAmount > 0 && !canCancel && status != UNAVAILABLE`, bên trong guards rental!=null/balance; mutation vẫn POST+CSRF. Expired/cancel attempt không xóa nợ hoặc đổi dueAt.

Giữ nguyên các ID và data attributes của polling: `payment-checkout`, `financial-status`, `payment-status`, `payment-qr`, `payment-cancel`, `polling-message`, `balance-result`, `intent-refunds`/heading; `data-status-url/result-url/expires-at/booking-advance/intent/state/terminal`. Result EXPIRED/CANCELLED/RECEIVED/ACTIVE/REFUND_PENDING tiếp tục polling; REVIEW_REQUIRED/UNAVAILABLE/SETTLED/REFUNDED dừng theo contract cũ. Refund chỉ SUCCEEDED terminal, overdue là phần state riêng.

## 3. Kết quả kiểm chứng

| Bước | Kết quả |
|---|---|
| Baseline JUnit | **133/133**,0 failure/error/skip |
| RED |2/2 fail đúng thiếu link ACTIVE và aria field error |
| Focused source cuối | **135/135**,0 failure/error/skip |
| Node baseline |6/6 |
| Node cuối |**8/8** gồm advance expiry/auth và overdue-only reload |
| Chrome |**828 kiểm tra** trong lượt ma trận cuối + **28 kiểm tra bổ sung** RECEIVED/no-JS checkout; cả hai lượt 0 JavaScript error |
| Ảnh |**36 trước / 134 sau** trong ma trận cuối + **4 ảnh bổ sung RECEIVED** |
| Pure reads/preview |**60 đoạn** trong ma trận cuối + **4 đoạn bổ sung**, 20 bảng không đổi trong từng đoạn |
| POST thật trong fixture |4 audit: confirm, cancel balance, retry, cancel advance; thay đổi đúng allowlist và invariant tiền/hạn |
| Provider |Fake payout create/get/find **0**; mock verifier **8** lần, external live HTTP không được phép |
| Cleanup |**6 schema** đã xóa và truy vấn lại; fixture port đóng, ready/credential đã dọn; port8080 giữ nguyên |

Suite gồm9 nhóm: `PaymentIntentTest`, `BeforeAcceptanceTest`, `RentalBalanceUiTest`, `RentalCancellationUiTest`, `RefundRecipientTest`, `RefundServiceTest`, `PaymentAccessAuditTest`, `SubscriptionOwnerUiTest`, `FinancialViewsTest`. Hai test Java mới bắt hành vi thật và đã RED trước sửa; hai test Node bổ sung là characterization đã pass trên JS cũ. Không gọi characterization pass là RED.

Browser tại1440×1000,1024×768,390×844,320×740 đã kiểm tra số tiền/hạn/code thật từ src model, reload không tạo QR/deadline mới, một h1, target của control đã sửa≥44px, no overflow, escaping và message/accessibility. Order caller chỉ kiểm tra fragment được đổi, không yêu cầu các control ngoài UI-03 đã được redesign.

Các ca gồm advance600.000/1.400.001, balance1.400.000 và gói5.000; result ACTIVE/RECEIVED/EXPIRED/CANCELLED/SETTLED/REVIEW_REQUIRED/REFUND_PENDING/REFUNDED/UNAVAILABLE; balance completed vẫn giữ refund/review riêng. Sáu trạng thái refund, deadline trước/đúng/sau, SUCCEEDED không overdue, READY editable hoặc bị khóa lease/attempt; preview không ghi, confirm READY/save opt-in, input giữ0đầu; BIN/account invalid400, verifier lỗi/echo mismatch/name đổi/missing confirm409.

Customer A/B, Owner recipient/collector/B, Admin, anonymous/stale được kiểm tra theo đúng route; summary account che, collector/Admin không mở recipient form hoặc intent. Hai duplicate refund hiện trên result/order APPLIED, SYSTEM_CONFIRMATION_DELAY giữ giải thích đúng. Browser kiểm tra polling đổi trạng thái từ fixture đã commit, offline/503/JSON lỗi/401/403/login redirect, CSS hidden và no-JS/keyboard. Node kiểm tra single in-flight, timeout10giây, hidden/visible, pagehide và persisted pageshow.

## 4. Evidence và lệnh

Từ root:

```powershell
.\mvnw.cmd -f .scratch/ui-03/pom.xml "-Dtest=PaymentIntentTest,BeforeAcceptanceTest,RentalBalanceUiTest,RentalCancellationUiTest,RefundRecipientTest,RefundServiceTest,PaymentAccessAuditTest,SubscriptionOwnerUiTest,FinancialViewsTest" test
node --test src/test/js/payment-polling.test.cjs
$env:UI03_OUT = '.scratch/ui-03/browser-final'
node .scratch/ui-03/browser-check.cjs
```

Node browser cần Java `Ui03Browser` fresh fixture đã boot và ready/metadata/credential mới; không chạy với runtime8080 hoặc reuse schema/credential đã dọn. POM/build/classes cách ly trong `.scratch/ui-03/`, không dùng root target làm output. Harness là evidence local, không phải prerequisite khi clone repo; tests trong src là regression đi cùng source.

- `.scratch/ui-03/baseline.json`, `port8080-before.json`: manifest tracked/untracked/file xóa sẵn, config hash/index/HEAD/branch và port ngay từ đầu.
- `baseline-tests.log`, `red-tests.log`, `green-tests.log`, `node-baseline.log`, `node-characterization.log`, `node-final.log`: giữ riêng các lượt và kết quả cuối.
- `browser-before/capture.json`:36 ảnh mới, fixture trước đã dọn.
- `browser-final/report.json`: browser checks/ảnh cuối; `read-post-audit.json`, SQL trước/segment/sau, provider-calls, metadata/cleanup và accessibility đi cùng.
- `browser-received/report.json`: lượt bổ sung ảnh RECEIVED ở bốn viewport và tải lại checkout khi tắt JavaScript; snapshot 20 bảng theo từng đoạn, payout 0, không có POST mutation.
- `schema-cleanup-audit.json`, `fixture-port-audit.json`, `validation.json`: cleanup, allowlist/hash, form/polling DOM contracts,63resource khớp build, index/config/HEAD giữ nguyên.

Ảnh đại diện:

- [Checkout desktop](../../.scratch/ui-03/browser-final/checkout-advance-1440.png), [320px](../../.scratch/ui-03/browser-final/checkout-advance-320.png).
- [Balance result](../../.scratch/ui-03/browser-final/result-active-balance-1440.png), [refund pending](../../.scratch/ui-03/browser-final/result-refund_pending-390.png).
- [Recipient nhập](../../.scratch/ui-03/browser-final/refund-entry-390.png), [preview](../../.scratch/ui-03/browser-final/refund-preview-320.png), [lỗi field](../../.scratch/ui-03/browser-final/refund-validation-error-390.png).
- [Summary collector](../../.scratch/ui-03/browser-final/refund-summary-collector-1440.png), [quá hạn](../../.scratch/ui-03/browser-final/refund-overdue-320.png).
- [Refund-links trên order](../../.scratch/ui-03/browser-final/subscription-order-refund-links-390.png).
- [Kết quả RECEIVED](../../.scratch/ui-03/browser-received/result-received-390.png).

Agent đã xem ảnh đại diện đủ bốn trang và fragment trên order; human visual review còn chờ.

## 5. Audit và giới hạn

- Không áp “mọi bảng trước/sau toàn phiên giống nhau” cho phiên có POST thật. Snapshot so sánh20 bảng có BLOB chuẩn hóa hex và self-check ổn định ở từng đoạn GET/poll/preview. Confirm được phép đổi refunds/saved-account, cancel/retry dùng allowlist riêng; expected tiền/recipient/channel/debt/deadline kiểm tra bằng literal. Command seed/clock tách khỏi read/POST audit.
- Fixture giữ flags handler suốt vòng đời, payment maintenance/rental deadline scheduler tắt; client `page.clock.setFixedTime` đồng bộ clock server fixture. Các trạng thái demo bổ sung được seed/commit trong schema riêng; không gọi trạng thái giả là money/provider thật.
- Ảnh QR dùng ảnh fixture qua request interception; src server vẫn được kiểm tra amount/code. Lượt ảnh trước dùng placeholder local chung; ảnh sau có nhãn fixture rõ. Không nghiệm thu mã QR ngân hàng hoặc CDN thật. Tailwind/fonts dùng cache, external request bị chặn.
- Seed đầu vướng constraint tổng booking do giá/ngày chưa khớp, và harness gọi nhầm tên `cancelOrder`; đã sửa setup đúng constraint/API, không đổi service. Lượt browser đầu bị polling trang cũ reload chồng navigation, đã tách trang trước khi điều hướng và giữ lỗi/ảnh riêng. Dispatcher fixture ban đầu đọc REFUND_LOCK như tên enum status; đã phân biệt lệnh lock/attempt trước nhánh state, không nới DB enum hoặc sửa nghiệp vụ.
- Ca account 51 chữ số ban đầu bị `maxlength="50"` cắt còn 50 nên không tới nhánh server invalid. Harness sau đó bỏ maxlength chỉ trong DOM của ca kiểm tra này để gửi đủ 51 chữ số; template sản phẩm giữ nguyên validation. Các lượt lỗi trước được giữ riêng, không tính vào lượt browser cuối.
- Không chạy full suite/JAR;135 focused tests, Node8 và browser là phạm vi kiểm chứng UI-03. Native browser zoom200% chưa thao tác, chỉ reflow720CSSpx. Lifecycle polling có Node characterization; không tuyên bố đã nghiệm thu native BFCache trên mọi browser.
- `.env`, local config/provider, backend, CSS/JS production, seed/uploads/index/HEAD và dirty changes có sẵn ngoài allowlist giữ nguyên. `uploads/seed-car-5.png` đã xóa từ baseline, không phục hồi.
- Hai tài liệu phân tích provider xuất hiện sau baseline: `p42a-p88-completion-analysis-2026-11-08.md` và `p42a-provider-contract-analysis-2026-11-08.md`. Giữ nguyên và ghi hash riêng tại `.scratch/ui-03/concurrent-documents.json`; không tính chúng là thay đổi UI-03 hoặc viết lại baseline gốc. Lượt audit đầu dừng vì hai file mới ngoài allowlist; lượt cuối phân biệt rõ các file này và vẫn yêu cầu mọi file đã tồn tại ngoài phạm vi giữ hash cũ.

## 6. Điểm dừng

Đã cập nhật đúng checklist triển khai/kiểm chứng UI-03; UI-04 và các đợt sau giữ mở. Không stage/commit/push, đổi nhánh, restart8080, bật provider/scheduler ứng dụng hoặc chuyển tiền thật. Gate P4.2-A/P5.9/P8.8/human/live giữ trạng thái riêng; UI không thay số task nghiệp vụ. Khi được giao UI-04, dùng interface refund-links ở §2 và baseline/fixture mới.
