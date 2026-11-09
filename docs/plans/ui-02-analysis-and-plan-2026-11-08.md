# UI-02 — Owner dashboard và tài chính: Analysis and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL khi được giao triển khai: dùng `superpowers:executing-plans`, thực hiện đúng UI-02, kiểm chứng và bàn giao rồi dừng trước UI-03. Không dùng subagent nếu chưa được yêu cầu. Các checkbox triển khai được cập nhật theo bằng chứng trong [bàn giao UI-02](ui-02-handoff.md); phần phân tích bên dưới giữ snapshot trước triển khai.

**UI-02 — 2026-11-08:** dashboard Owner, finances đủ 9 role/view và overview dùng chung đã triển khai, kiểm chứng cục bộ: focused **47/47**, Chrome **1233 kiểm tra**, **28 ảnh trước/139 ảnh sau**; 20 bảng fixture không đổi, provider 0, cleanup đã xác nhận. Xem [bàn giao UI-02](ui-02-handoff.md). Human visual review/native zoom 200% còn chờ; UI-03 trở đi và gate live giữ mở.

**Goal:** Hoàn thiện `/owner/dashboard` và `/payment/finances` cho đủ role/view, giữ đúng ý nghĩa tiền, trạng thái và quyền hiện có.

**Architecture:** Dùng workspace/sidebar/topbar đã có từ UI-01; tổ chức lại markup của dashboard, finances và overview dùng chung. Giữ read model/controller/query, dùng một bản nội dung finances cho cả layout Customer và workspace Owner/Admin; bổ sung active scope Admin bằng tham số `financeView` hiện có.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Spring Security, MySQL, Tailwind Play CDN, Inter, Material Symbols. Không thêm dependency.

