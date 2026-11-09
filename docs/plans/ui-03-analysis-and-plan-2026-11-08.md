# UI-03 — Checkout, kết quả và hoàn tiền: Analysis and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL khi được giao triển khai: dùng `superpowers:executing-plans`, thực hiện đúng UI-03, kiểm chứng và bàn giao rồi dừng trước UI-04. Không dùng subagent nếu chưa được yêu cầu. Các checkbox triển khai đã cập nhật theo [bàn giao UI-03](ui-03-handoff.md); nội dung phân tích bên dưới giữ snapshot trước triển khai.

**UI-03 — 2026-11-08:** đã triển khai checkout/result/refund/refund-summary và refund-links; focused **135/135**, Node **8/8**, Chrome **828 kiểm tra + 28 bổ sung**, **36 ảnh trước/138 ảnh sau**. GET/poll/preview kiểm tra 20 bảng theo từng đoạn; 4 POST audit giữ invariant, fake payout 0; cleanup đã xác nhận. Xem [bàn giao UI-03](ui-03-handoff.md). Human visual review/native zoom 200% còn chờ; UI-04 trở đi và các gate live giữ mở.

**Goal:** Hoàn thiện bốn màn checkout/result/refund/refund-summary cùng fragment refund-links, giúp người dùng đọc đúng khoản phải trả, kết quả tiền và bước tiếp theo.

**Architecture:** Giữ layout A của màn giao dịch theo SDD §8.7.3, dùng public header UI-01 cho mọi role và token Stitch hiện có. Tổ chức lại markup trong năm template, giữ nguyên model/controller/POST/polling; fragment `links` tiếp tục dùng chung cho result và subscription order. Không cần fragment generic hoặc dependency mới.

**Tech Stack:** Java21, Spring Boot4.1.1, Thymeleaf/Spring Security, MySQL fixture, Tailwind Play CDN, Inter/Material Symbols; Node built-in test runner và Playwright/Chrome cho kiểm chứng.