**Spec:** [SDD §8.7](../SDD.md#87-hoàn-thiện-thiết-kế-ui-sau-các-đợt-triển-khai-chức-năng--2026-11-07), [wireframe và backlog UI](ui-completion-2026-11-07.md), [phạm vi 15 trang](ui-15-pages-readiness-2026-11-08.md), [bàn giao UI-01](ui-01-handoff.md).

## Global Constraints

- Chỉ triển khai UI-02 khi được giao: hai trang và overview, cộng thay đổi active scope cần thiết của workspace. Không triển khai checkout/refund/order/Admin dashboard hoặc các đợt sau.
- Giữ URL, HTTP method, model, field, CSRF, điều kiện quyền/trạng thái, escaping, masking và selector hiện hành. Không thêm API, controller hoặc query cho UI.
- Không sửa Java main, schema/seed, provider config, `.env`, `application-local.properties`, scheduler, root `pom.xml` hoặc restart runtime 8080. Mutation/giả lập schema chỉ chạy fixture riêng.
- Giữ token Stitch, Spring MVC/Thymeleaf và Tailwind Play CDN. Không thêm React, Bootstrap, thư viện chart hoặc chuyển hệ assets.
- Không checkout/reset/stash/merge/stage/commit/push. Giữ root và mọi dirty changes; lập manifest mới trước/sau đợt implement.
- UI tiếng Việt; tiền từ model, giờ `Asia/Ho_Chi_Minh`. Không tạo số dư, doanh thu ròng, bộ lọc, export hoặc action tiền mới.
- Pending/processing không phải thành công; unavailable không phải 0. Không thay điều kiện quyền bằng việc chỉ ẩn nút.

## 1. Baseline và mức độ sẵn sàng

Xác minh lại sau UI-01 tại root `C:/Users/Admin/Documents/hoc/Car`, branch `feat/phase5-profile-password`, HEAD `b829b05`.

- Có 5 checkout được Git đăng ký; tiếp tục tại root theo quyết định đã duyệt. Root có **49 tracked dirty / 49 untracked entry** trước tài liệu này; entry có thể là một thư mục, không phải số file.
- Nội dung **63 resource** hiện tại khớp build đã kiểm chứng của UI-01 sau chuẩn hóa CRLF/LF. Dashboard, finances, overview và hai read model chưa đổi so với baseline đầu UI-01.
- UI-01 có evidence trước đó: **35/35 focused tests, 634 browser checks, 60 ảnh sau**, cleanup hoàn tất. Đây là kết quả UI-01, không phải kết quả kiểm thử UI-02 mới.
- UI-01 human visual review và browser zoom 200% trực tiếp còn chờ. Có thể chuẩn bị UI-02 trên source hiện hành; chưa coi các mục này đã được người dùng duyệt.
- Phiên phân tích không chạy Maven/browser/DB và không bật server. Đã đối chiếu source/test, không chấm thẩm mỹ runtime UI-02 bằng số dòng hoặc class.
- Evidence phân tích: `.scratch/ui-02-analysis/baseline.json`, `validation.json`. Baseline gồm cả hash `.env` và `application-local.properties`, không sao chép nội dung secret.

**Kết luận:** đủ hợp đồng source và phạm vi để giao UI-02. Đầu phiên implement vẫn phải tạo fixture/ảnh trước mới và chạy baseline suite; không dùng lại ready, credential, schema hoặc ảnh lịch sử làm kết quả UI-02.

## 2. File và caller phải chạm

Đường template tính từ `src/main/resources/templates/`.

| File | Công việc được phép | Caller/hồi quy |
|---|---|---|
| `owner/dashboard.html` | Chuyển sang workspace; heading/CTA, KPI, tài chính/cảnh báo; sửa token font | Owner A/B, no data, flag off, thiếu module |
| `payment/finances.html` | Layout theo role, active tab, bảng/card, empty/paging/reload | Customer 2 view, Owner 3 view, Admin 4 view |
| `subscription/overview.html` | Card gói/hạn/quota/trạng thái/CTA; giữ fragment không tham số | Dashboard, `subscription/plans.html`, `owner/cars.html` |
| `fragments/workspace.html` | **Bổ sung tối thiểu** active của hai link Admin finance; giữ interface | Sáu caller UI-01 và các caller UI-02 |
| `src/test/java/com/carrent/payment/WorkspaceUiTest.java` | Thêm kiểm tra shell dashboard và role/view của finances | Giữ nguyên năm test UI-01 |
| `src/test/java/com/carrent/payment/FinancialViewsTest.java` | Chỉ thêm thiếu hụt về paging/empty/link sau đổi presentation | Không xóa/nới assertion quyền, masking hoặc receipt scope |
| `src/test/java/com/carrent/payment/SubscriptionOwnerUiTest.java` | Chỉ sửa/thêm assertion hành vi nếu overview thay đổi khiến cần kiểm tra thêm | Không đổi nghiệp vụ gói/order/refund |
| `docs/plans/ui-02-handoff.md` — tạo khi triển khai | Source baseline, evidence, kết quả, giới hạn, điểm dừng | Cập nhật checklist UI-02 và SDD sau khi có kết quả thật |

Giữ `fragments/head/header/messages/pagination`, `payment/finance-unavailable.html`, CSS/JS và các page khác làm dependency/hồi quy. Không sửa chúng mặc định. Fallback finance vẫn là trang unavailable hiện hành với public header; controller trả sớm trước khi thêm các model finance, nên không ép fallback dùng `view/obligations/refunds/reviewReceipts`.

Không cần fragment generic mới hoặc `fragments/ui.html`. Ưu tiên nội dung finances một lần trong cùng file, chọn wrapper/header theo role; không nhân đôi toàn trang cho Customer/Owner/Admin.

## 3. Phân tích dashboard và nghĩa dữ liệu

`OwnerDashboardController.dashboard()` trả `stats`, `subscription`, `subscriptionAvailable`, header `Cache-Control: no-store`; service kiểm tra Owner hiện tại trong DB, không lấy owner ID từ query string.

### 3.1. KPI không tạo thành một phép chia tổng

| Field `stats` | Nghĩa source hiện tại | Điều UI không được suy diễn |
|---|---|---|
| `ownerName` | Tên Owner hiện tại, cần `th:text` | Không render HTML từ tên |
| `publicCars` (`Long`) | Xe public theo listing/approval và policy gói; có thể null nếu thiếu schema gói khi policy bật | Không gọi là xe đang rảnh; không coi null là 0 |
| `pendingCars` | Approval PENDING, không lọc listing status | Không đồng nhất với `subscription.pending` |
| `hiddenCars` | Listing INACTIVE | Không cộng thêm `temporarilyHidden`; không coi đây là số xe hết hạn gói |
| `bookings` | Toàn bộ đơn trên xe Owner, có cả lịch sử/status khác | Không gọi là đơn đang thuê hoặc việc cần xử lý |
| `allocatedRental` | Tổng allocated của receipt thuê hợp lệ, đúng parties/channel/snapshot, VIETQR_V1 và inspection required | Không là số dư ví, gross received hoặc tiền sẵn sàng rút |
| `refundsSucceeded` | Số tiền hoàn SUCCEEDED hợp lệ | Không tự trừ khỏi allocated để sinh doanh thu ròng |
| `refundsPending`, `refundsPendingAmount` | Số khoản/số tiền mọi refund chưa SUCCEEDED | Gồm cả READY/WAITING_FUNDS/PROCESSING/REVIEW_REQUIRED; không chỉ WAITING_RECIPIENT |
| `refundsOverdue`, `refundsOverdueAmount` | Phần refund chưa SUCCEEDED có `dueAt <= now` | Là tập con pending; không cộng hai nhóm thành tổng |
| `outstandingDebt` | Số tiền còn nợ BALANCE của booking hợp lệ đã nhận lại xe | Không lấy từ hạn QR hoặc cộng mọi obligation OPEN |
| `overdueDebts`, `overdueDebtAmount` | Phần nợ trên có deadline đã tới | Là tập con outstanding; không tự khóa tài khoản/chặn thuê |

`pendingCars` và `hiddenCars` có thể chồng nhau. Bốn KPI xe/đơn là các số liệu độc lập, không có biểu đồ phân bổ hoặc tổng cộng do UI tự tính.

Hạn nợ hợp lệ là **1 giờ sau nhận lại xe thực tế**; kiểm tra quá hạn bao gồm đúng thời điểm deadline. Refund SUCCEEDED không còn overdue dù ngày hạn đã qua. Gia hạn/hết hạn gói không làm mất dữ liệu tài chính lịch sử.

### 3.2. Hai nguồn available độc lập

- `subscriptionAvailable`: policy bật, đủ `owner_subscriptions/subscription_orders` và có service tương ứng. Nếu false, giữ `subscription-unavailable`; không dựng card gói với quota 0.
- `stats.financialAvailable`: kiểm tra năm bảng `payment_channels/payment_obligations/payment_intents/payment_receipts/refunds`. Nếu false, giữ `financial-unavailable`, không hiện những giá trị khởi tạo 0 như số liệu thật.
- Finances kiểm tra **bảy** bảng, thêm `webhook_inbox/payout_attempts`. Có thể dashboard còn số tài chính nhưng mở finances ra unavailable khi thiếu hai bảng này. Không đổi backend để đồng nhất hai gate trong UI-02.
- `publicCars == null`, không có gói, hết hạn và unavailable là các trạng thái khác nhau. Không bọc cả tài chính trong điều kiện gói còn hiệu lực.

### 3.3. Bố cục đề xuất

```text
Desktop: sidebar 256px | topbar 64px
Tổng quan chủ xe / tên          [Quản lý xe] [Xem đơn thuê]
[Xe công khai] [Chờ duyệt] [Đang ẩn] [Tổng đơn thuê]
[Gói hiện tại / hạn / quota / pending / tạm ẩn / Mua hoặc Gia hạn]
Tiền đã ghi nhận                          [Xem khoản tôi thu]
[Tiền thuê đã phân bổ] [Đã hoàn thành công]
Cần theo dõi
[Hoàn đang chờ: số khoản + tiền] [Trong đó quá hạn: số khoản + tiền]
[Nợ 70%: tiền]                  [Trong đó quá hạn: số khoản + tiền]
Giải thích ngắn về các phạm vi; không cộng/trừ thành số dư
```

Mobile: heading → CTA → KPI (1 cột tại 320, 2 cột khi đủ 390) → gói → tài chính → cảnh báo. Dùng `min-w-0`, wrap tiền/ID dài; không thu nhỏ tiền đến mức khó đọc. Dùng padding/gap vì head hiện có reset margin first/last child của main.

Giữ `owner-dashboard`, `owner-counts`, `subscription-overview`, `subscription-unavailable`, `financial-unavailable` và **13 `data-metric` hiện có**. Không lặp mỗi metric một bản desktop và một bản mobile; harness hiện tại đọc theo key. Thay `font-title-lg` tại dashboard/overview bằng cặp token đã có như `font-title-md text-title-md` hoặc `font-headline-sm text-headline-sm` theo cấp heading.

Các CTA cảnh báo chỉ dùng route thật: `/payment/finances?view=collected` hoặc `/owner/bookings`. Finances chưa có filter overdue, nên không gắn query giả hoặc mô tả rằng link mở sẵn danh sách chỉ quá hạn.

## 4. Overview là module dùng chung, không chỉ card dashboard

Interface giữ nguyên `subscription/overview :: overview`; model `subscription` có `plan, validUntil, active, reserved, pending, temporarilyHidden, quota`.

- `reserved`: xe listing ACTIVE có approval PENDING/APPROVED, kể cả khi gói đã hết hạn. `pending`: chỉ xe ACTIVE/PENDING; khác KPI dashboard đếm mọi xe PENDING.
- `temporarilyHidden`: xe ACTIVE/APPROVED bị ẩn theo policy khi không có gói active; xe người dùng INACTIVE không nằm trong số này.
- `plan == null`: “Chưa có gói”, hiển thị số xe đã chọn đăng và chưa có hạn mức; **không chia cho quota 0**, không thanh progress phần trăm.
- `plan != null && active`: badge còn hiệu lực, hạn giờ Việt Nam, `reserved/quota`; giữ pending và temporarilyHidden, CTA gia hạn và quản lý xe.
- `plan != null && !active`: badge hết hạn, giữ plan/hạn/quota; giải thích xe/hồ sơ/đơn được giữ và người dùng chọn bật xe theo điều kiện hệ thống.
- Nếu `reserved > quota` ở dữ liệu lịch sử, hiển thị giá trị thật; không clamp, tự chọn/ẩn xe hoặc tuyên bố vẫn còn suất.
- Giữ guard `subscription != null` ngay trong fragment. Caller Owner cars hiện có `th:if` cùng node `th:replace`; không dựa vào thứ tự xử lý hai attribute để tránh null.
- Giữ `subscription-overview`, `subscription-heading`, ba link hiện hành và giải thích hạ gói. Có thể đưa phần giải thích dài vào `<details>` native, nhưng nhãn trạng thái, quota, hạn và CTA chính luôn nhìn thấy.
- Không đổi nhãn enum/giá catalog hoặc mua gói trong UI-02; plans/order thuộc UI-04. Sau sửa overview phải render dashboard, plans và owner/cars với no-plan/active/expired.

## 5. Ma trận role/view tài chính

`FinancialController.list()` mặc định `view=paid`, `page=0`, clamp page vào `[0,100000]`, lấy ba Page độc lập. `FinancialViews` kiểm tra user ACTIVE/role hiện tại; query owner/user ID không đổi phạm vi.

| Role | paid | collected | refunds | managed |
|---|---|---|---|---|
| Customer | Nghĩa vụ thuê 30%/70%; receipt khớp và refund mình nhận | **403** | Refund thuê mình nhận, chỉ section refund | **403** |
| Owner | Nghĩa vụ gói mình mua; receipt khớp và refund gói mình nhận | Thu/hoàn tiền thuê của mình; receipt review vào kênh mình | Refund gói mình nhận, chỉ section refund | **403** |
| Admin | Được vào; purpose scope payer hiện cho tập rỗng hợp lệ | Phí gói mình thu/hoàn; review vào kênh Admin mình | Được vào; recipient purpose scope hiện cho tập rỗng hợp lệ | Toàn hệ thống, chỉ đọc |

Có **9 tổ hợp được phép**, 3 tổ hợp role/view bị cấm trong bảng. Không giấu tab Admin paid/refunds chỉ vì hiện rỗng; empty có chủ đích không phải lỗi quyền. Anonymous bị chuyển login; view lạ bị 403; page không phải số đi theo 400 binding hiện hành, page âm clamp về 0.

Refund trong `paid` lấy theo recipient riêng, không phải chỉ refund của các obligation trên cùng trang. Review chưa khớp intent được collector/Admin nhìn thấy trong scope phù hợp; payer không được nhận toàn bộ receipt chưa khớp vào kênh.

### 5.1. Layout và active scope cần bổ sung sau UI-01

- Customer giữ public header/layout A, không public active home/cars/bookings vì đây là tài chính.
- Owner/Admin dùng workspace: `sidebar('finances', view)` và `topbar('Tài chính')`. Một bản nội dung chung, không render hai header.
- Owner current item đúng collected/paid/refunds.
- Admin hiện có `/admin/finance` chỉ active theo `activeSection == 'finances'`; link `/payment/finances` chưa có active. Nếu tái dùng nguyên trạng sẽ tô sai scope.
- Đề xuất sửa hai link Admin, giữ interface cũ:

```text
globalCurrent   = activeSection == 'finances'
                  && (financeView == null || financeView == 'managed')
personalCurrent = activeSection == 'finances'
                  && financeView != null && financeView != 'managed'
```

`null` dành cho `/admin/finance` khi UI-05 dùng shell; `managed` là view toàn hệ thống hiện hành nên chọn nhóm tra cứu. Admin paid/collected/refunds chọn “Tài chính cá nhân”. Nội dung managed ghi rõ toàn hệ thống và giữ link đến `/admin/finance`; không nhập hai route thành một hoặc xóa view cũ.

Đây là bổ sung có lý do vào phạm vi ba template ban đầu: sửa `workspace.html` để reuse đúng scope, không làm lại sidebar hoặc thêm tham số mới.

### 5.2. Heading, tab và nội dung

```text
Tài chính                         [Tải lại trạng thái]
Phạm vi: Khoản tôi thu / nghĩa của view và role hiện tại
[Tôi thanh toán] [TÔI THU] [Tôi nhận hoàn] [Toàn hệ thống — Admin]
Nghĩa vụ thanh toán
  Mã / tham chiếu | Mục đích | Phải trả | Đã ghi nhận | Trạng thái | Link hợp lệ
Khoản tôi hoàn trả / Khoản tôi nhận hoàn / Các khoản hoàn toàn hệ thống
  Mã | Tiền | Trạng thái / quá hạn | Hạn nếu có | Tài khoản che | Chi tiết
Giao dịch cần kiểm tra
  Lời giải thích tiền đã ghi có nhưng chưa phân bổ xác nhận
  Mã | Tiền | Nội dung kiểm tra | Thời điểm giờ Việt Nam
[Trang trước] Trang n [Trang sau]
```

View refunds chỉ hiện section refund, như điều kiện hiện tại. `paid` có nghĩa “khoản tôi phải thanh toán”, không có nghĩa mọi khoản trong view đã trả xong.

Tab có `aria-current="page"` và khác màu/nền bằng token thật, không chỉ underline. Dùng link GET bình thường, không dựng widget ARIA tabs cần JS/arrow-key behavior. Heading scope đọc rõ riêng ở managed để không hiểu số liệu hệ thống thành tiền cá nhân.

Đề xuất **một DOM cho mỗi dataset**, table desktop chuyển trình bày dạng card có nhãn trên mobile; không chạy hai vòng render chứa cùng action/ID để tách desktop và mobile. Nếu đổi CSS display của table/row, giữ explicit table/rowgroup/row/cell roles và kiểm tra accessibility snapshot. Không ép một bảng quá nhiều cột rộng ra toàn trang.

## 6. Hợp đồng từng nguồn tài chính

| Nguồn | Dữ liệu có thật | Điều kiện/action phải giữ |
|---|---|---|
| `obligations` | `id, purpose, subjectId, amountDue, amountSettled, status, latestIntent, payer` | Link latest intent chỉ khi `payer && latestIntent != null`; Admin timeline chỉ ADVANCE/BALANCE |
| `refunds` | `id, amount, reason, status, account, dueAt, overdue, recipient` | Link summary luôn theo quyền backend; list giữ `account` đã che; không thêm form recipient |
| `reviewReceipts` | `id, amount, creditedAt, message` | Chỉ hiển thị; không có receipt detail/resolve route hoặc payload để tạo action |

- Không có tên xe/khách, ngày tạo obligation, dueAt/debt overdue trên `ObligationRow`. Không thêm cột/action dựa vào field không tồn tại hoặc suy quá hạn từ latest intent EXPIRED.
- Trạng thái obligation và latest intent là hai thông tin riêng: obligation SETTLED có thể vẫn có receipt/refund cần kiểm tra; không dùng màu “thành công” che cảnh báo latest intent.
- Dùng `o.status.message()`, `o.purpose.message()`, `r.status.message()` và `latestIntent.message` hiện có. `RefundReason` không có `message()`; không gọi helper giả hoặc render thêm lý do enum tiếng Anh trong UI-02.
- `r.account` đã được model che cho list/summary, kể cả Admin/recipient: null thì “Chưa có thông tin tài khoản nhận”, không thêm BIN/tên/STK đầy đủ. Với STK không quá bốn ký tự model chỉ trả dấu che; không tự khôi phục dữ liệu.
- Overdue là badge riêng cạnh status. Refund SUCCEEDED không overdue; chưa có `dueAt` hiển thị “Chưa bắt đầu thời hạn xử lý”, không tự đặt hạn.
- Tiền luôn có VNĐ, desktop căn phải/tabular, mobile có label; dữ liệu lớn không cắt nghĩa. Giữ text tiền liền mạch để các assertion hiện hành như `100.001 VNĐ` còn kiểm tra được.
- Giữ nhãn + message “Lần thanh toán gần nhất: …” trong cùng text node nếu có thể: `FinancialViewsTest` hiện kiểm tra nguyên chuỗi này. Không bỏ assertion đối chiếu status chỉ để đổi markup.
- Không thêm polling, loading skeleton, client fetch, tổng cộng trang hoặc số dư. “Tải lại” giữ `view/page`; đây là GET cùng URL hiện tại.

### 6.1. Phân trang là phần dễ hồi quy nhất

Ba nguồn đều sort ID giảm dần, size **20**, dùng chung `pageNumber`. Không có một tổng số trang chung.

- Previous khi `pageNumber > 0`.
- Next khi **OR** ba `hasNext()`; không lấy riêng obligations để quyết định.
- Chuyển view không mang page của view cũ; reload/previous/next giữ view hiện tại.
- Section hết bản ghi ở page 1 vẫn hiện empty riêng dù section khác có dữ liệu/next. Không tự ẩn navigation toàn trang hoặc thông báo mọi dữ liệu rỗng.
- Page xa vượt nguồn vẫn hiển thị page số đã clamp và đường trước; không tự đổi sang page cuối.
- Không dùng thẳng fragment `pagination(page,baseUrl)` của UI-01: fragment cần một Page, trong khi ở đây có ba Page độc lập. Tái dùng style/target 44px, giữ cơ chế previous/next hiện có.

Fixture phải có ca **0 obligation + 21 review receipt**, **21 refund + nguồn khác rỗng**, và page 1 chỉ còn một record ở một nguồn. Đây là kiểm tra hành vi, không kiểm tra chuỗi class Tailwind.

## 7. Kế hoạch thực hiện UI-02

Ba checkpoint dưới đây thuộc cùng UI-02, không tạo task nghiệp vụ mới. Có thể bàn giao tạm một checkpoint nếu có giới hạn phiên, nhưng UI-02 chưa đóng đến khi đủ cả ba.

### Checkpoint A — Dashboard và overview

**Files:** `owner/dashboard.html`, `subscription/overview.html`; test `WorkspaceUiTest.java`, các assertion thiếu thực tế trong `OwnerRevenueTest.java`/`SubscriptionOwnerUiTest.java` nếu cần.

**Consumes:** `stats/subscriptionAvailable/subscription`, workspace UI-01, overview không tham số. **Produces:** dashboard dùng shell với current dashboard; overview giữ đúng hợp đồng trên cả ba caller.

- [x] Ghi manifest mới, đọc lại UI-01 handoff; tạo output/DB/provider fixture riêng, chạy baseline focused suite và chụp trước dashboard/overview callers.
- [x] Thêm test RED vào `WorkspaceUiTest`: Owner dashboard có nav Owner/current `/owner/dashboard`, không có public nav; metrics vẫn do model hiện hành cung cấp.

```java
@Test void ownerDashboardUsesWorkspaceNavigation() throws Exception {
    String page = html("/owner/dashboard", 2);
    assertThat(currentLinks(nav(page, "Điều hướng chủ xe")))
            .containsExactly("/owner/dashboard");
    assertThat(page).doesNotContain("aria-label=\"Điều hướng chính\"");
}
```

Đoạn này dùng `html/nav/currentLinks` có sẵn trong `WorkspaceUiTest`; trên source hiện tại fail vì chưa có nav Owner ở dashboard.

- [x] Thay header/wrapper bằng `sidebar('dashboard', null)`, `topbar('Tổng quan chủ xe')`, giữ main ID và metrics. Dựng bố cục §3; không thêm data fetch hoặc tổng mới.
- [x] Dựng overview §4 và sửa font token; giữ null guard và IDs. Render lại plans/cars, không redesign hai trang đó.
- [x] Chạy `WorkspaceUiTest,OwnerRevenueTest,SubscriptionOwnerUiTest`; chụp normal/no-plan/expired/zero/flag-off/missing module, keyboard/mobile và Owner B.

### Checkpoint B — Finances đủ role/view và paging

**Files:** `payment/finances.html`, hai active link của `fragments/workspace.html`; test `WorkspaceUiTest.java`, `FinancialViewsTest.java` nếu cần bổ sung paging.

**Consumes:** năm model finance, interface workspace UI-01, data scope §5/6. **Produces:** Customer layout A, Owner/Admin layout B, active scope đúng, một bộ nội dung finances.

- [x] Thêm test RED Owner collected dùng nav Owner/current collected; Customer paid vẫn public header; Admin collected chọn personal finance, Admin managed chọn global finance.

```java
@Test void financeWorkspaceMarksTheOwnersCollectionScope() throws Exception {
    String page = html("/payment/finances?view=collected", 2);
    assertThat(currentLinks(nav(page, "Điều hướng chủ xe")))
            .containsExactly("/payment/finances?view=collected");
}

@Test void adminFinanceNavigationDistinguishesPersonalAndManagedScope() throws Exception {
    assertThat(currentLinks(nav(html("/payment/finances?view=collected", 1),
            "Điều hướng quản trị"))).containsExactly("/payment/finances");
    assertThat(currentLinks(nav(html("/payment/finances?view=managed", 1),
            "Điều hướng quản trị"))).containsExactly("/admin/finance");
}
```

- [x] Áp dụng wrapper theo role, sửa active predicates §5.1; không đổi fragment parameters hoặc render ba bản main. Tab vẫn link GET, current style nhìn thấy được.
- [x] Tổ chức ba nguồn theo §5/6; thêm nhãn trạng thái/tiền/hạn từ model thật, giữ masking và action guards. Không thêm recipient form, provider setup hoặc nút xử lý tiền.
- [x] Kiểm tra paging ba nguồn bằng fixture 21 record: chụp page 0/1 với source khác nhau là nguồn duy nhất có next. Empty mỗi section, giữ query và không nhân đôi metrics/action.
- [x] Chạy `WorkspaceUiTest,FinancialViewsTest,PaymentAccessAuditTest`; kiểm tra 9 role/view hợp lệ, các URL bị cấm, Owner/Customer khác tài khoản và stale/banned role.

### Checkpoint C — Hồi quy/evidence và dừng

**Files:** harness/evidence `.scratch/ui-02/`, tạo `docs/plans/ui-02-handoff.md`; cập nhật đúng checkbox UI-02 sau khi có kết quả.

- [x] Chạy focused suite hợp nhất §8 trên source cuối; không gọi kết quả UI-01 hoặc lần fail ban đầu là kết quả UI-02 cuối.
- [x] Browser bốn viewport và matrix §8; chụp trước/sau mới, xem ảnh hai trang chính + overview callers. Ghi riêng native zoom/human review chưa thực hiện nếu còn thiếu.
- [x] Xác nhận response no-store; GET không đổi 20 bảng fixture, provider counter 0; snapshot BLOB dùng hex và có self-check ổn định như harness cuối UI-01.
- [x] Hash backend/config/index giữ nguyên ngoài allowlist; dọn schema/port/credential, giữ lỗi ban đầu và rerun riêng; viết handoff có đủ source/route/role/state/test/ảnh/giới hạn.
- [x] Dừng trước UI-03; không đổi task nghiệp vụ/provider/pilot hoặc coi ba checkpoint UI là ba chức năng đã triển khai.

## 8. Fixture và tiêu chí nghiệm thu

### 8.1. Tái sử dụng đúng phần của UI-01/P5.7

- Đọc `PaymentTestDatabase.java`, `PaymentFlowFixture.java`, `PayoutWorkerTest.Configuration` và `.scratch/ui-01/ShellBrowser.java` để tạo harness UI-02 riêng. Không sửa/ghi đè evidence UI-01.
- Reuse cách isolated POM, native login, asset cache, fake providers, canonical DB snapshot, cleanup/schema verification. P5.7 có seed dashboard/case deadline hữu ích; không chạy nguyên harness cũ vì path/cache/ready/lifecycle khác.
- Chặn external network trong browser fixture; QR/image nếu cần dùng placeholder. Không dùng dữ liệu ngân hàng hoặc credential thật; không in `.env`/local properties hoặc ghi chúng vào handoff.
- Fixture giữ flag trong suốt phiên render; xóa System property ngay sau boot làm `@environment` trong workspace nhìn thấy flag khác bean đã khởi tạo. Dọn/khôi phục property khi đóng fixture.
- Schema/port luôn mới, payment maintenance và rental deadline scheduler tắt. Kiểm tra host/DB flags trước chạy; không rename/drop bảng của schema ứng dụng.
- File fixture đặt dưới `.scratch/ui-02/`. Chưa tạo POM/harness UI-02 trong phiên phân tích này.

Nếu root `pom.xml` còn khớp hash baseline UI-01, có thể tạo POM cách ly từ bản UI-01 và chỉ đổi output; nếu khác, tạo lại từ root hiện tại thay vì dùng bản cũ:

```powershell
$ui02Baseline = Get-Content -Raw .scratch/ui-01/baseline.json | ConvertFrom-Json
if ((Get-FileHash pom.xml -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ui02Baseline.files.'pom.xml') {
    throw 'Root pom changed; regenerate the isolated POM from current source.'
}
New-Item -ItemType Directory -Force .scratch/ui-02 | Out-Null
$ui02Pom = (Get-Content -Raw .scratch/ui-01/pom.xml).Replace('/.scratch/ui-01/build', '/.scratch/ui-02/build')
[System.IO.File]::WriteAllText((Join-Path $PWD '.scratch/ui-02/pom.xml'), $ui02Pom, [System.Text.UTF8Encoding]::new($false))
.\mvnw.cmd -f .scratch/ui-02/pom.xml "-Dtest=WorkspaceUiTest,OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest,SubscriptionOwnerUiTest" test
```

Lệnh tương lai; phiên này chưa chạy. Tên test đã xác nhận có source; chỉ thêm test khi bắt hành vi UI mới hoặc gap có thật, không nhân bản test service đang đủ coverage.

### 8.2. Matrix dữ liệu/trạng thái bắt buộc

| Nhóm | Ca fixture | Điều phải thấy |
|---|---|---|
| Dashboard | Owner có dữ liệu, Owner B, Owner rỗng | Đúng dữ liệu riêng, escaped tên dài; zero thật khác unavailable |
| Dashboard finance | Allocated 150.000; refund succeeded 20.000; pending 2 khoản/40.000; overdue 1 khoản/30.000 | Bốn số không tự trừ/cộng thành số dư; overdue là phần pending |
| Nợ | Outstanding 150.000; deadline trước 1 giây, đúng hạn, sau hạn; QR EXPIRED | Debt không biến mất khi QR hết hạn; quá hạn theo clock server |
| Gói | No-plan với reserved > 0; active; expired; reserved == quota và reserved > quota | Không chia 0/clamp; quota/pending/tạm ẩn khác KPI dashboard |
| Module | Flag gói off; thiếu bảng gói; thiếu bảng tài chính; chỉ thiếu payout_attempts | Panel độc lập; publicCars null khác 0; finances fallback không cần model list |
| Scope | Customer/Owner/Admin × các view §5; Owner A/B, Customer A/B | Đúng 9 tổ hợp hợp lệ, 3 tổ hợp cấm; không lộ khoản của người khác |
| Obligation | OPEN/SETTLED/CANCELLED/EXPIRED; latest intent null/khác trạng thái obligation | Label rõ; latest intent link chỉ payer; không suy overdue khi không có field |
| Refund | WAITING_RECIPIENT/READY/WAITING_FUNDS/PROCESSING/REVIEW_REQUIRED/SUCCEEDED, dueAt null/quá hạn | Status có chữ; SUCCEEDED không overdue; account luôn theo model đã che |
| Review | Receipt khớp/không khớp; số tiền lớn; creditedAt | Không lộ payload/credential hoặc tạo action resolve; giờ Việt Nam |
| Paging | Chỉ review có 21 dòng; chỉ refund có 21 dòng; page 1 chỉ một nguồn còn dữ liệu | Next dùng OR; empty cục bộ; reload/page giữ view; đổi view reset page |

Các số ở dòng finance là **fixture đích**, cần seed đúng quan hệ theo source/test rồi kiểm tra bằng expected literal. Không tự tính expected bằng chính hàm production đang kiểm thử; không nhận ảnh P5.7 làm bằng chứng fixture UI-02 mới.

### 8.3. Browser và ngưỡng đóng đợt

- 1440×1000, 1024×768, 390×844, 320×740; đủ hai trang, role layouts và tab từng view, overview trên plans/cars. Không tràn trang, chồng nav hoặc crop tiền; kiểm tra cả breakpoint có sidebar với vùng main còn 768px.
- Nav desktop/mobile chọn đúng một mục **nhìn thấy được**; UI-01 có hai bản nav trong DOM. Active sidebar và active tab là hai navigation khác nhau; không đếm `aria-current` toàn trang.
- Keyboard mở/đóng menu và account, tab/reload/paging, focus/scroll; target mới ≥44px; accessibility snapshot đọc được table/card và nhãn tiền. No-JS giữ đọc/link/logout/submit hiện hành, không cam kết đủ style CDN khi tắt JS.
- Zoom 200% trực tiếp trong browser cần ghi cách làm và kết quả riêng; viewport 720 CSS px chỉ là kiểm tra reflow tương đương, không tự gọi là đã kiểm tra native zoom.
- Dữ liệu và action khớp model fixture, mã/tên dài, empty từng nhóm, unavailable, thời điểm biên; no-store giữ nguyên, không có GET phát lệnh tiền hoặc DB mutation.
- Focused tests + browser pass, ảnh trước/sau mới và audit sạch; ghi human visual review riêng. Thiếu bước nào thì báo rõ giới hạn, không đóng UI tổng thể hoặc gate provider.

## 9. Lệnh giao việc cho phiên implement

> Chỉ triển khai **UI-02** theo `docs/plans/ui-02-analysis-and-plan-2026-11-08.md`, đọc bàn giao UI-01 và SDD §8.7. Dùng root hiện hành, bảo toàn baseline/backend/config/index. Sửa dashboard Owner, finances đủ 9 tổ hợp role/view, overview trên ba caller; bổ sung active scope hai link Admin trong workspace bằng `financeView`, không đổi interface. Fixture/POM/output riêng, giữ ngữ nghĩa KPI/paging/masking/permission; chạy focused suite và browser bốn viewport, lưu ảnh/audit/handoff, dọn fixture rồi dừng trước UI-03. Không restart 8080, gọi provider/tiền thật, commit/push hoặc sửa service/schema.