**Spec:** [SDD §8.7](../SDD.md#87-hoàn-thiện-thiết-kế-ui-sau-các-đợt-triển-khai-chức-năng--2026-11-07), [backlog/wireframe §3.4 và UI-03](ui-completion-2026-11-07.md), [kế hoạch 15 trang](ui-15-pages-readiness-2026-11-08.md), [bàn giao UI-02](ui-02-handoff.md), [prototype thanh toán](../design/stitch/payment-thanh-toan.html).

## Global Constraints

- Chỉ UI-03: bốn trang và refund-links. UI-04 order/plans, Admin dashboard, workflow booking/inspection và profile giữ phạm vi riêng; kiểm tra caller của fragment không có nghĩa redesign caller.
- Làm trên root đã được chọn, giữ mọi dirty changes. Không checkout/reset/stash/merge/stage/commit/push hoặc tự tạo/chuyển worktree.
- Không sửa Java main, root POM, schema/seed ứng dụng, provider/config, `.env`, `application-local.properties`, scheduler hoặc restart8080. Chỉ fixture schema/port riêng mới được seed, đổi clock, chạy POST và restart khi cần.
- Giữ URL/method, model/field, validation, CSRF, điều kiện quyền/trạng thái, escaping/masking và toàn bộ ID/data attributes của polling. JavaScript production mặc định giữ nguyên.
- Không thêm phương thức thanh toán, tự xác nhận đã chuyển tiền, QR mô phỏng trong sản phẩm, copy bank config vào HTML, countdown mới hoặc API mới.
- Chỉ dữ liệu backend được dùng làm số tiền/trạng thái/hạn. Không suy booking hoàn tất từ receipt, suy đã hoàn từ READY hoặc tính lại 30/70 tại template.
- Giữ tiếng Việt, VNĐ và giờ `Asia/Ho_Chi_Minh`; input tài khoản dạng text với `inputmode="numeric"` để giữ số0 đầu.
- Human visual review, native zoom200%, provider/pilot/live là các gate riêng. Chưa đạt thì ghi rõ, không đóng gate nghiệp vụ bằng bằng chứng CSS/fixture.

## 1. Baseline và mức sẵn sàng

- Root `C:/Users/Admin/Documents/hoc/Car`, branch `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. Git hiện đăng ký **6 worktree**, khác snapshot5 cũ; không chọn baseline bằng số worktree trong tài liệu lịch sử.
- Manifest phiên này: `.scratch/ui-03-analysis/baseline.json`, **424 tracked/untracked file path**, gồm file bị xóa sẵn và hash config/index bảo vệ. Đây là số path đã khai triển, không phải số entry trong `git status`.
- **63/63 resource khớp byte với build UI-02**; evidence `.scratch/ui-03-analysis/ui02-resource-match.json`. Năm template UI-03 và polling vẫn là bản chức năng cũ.
- UI-02 đã kiểm chứng trước đó: focused47/47, browser1233 checks,28 ảnh trước/139 ảnh sau,20 bảng/27 đoạn GET và provider0; schema/port đã dọn. Đây là evidence UI-02, không phải baseline HTTP/browser UI-03 mới.
- Phiên phân tích UI-03 vừa chạy `node --test src/test/js/payment-polling.test.cjs`: **6/6 pass**,0 fail/skip. Log `.scratch/ui-03-analysis/polling-baseline.log`. Chưa chạy Maven, browser, DB hoặc server trong phiên phân tích này.
- Chưa có ảnh UI-03 mới cho đủ trạng thái. Khi implement phải chụp trước trên fixture mới trước khi thay resource build; không tái dùng ready/credential/schema đã dọn của UI-02.

**Kết luận:** có đủ spec và hợp đồng source để giao UI-03. Các rủi ro chính nằm ở cách trình bày trạng thái, form nhận hoàn và tương tác DOM/polling; không cần đổi luồng backend để chuẩn bị UI.

## 2. File, caller và ranh giới thay đổi

Đường template tính từ `src/main/resources/templates/`.

| File | Nội dung được triển khai | Caller/hồi quy |
|---|---|---|
| `payment/checkout.html` | Số tiền/nội dung/hạn, QR, ngữ cảnh 30/70, tải lại/hủy | `/payment/intents/{id}` chỉ khi status ACTIVE |
| `payment/result.html` | Kết quả, thông tin nợ/booking độc lập, hành động hợp lệ, refund links | GET intent không ACTIVE và GET `/result` mọi status |
| `payment/refund.html` | Nhập → verifier preview → xác nhận, tracking và lỗi | Recipient Customer thuê hoặc Owner mua gói |
| `payment/refund-summary.html` | Tiền/status/hạn/account đã che; link recipient theo model | Recipient/collector/Admin theo `FinancialViews` |
| `payment/refund-links.html` | Card từng khoản hoàn; giữ `links` không tham số | **Hai caller thật:** result và `subscription/detail.html` |
| `src/test/java/com/carrent/payment/PaymentIntentTest.java` | Thêm kiểm tra link mở lại QR trên result ACTIVE | Giữ server amount/deadline/CSRF/quyền hiện có |
| `src/test/java/com/carrent/payment/RefundRecipientTest.java` | Bổ sung HTML validation/error accessibility | Không nới assertion xác minh/chủ thể/deadline |
| `src/test/js/payment-polling.test.cjs` | Thêm characterization advance expiry/auth và overdue-only reload | Helper VM; không đổi JS production để làm test pass |
| `.scratch/ui-03/`, `docs/plans/ui-03-handoff.md` | Fixture/output/evidence và bàn giao khi triển khai | Cập nhật đúng UI-03 trong các checklist/SDD sau kiểm chứng |

Mặc định giữ `fragments/head/header/workspace`, CSS/JS, `subscription/detail.html`, booking detail và finances UI-02 nguyên trạng. Kiểm tra chúng như caller. Nếu phát hiện lỗi backend/JS thật ngoài việc sắp markup, giữ bằng chứng và ghi issue riêng, không âm thầm mở rộng đợt UI.

### Prototype cần chuyển theo hợp đồng mới

`payment-thanh-toan.html` là prototype cũ có SVG QR giả, radio các phương thức, thông tin xe/ngân hàng mẫu và nút mô phỏng “Xác nhận đã chuyển khoản”. SDD §8.7 thay hợp đồng mock cũ: dùng bố cục card/grid, spacing, icon và token; dữ liệu/thao tác lấy đúng các phần §3–6 bên dưới. Không port những control mô phỏng hoặc câu “xác nhận tức thì/miễn phí” thành cam kết của luồng hiện hành.

## 3. Route, model và quyền

### 3.1. Route thật

| Route | Method/kết quả | Điều phải giữ |
|---|---|---|
| `/payment/obligations/{id}/attempts` | POST → redirect checkout | Payer, CSRF, server amount và điều kiện tạo/reuse |
| `/payment/intents/{id}` | GET → checkout nếu ACTIVE, còn lại result | Read-only, no-store; không đổi deadline |
| `/payment/intents/{id}/result` | GET → result cả khi ACTIVE | Link tải lại/mở QR không tạo intent mới |
| `/payment/intents/{id}/status` | GET JSON `PaymentIntentView` | Payer hiện tại; polling no-store |
| `/payment/intents/{id}/cancel` | POST → `/result` | Advance hủy booking unpaid; balance chỉ kết thúc attempt |
| `/payment/refunds/{id}` | GET → recipient detail | Chỉ recipient đúng role/current ACTIVE |
| `/payment/refunds/{id}/recipient/preview` | POST → HTML200/400/409 | Verifier, không ghi account/deadline, không payout |
| `/payment/refunds/{id}/recipient` | POST → redirect hoặc HTML400/409 | Reverify + checkbox + CSRF, không trả SUCCEEDED |
| `/payment/finances/refunds/{id}` | GET → summary hoặc unavailable | `RefundRow` đã che account, phạm vi riêng |
| `/payment/refunds/{id}/status` | GET JSON status hoặc503 khi module thiếu | Recipient/collector/Admin đọc được theo scope; không đồng nghĩa được sửa |

`PaymentController` trả BusinessException dưới dạng **409 JSON `{message}`**, không phải form lỗi HTML mới. Module thiếu ở `FinancialController` trả `payment/finance-unavailable` hoặc JSON503. UI-03 giữ fallback này, không tạo model giả để ép render template.

### 3.2. Các model khác nhau

**`payment: PaymentIntentView`** có `id,code,amount,expiresAt,status,qrUrl,rental` và `message()`.

`rental` có `bookingId,advanceAmount,balanceAmount,cancelled,canCancel,confirmationDelayed,balance,obligationId,remainingAmount,dueAt,completed`. Nó có thể null. Không có `canCreate`, order ID/tier, tên xe/khách, bank label hoặc tài khoản thụ hưởng tách riêng. Không tách/giải mã `qrUrl` để dựng field mới. Khi rental null, dùng mô tả thanh toán trung tính, không tự khẳng định một tier cụ thể.

**`refund: RefundView`** trên recipient page có `id,amount,reason,status,bankBin,accountNumber,accountName,readyAt,dueAt,editable,overdue`; account/name đầy đủ chỉ ở route recipient được backend cho phép.

**`refund: FinancialViews.RefundRow`** trên summary có `id,amount,reason,status,account,dueAt,overdue,recipient`; `account` đã được che cho **mọi người**, kể cả recipient/Admin. Không gọi `accountNumber/accountName/editable` tại summary hoặc đưa `RefundView` sang fragment chung.

**`recipientForm`** có `bankBin,accountNumber,saveForLater,confirmedAccountName`; **`destination`** là verifier `Destination(bankBin,accountNumber,accountName)` hoặc null. `confirmed` là request boolean riêng, không phải property của form.

**`intentRefunds`** là danh sách `RefundRow`, null/rỗng thì fragment không render. `RefundReason` không có `message()`; giữ switch nhãn tiếng Việt hiện hành.

### 3.3. Ma trận quyền

| Trường hợp | Checkout/result/status | Recipient detail/preview/confirm | Summary/refund status |
|---|---|---|---|
| Thuê xe: Customer A là payer/recipient | Có | Có | Có, account che |
| Thuê xe: Owner A là collector |403|403|Có, chỉ đọc/account che |
| Thuê xe: Admin |403|403|Có, chỉ đọc/account che |
| Thuê xe: Customer B/Owner B |403|403|403|
| Gói: Owner A là payer/recipient |Có|Có|Có, account che |
| Gói: Admin collector |403|403|Có, chỉ đọc/account che |
| Gói: Owner B/Customer |403|403|403|
| Anonymous |302 login|302 login khi có CSRF hợp lệ cho POST; thiếu CSRF có thể403|302 login|

Stale BANNED/INACTIVE/role đổi bị chặn bởi service hiện tại. Admin không có quyền xem intent chỉ vì được đọc summary. Quyền của summary/status không được dùng làm quyền chỉnh recipient.

## 4. Checkout/result: trạng thái và hành động

### 4.1. Ý nghĩa tiền và deadline

- `payment.amount` là số tiền của **attempt**, không tự thay bằng `remainingAmount`; hai giá trị có thể khác khi có nhiều QR/lịch sử.
- Advance30%: `advanceAmount` và `balanceAmount` là snapshot server, gồm trường hợp tổng2.000.001 →600.000/1.400.001. Không nhân/chia phần trăm tại Thymeleaf. Đã đồng ý BEFORE không phải đã nhận xe; receipt hợp lệ mới xác nhận đặt xe, hợp đồng/giao xe riêng.
- Balance70%: `remainingAmount` là nợ hiện tại; `dueAt` là hạn nợ sau nhận lại xe, độc lập `expiresAt` của QR. QR15phút hết/hủy không xóa nợ hoặc đổi dueAt; quá hạn vẫn có thể trả.
- `completed` là trạng thái booking từ model. Balance0/COMPLETED có thể cùng REFUND_PENDING/REFUNDED do tiền dư/trùng; vẫn hiện cả hai nhóm thông tin.
- SYSTEM_CONFIRMATION_DELAY: tiền vào đúng hạn nhưng xác nhận trễ, booking đã hết giữ chỗ và hoàn đủ; giữ message backend và giải thích trong summary. Không đổi thành lỗi khách chuyển chậm.
- Gói đã APPLIED và refund trùng/muộn là hai trạng thái độc lập. Fragment không sửa nhãn order, quota hoặc thời hạn gói.

### 4.2. Result terminal phải giữ đúng

| `payment.status` | Polling result hiện hành | Cách trình bày |
|---|---|---|
| ACTIVE |Tiếp tục|Chờ thanh toán, mở QR đang có hiệu lực bằng GET |
| RECEIVED |Tiếp tục|Đã nhận tiền theo message backend; không tự báo giao xe/hoàn tất gói |
| EXPIRED/CANCELLED |Tiếp tục|Lần QR kết thúc, vẫn có thể ghi nhận receipt muộn |
| REFUND_PENDING |Tiếp tục|Chờ hoàn; không dùng màu/chữ đã hoàn |
| REFUNDED |Dừng|Đã hoàn theo backend, booking/nợ vẫn có phần riêng |
| SETTLED |Dừng|Balance đủ tiền; giữ `completed`/receipt cần kiểm tra độc lập |
| REVIEW_REQUIRED |Dừng|Cần kiểm tra và tải lại thủ công; không đổi terminal để “live” hơn |
| UNAVAILABLE |Dừng|Ẩn QR theo backend, có tải lại; không dựng thành zero/paid |

Không thêm enum mới hoặc đổi `payment.message()`. Dùng màu/icon để hỗ trợ message, không thay message bằng trạng thái do UI tự suy.

### 4.3. Điều kiện CTA thật

Backlog §3.4 dùng từ `canCreate` nhưng model không có field này. Bản triển khai phải dùng điều kiện hiện hữu:

```html
<form th:if="${payment.rental.remainingAmount > 0 and !payment.rental.canCancel and payment.status != 'UNAVAILABLE'}"
      method="post" th:action="@{/payment/obligations/{id}/attempts(id=${payment.rental.obligationId})}">
  <button type="submit" class="min-h-11 rounded-xl bg-secondary text-on-secondary px-4 py-3">Tạo lần thanh toán mới</button>
</form>
```

Form trên chỉ nằm **bên trong guard rental != null và rental.balance**. Giữ form cancel balance khi `rental.canCancel`; đây có thể là REVIEW_REQUIRED còn active attempt, không chỉ status ACTIVE. Giữ cancel advance khi `!rental.balance && rental.canCancel`, nhãn **Hủy đặt xe**. Checkout luôn giữ form cancel theo controller hiện hành.

Mở QR trên result ACTIVE nên có đúng một link chung cho advance/balance/non-rental; chuyển link balance hiện có ra vùng dùng chung:

```html
<a th:if="${payment.status == 'ACTIVE'}" th:href="@{/payment/intents/{id}(id=${payment.id})}"
   class="min-h-11 inline-flex items-center rounded-xl bg-secondary text-on-secondary px-4 py-3">Mở mã QR đang có hiệu lực</a>
```

Không tạo retry/subscription order mới khi model không có điều kiện/ID tương ứng. Các link booking, finance refunds, reload và home hiện có giữ route thật. Reload result hiện hành đi qua GET intent và có thể trả checkout khi ACTIVE; không đổi thành thao tác tạo lại QR.

### 4.4. Bố cục đề xuất

```text
Checkout — max-w-4xl, layout A
Thanh toán chuyển khoản / trạng thái backend
SỐ TIỀN ATTEMPT + ngữ cảnh trả trước/phần còn lại nếu có
[QR nguyên tỉ lệ + nội dung + hạn QR] [30/70 hoặc nợ/hạn trả + hướng dẫn]
[Tải lại trạng thái] [Hủy đặt xe / Hủy lần thanh toán]

Result — card kết quả, max-w-4xl
Icon + Trạng thái thanh toán / message backend
Mã và số tiền attempt
[Booking/nợ 70%/hạn thật] [Hành động được phép]
Danh sách từng refund khi có
Polling-message / tải lại / điều hướng
```

Mobile1cột, số tiền/bối cảnh dễ thấy trước thao tác QR, chữ/mã dài wrap; desktop2cột khi đủ chỗ. QR dùng ảnh model, `object-contain`, `h-auto`, không `object-cover`, không crop wrapper. Không nhét full bank data từ config vào phần hướng dẫn. Heading dùng cặp `font-title-md text-title-md`; main dùng padding thay margin first-child bị head reset.

## 5. Recipient/summary/refund-links

### Recipient

- Card tóm tắt amount/status/deadline luôn nhìn thấy. Hai bước cung cấp tài khoản: **Nhập thông tin → Kiểm tra và xác nhận**. Đây là bước form, không phải tiến độ chi tiền.
- Bước nhập khi `refund.editable && destination == null`: BIN6 chữ số ASCII, account1–50 chữ số ASCII, opt-in `saveForLater`. Label/for, helper/error có ID, input và nút≥44px, `aria-invalid`/`aria-describedby` theo BindingResult.
- Bước preview khi `refund.editable && destination != null`: hiện BIN/account/name verifier, giữ hidden `bankBin,accountNumber,saveForLater,confirmedAccountName`, checkbox `name="confirmed" value="true" required`, link nhập lại GET. Không cho nhập tay tên xác minh.
- Preview không ghi DB/không gửi payout. Confirm **gọi verifier lại**, kiểm tra tên/nguồn/quyền/lease/attempt hiện tại; thành công chỉ chuyển READY. `readyAt/dueAt` đặt một lần; sửa tài khoản hợp lệ trước dispatch không gia hạn.
- `editable` chỉ WAITING_RECIPIENT hoặc READY **và** chưa lease/attempt. READY vẫn có thể sửa trước khi dispatch; không khóa form chỉ theo tên READY và cũng không mở form chỉ theo status nếu editable=false.
- Field400: giữ input hợp lệ, lỗi bên field; lookup/name mismatch/missing checkbox/source conflict409: giữ errorMessage và trở lại bước nhập vì controller không cấp destination khi thất bại. Không dùng UI để biến lỗi thành preview thành công.
- Saved account chỉ điền sẵn, không tự xác nhận refund mới hoặc tự đặt deadline. Mặc định saveForLater không được tự tick thay người dùng.
- WAITING_FUNDS/PROCESSING/REVIEW_REQUIRED/SUCCEEDED chỉ theo dõi theo model, không có nút “Hoàn ngay”. Refund SUCCEEDED không overdue dù dueAt đã qua. dueAt null không tự dựng deadline.

### Summary và fragment

- Summary cùng hệ card/status/time với recipient nhưng chỉ `RefundRow.account` đã che; null có nhãn chưa có thông tin. CTA recipient chỉ theo `refund.recipient`; collector/Admin không có recipient form/accountName/BIN.
- `links` giữ null/empty guard, `intent-refunds`/heading IDs và mỗi refund một card, một link summary. CTA bổ sung tài khoản chỉ khi status WAITING_RECIPIENT như hiện hành; không mở rộng sang mọi state.
- Giữ switch lý do DUPLICATE_PAYMENT/LATE_SUBSCRIPTION/CANCELLED_SUBSCRIPTION/INCOMPATIBLE_SUBSCRIPTION và fallback hiện hành. Không tạo helper `reason.message()`.
- Result và subscription order phải render được danh sách nhiều refund; order APPLIED không bị đổi thành chờ kích hoạt khi còn refund. Không thêm tổng refund hoặc suy số tiền khả dụng.

## 6. Polling: DOM là interface phải giữ

| Node/attribute | Caller JS và yêu cầu |
|---|---|
| `#payment-checkout` | Root ưu tiên của checkout; chỉ một node |
| `#financial-status` | Root result/refund/summary; chỉ một node trên mỗi page |
| `#payment-qr`, `#payment-cancel` | JS ghi `.hidden`; không nhân đôi DOM desktop/mobile |
| `#payment-status` | Message checkout/result; checkout lỗi polling ghi textContent vào node này |
| `#polling-message` | Message result/refund/summary, role status + aria-live polite, không giấu trong details |
| `data-status-url`, `data-result-url`, `data-expires-at`, `data-booking-advance` | Checkout URL/deadline/ngoại lệ giữ nút hủy advance khi QR hết hạn |
| `data-intent="true"`, `data-state`, `data-terminal` | Result so sánh status; refund so sánh `STATUS:overdue` |
| `#balance-result`, `#intent-refunds`, `#intent-refunds-heading` | Selector/semantics đang dùng trong template và regression |

JS hiện có: poll2giây, timeout10giây, single in-flight, hidden tab abort/visible resume, pagehide stop/BFCache resume. Auth401/403/redirect dừng, ẩn QR và cancel, yêu cầu login. Network/503/JSON lỗi hiện thông báo không chuyển thêm tiền rồi retry. Checkout đổi status → redirect result, financial-status đổi state → reload; terminal không khởi động polling.

**Rủi ro CSS cần kiểm tra thật:** Tailwind `block/flex/grid` trên chính img/form có thể thắng user-agent `[hidden]`. Giữ style bố cục trên wrapper và tránh display utility trên `#payment-qr/#payment-cancel`, hoặc dùng explicit variant bảo vệ `[hidden]` nếu cần. Khi JS đặt hidden, browser phải kiểm tra `isVisible() === false`, không chỉ property `hidden === true`. Advance QR hết hạn giữ nút hủy đặt xe; auth thay đổi phải ẩn cả nút đó.

Không thêm countdown dựa clock client. Expiry timer sẵn có chỉ ẩn QR và không được dùng làm bằng chứng tiền đã trả/hủy booking.

## 7. Kế hoạch triển khai UI-03

Ba checkpoint cùng một đợt. Setup/fixture/docs nằm trong checkpoint cần chúng; không tạo thêm task nghiệp vụ.

### Checkpoint A — Checkout và result

**Files:** hai template payment, `PaymentIntentTest.java`, harness/output `.scratch/ui-03/`.

**Interface:** nhận `PaymentIntentView`/`intentRefunds`, giữ DOM §6 và POST §3; tạo bố cục §4 cùng link ACTIVE dùng lại QR.

- [x] Lưu baseline mới, xác nhận local JDBC/schema mới/port0/fake provider, tạo POM/output riêng; chạy focused baseline và Node6, chụp trước bốn page cùng order caller trên resource chưa sửa.
- [x] Thêm test RED trong `PaymentIntentTest` cho link mở lại QR trên result ACTIVE non-rental; chạy riêng xác nhận fail do thiếu CTA.

```java
@Test void activeResultLetsThePayerReopenTheExistingQr() throws Exception {
    var intent = f.payments.openAttempt(201L, f.actor(4));
    String page = f.mvc.perform(get("/payment/intents/" + intent.id() + "/result")
                    .with(user(f.actor(4)))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(page).contains("Mở mã QR đang có hiệu lực", "href=\"/payment/intents/" + intent.id() + "\"");
    assertThat(f.count("payment_intents")).isEqualTo(1);
    assertThat(f.payments.status(intent.id(), f.actor(4)).expiresAt()).isEqualTo(intent.expiresAt());
}
```

- [x] Dựng checkout theo §4.4: giữ amount/code/time thật, bối cảnh30/70, ảnh model, cancel form và no-JS reload; sửa cặp font/text token, không thêm data field.
- [x] Dựng result và di chuyển link ACTIVE thành link chung đúng một lần; giữ guards rental/balance/canCancel/retry, terminal expression nguyên nghĩa, message và refund fragment.
- [x] Chạy `PaymentIntentTest,BeforeAcceptanceTest,RentalBalanceUiTest`; browser ACTIVE advance/balance/non-rental, EXPIRED/CANCELLED/RECEIVED/SETTLED/REVIEW_REQUIRED/REFUND_PENDING/REFUNDED/UNAVAILABLE, kiểm tra QR thực sự ẩn và amount/deadline không đổi khi reload.

### Checkpoint B — Recipient, summary và fragment dùng chung

**Files:** ba template refund, `RefundRecipientTest.java`; caller order chỉ kiểm tra, không redesign.

**Interface:** nhận đúng `RefundView` hoặc `RefundRow` theo page, `recipientForm/destination`; giữ `links` không tham số và hai caller. Trả presentation đúng bước và trạng thái; không thay transaction.

- [x] Thêm RED accessibility cho response field400 trong `RefundRecipientTest`: input bank invalid có aria-invalid và liên kết lỗi. Giữ các assertion400/409/no mutation cũ.

```java
@Test void invalidBankIsAssociatedWithItsFieldError() throws Exception {
    long id = refund();
    String page = f.mvc.perform(post("/payment/refunds/" + id + "/recipient/preview")
                    .with(user(f.actor(4))).with(csrf())
                    .param("bankBin", "97042").param("accountNumber", "123"))
            .andExpect(status().isBadRequest()).andReturn().getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var input = java.util.regex.Pattern.compile("<input[^>]*id=\"bankBin\"[^>]*>").matcher(page);
    assertThat(input.find()).isTrue();
    assertThat(input.group()).contains("aria-invalid=\"true\"", "bankBin-error");
    assertThat(page).contains("id=\"bankBin-error\"", "Mã ngân hàng phải gồm 6 chữ số.");
    assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds WHERE id=?", java.sql.Timestamp.class, id)).isNull();
    assertThat(f.count("payout_attempts")).isZero();
}
```

Ví dụ triển khai field, kết hợp error paragraph mới với label/input hiện hành:

```html
<input id="bankBin" th:field="*{bankBin}" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required
       th:attr="aria-invalid=${#fields.hasErrors('bankBin')},aria-describedby=${#fields.hasErrors('bankBin') ? 'bank-help bankBin-error' : 'bank-help'}"
       class="block w-full min-h-11 border border-outline rounded-xl px-3 py-3 focus-visible:outline focus-visible:outline-2 focus-visible:outline-secondary"/>
<p id="bankBin-error" th:if="${#fields.hasErrors('bankBin')}" th:errors="*{bankBin}" class="text-error"></p>
```

- [x] Dựng recipient hai bước theo §5, giữ allowlist/hidden/checkbox/name escaping; state theo editable và destination, deadline/status/flash/error luôn đọc được, không đổi API hoặc auto-submit.
- [x] Dựng summary và cards links, giữ masking/recipient guard/null guard/nhãn lý do; render lại result và subscription order APPLIED/REFUND_PENDING/multiple refunds, không sửa order body.
- [x] Chạy `RefundRecipientTest,RefundServiceTest,FinancialViewsTest,RentalCancellationUiTest,SubscriptionOwnerUiTest`; browser preview → confirm READY, READY trước/sau lease/attempt, name mismatch/provider failure, saved opt-in, collector/Admin summary và cross-account403.

### Checkpoint C — Polling, invariants và bàn giao

**Files:** test Node, harness/evidence, `docs/plans/ui-03-handoff.md`; update đúng UI-03 trong README/SDD/checklists sau kết quả thật.

**Interface:** giữ nguyên JS production và selectors, test các nhánh ngoài coverage Node6; phân biệt read-only/mutation scope §8.

- [x] Bổ sung hai characterization tests Node: advance expiry giữ cancel nhưng auth thay đổi ẩn cancel; refund overdue đổi mà status không đổi vẫn reload. Helper thêm option `bookingAdvance=false` và dataset `bookingAdvance: String(bookingAdvance)`; giữ các option cũ.

```javascript
test('advance expiry keeps booking cancellation until authentication changes', async () => {
  for (const status of [401, 403]) {
    const b = browser({ checkout: true, bookingAdvance: true, fetcher: () => ({ status }) });
    assert.equal(b.qr.hidden, true);
    assert.equal(b.cancel.hidden, false);
    await b.tick();
    assert.equal(b.cancel.hidden, true);
    assert.equal(b.count(2000), 0);
  }
});
test('refund overdue change reloads even when status remains READY', async () => {
  const b = browser({ fetcher: () => response('READY', true) });
  await b.tick();
  assert.equal(b.reloads, 1);
  assert.equal(b.count(2000), 0);
});
```

Hai characterization test có thể pass trước sửa template; không gọi chúng là RED. RED của đợt là CTA ACTIVE và field-error accessibility ở trên. Không thêm test chỉ kiểm tra chuỗi class/mirror template.

- [x] Chạy focused suite hợp nhất §8.1 và Node suite cuối; giữ log baseline/RED/fail/rerun riêng, đọc số test thực tế thay vì lấy count từ UI-02.
- [x] Browser matrix §8.2 tại1440×1000/1024×768/390×844/320×740; native no-JS, keyboard, bối cảnh/ràng buộc tiền, CSS hidden, network/auth/timeouts/BFCache. Xem ảnh đại diện đủ bốn trang và order caller; ghi human review/zoom thật riêng.
- [x] Đối chiếu20 bảng quanh GET/poll/preview; confirm/cancel/retry có whitelist mutation và expected literal riêng. Fake payout create/get/find0, live HTTP0; ghi số lần mock verifier riêng, không gọi mock verification là payout.
- [x] Hash runtime/backend/config/index/HEAD theo allowlist; dọn tất cả fixture schema/port/credentials, viết handoff đủ contracts/evidence/giới hạn, cập nhật checkbox UI-03 và dừng trước UI-04.

## 8. Chiến lược fixture và kiểm chứng

### 8.1. Lệnh và baseline khi implement

Kiểm tra JDBC host local bằng parse/hash, không in username/password. Manifest mới gồm tracked/untracked nonignored, file xóa sẵn, HEAD/branch/index, hash `.env`/local config. Capture port8080 bằng object/array rõ ràng ngay từ đầu để query rỗng vẫn tạo JSON.

Nếu root POM còn khớp UI-02 baseline, tạo POM riêng từ bản đã dùng, chỉ đổi output:

```powershell
$ui03Baseline = Get-Content -Raw .scratch/ui-02/baseline.json | ConvertFrom-Json
if ((Get-FileHash pom.xml -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ui03Baseline.files.'pom.xml') {
    throw 'Root POM changed; regenerate isolated POM from the current root.'
}
New-Item -ItemType Directory -Force .scratch/ui-03 | Out-Null
$ui03Pom = (Get-Content -Raw .scratch/ui-02/pom.xml).Replace('/.scratch/ui-02/build', '/.scratch/ui-03/build')
[System.IO.File]::WriteAllText((Join-Path $PWD '.scratch/ui-03/pom.xml'), $ui03Pom, [System.Text.UTF8Encoding]::new($false))
.\mvnw.cmd -f .scratch/ui-03/pom.xml "-Dtest=PaymentIntentTest,BeforeAcceptanceTest,RentalBalanceUiTest,RentalCancellationUiTest,RefundRecipientTest,RefundServiceTest,PaymentAccessAuditTest,SubscriptionOwnerUiTest,FinancialViewsTest" test
node --test src/test/js/payment-polling.test.cjs
```

Đây là lệnh tương lai, chưa chạy Maven trong phiên phân tích. Node6 đã chạy trên source hiện tại; sau thêm hai tests dự kiến8, đọc TAP thực tế. Không cần full suite/JAR cho mỗi thay đổi padding; chỉ mở rộng khi có failure/concern cụ thể.

### 8.2. Matrix bắt buộc

| Nhóm | Ca | Kỳ vọng trọng tâm |
|---|---|---|
| Checkout | Advance snapshot600.000/1.400.001; balance1.400.000; subscription SMALL5.000 | Amount attempt, code, hạn thật; hai label hủy khác nghĩa; không nhúng tier giả |
| Result | Chín status §4.2, balance0/completed với refund pending và receipt review | Status/nợ/booking/refund tách rõ; không xanh thành công cho review/pending |
| CTA | Result ACTIVE cả ba loại; balance expired/cancelled; review active canCancel; unavailable | Một GET mở QR; retry/cancel giữ predicate/POST/CSRF, không tạo action thiếu ID |
| Refund form | WAITING_RECIPIENT, saved prefill, preview, confirm READY, READY editable/locked | Preview read-only, confirm không payout, deadline một lần, khóa theo editable |
| Refund states | Sáu enum, overdue trước1giây/đúng hạn/sau1giây, SUCCEEDED đã qua hạn | Overdue riêng; null dueAt không tự đặt hạn, SUCCEEDED không overdue |
| Errors | BIN5/Unicode digit; account space/51digits; name đổi; verifier throw/mismatched echo; missing checkbox; disabled source | HTTP400/409, input giữ, field error liên kết, không leak exception và không ghi READY |
| Quyền | Rental Customer A/B, Owner collector/B, Admin; gói Owner A/B và Admin; anonymous/stale | Matrix §3.3, account đầy đủ chỉ recipient detail/preview; summary luôn che |
| Fragment | Null/rỗng, duplicate2refunds, late/cancelled/incompatible; APPLIED order còn refund | Guard và card count đúng, status order không đổi vì refund |
| Polling | ACTIVE→RECEIVED→result; REFUND_PENDING→REFUNDED; READY:false→READY:true; terminal | Chỉ theo backend commit, reload/redirect đúng, không vòng lặp reload mock |
| Network/lifecycle | Timeout10s, offline/503/invalid JSON,401/403/login redirect, hidden/visible/pagehide/BFCache | Không thành công giả/không chuyển thêm; stop/resume/single in-flight đúng |
| Layout/accessibility | Bốn viewport, tên verifier/mã/tiền dài, QR load fail, keyboard và no-JS | Không tràn/crop; target≥44;1h1; label/error/live message; input giữ số0 đầu |

### 8.3. Fixture browser phải khác UI-02 ở các điểm này

1. Dùng `PaymentFlowFixture`/`PaymentTestDatabase` schema `carrent_p43_UUID`, port0 và `PayoutWorkerTest.Configuration` fake payout; bật verifier fixture và counter mock, disable payment maintenance/rental deadlines. Khi dùng production advance/balance/subscription handler trong fixture, giữ `carrent.rental.advance.enabled`, `carrent.rental.balance.enabled`, `carrent.subscription.enabled` trong **suốt vòng đời** fixture và tắt fixture domain handlers để tránh trùng route. Không đổi flags ứng dụng.
2. Seed/flow hợp lệ từ `BeforeAcceptanceTest.ready()` và `RentalBalanceUiTest.ready()`; subscription theo `SubscriptionOwnerUiTest`. Giữ parties/pilot Owner2/Admin1, bank/account pair, business key và expiry constraints. BEFORE cần ảnh fixture riêng; không ghi vào uploads ứng dụng. Không nhầm fake domain handler đã tạo receipt với production handler đã chuyển booking.
3. `PaymentFlowFixture.NOW = 2026-10-30T03:00:00Z`, trong khi ngày máy có thể khác. Trước navigate đặt clock browser đồng bộ server fixture; API đã xác minh có trong Playwright local:

```javascript
await page.clock.setFixedTime(new Date('2026-10-30T03:00:00Z'));
```

   Khi fixture clock thay đổi, cập nhật clock page tương ứng. `setFixedTime` giữ timers hoạt động; không freeze toàn bộ polling để có ảnh ACTIVE giả. Clock chỉ dùng trong harness, không sửa JS sản phẩm.
4. Dùng cache Tailwind/fonts như UI-02; chặn external network, route `img.vietqr.io` tới ảnh fixture cục bộ chỉ trong browser. Kiểm tra **src URL server** trước intercept để bảo toàn amount/code; không coi ảnh fixture là QR bank được nghiệm thu. No-JS vẫn đọc/link/POST, không tuyên bố đầy đủ styling khi tắt CDN JS.
5. Mỗi scenario UI phải có backend state đã commit. Khi giả response status để kiểm tra navigation, commit fixture tương ứng trước khi phản hồi hoặc chỉ assert navigation mà không nhận là state thật; mock READY→SUCCEEDED trong khi HTML backend vẫn READY gây reload loop và không chứng minh đã hoàn.
6. Tách phase GET/poll, POST preview và POST confirm/cancel/retry. Snapshot20 bảng có BLOB hex + deterministic self-check. GET/poll/preview không thay rows; submit chỉ thay recipient/status/updatedAt/readyAt/dueAt và saved account khi opt-in theo transaction thật, không có payout attempts; cancel/retry đối chiếu mutation hợp lệ và nợ/hạn không đổi cho balance. Không áp global “mọi bảng không đổi” cho phase có POST mutation.
7. Các command seed/clock/status phục vụ test phải có log/segment riêng. Nếu restore user sau BANNED/role thử nghiệm, restore cả `updated_at` tự động; không bỏ cột khỏi snapshot. Preview/confirm gọi mock verifier một lần mỗi request, fake payout vẫn0; không đánh đồng hai counter.
8. Dùng output/classpath ổn định; không javac harness trong lúc Maven thay `test-classes`. File scripts UTF-8, tên PNG bỏ ký tự `?`/query không hợp lệ trên Windows, await navigation/DOM trước count. Fresh credential chỉ lưu file tạm của fixture, xóa trong finally; xác nhận schema không còn và port không listen sau stop.

### 8.4. Điều kiện bàn giao

Focused suite/Node/browser pass trên source cuối; ảnh trước/sau mới và ảnh được agent xem; đủ role/state/actions/no-JS, pure reads và POST mutation có bằng chứng riêng, live HTTP0/fake payout0; hash allowlist/config/index/HEAD giữ nguyên, cleanup và links/checklists đã audit. Human visual review/native zoom chưa làm thì ghi tách, không suy từ reflow720 CSSpx hoặc test pass.

## 9. Lệnh giao phiên triển khai

> Chỉ triển khai **UI-03** theo `docs/plans/ui-03-analysis-and-plan-2026-11-08.md`, đọc bàn giao UI-02 và SDD §8.7. Dùng root hiện hành, bảo toàn baseline/backend/config/index. Sửa năm template checkout/result/refund/refund-summary/refund-links, giữ layout A, model/CSRF/quyền/masking và polling DOM/terminal; kiểm tra cả subscription order caller. Fixture/POM/output riêng, clock browser khớp fixture, GET/preview chỉ đọc và POST mutation có expected riêng; fake payout/live HTTP0. Chạy focused9 nhóm + Node suite/browser bốn viewport, lưu ảnh/audit/handoff, dọn fixture rồi dừng trước UI-04. Không restart8080, gọi provider/tiền thật, stage/commit/push hoặc sửa service/schema.
