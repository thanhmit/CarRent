# Hoàn thiện giao diện CarRent — Implementation Plan

> **For agentic workers:** Dùng `superpowers:executing-plans` để triển khai từng đợt và đọc SDD §8.7 trước khi sửa. Chỉ dùng subagent khi được yêu cầu rõ. Phiên 2026-11-07 chỉ khảo sát và viết kế hoạch. UI-01 đã triển khai và kiểm chứng cục bộ theo [bàn giao 08/11](ui-01-handoff.md); human visual review còn chờ. UI-02 đã kiểm chứng cục bộ theo [bàn giao](ui-02-handoff.md); UI-03 đã kiểm chứng cục bộ theo [bàn giao](ui-03-handoff.md); UI-04 đã kiểm chứng cục bộ theo [bàn giao](ui-04-handoff.md); UI-05A đã kiểm chứng cục bộ theo [bàn giao](ui-05A-handoff.md); UI-05B đã kiểm chứng cục bộ theo [bàn giao](ui-05B-handoff.md); UI-05C đã kiểm chứng cục bộ theo [bàn giao](ui-05C-handoff.md), hoàn tất scope 15 trang về implementation/local.

**Goal:** Hoàn thiện UI các trang đang thiên về chữ, làm rõ thao tác và đồng bộ trải nghiệm Owner/Admin/Customer theo Stitch, ưu tiên hai URL người dùng phản ánh.

**Architecture:** Giữ Spring MVC + Thymeleaf, tái sử dụng read model/controller/form và Tailwind tokens hiện có. Tách fragment shell theo role và một số thành phần lặp; chỉnh markup từng nhóm trang, không xây một bộ component/framework mới. UI không thay đổi xử lý tiền hoặc trạng thái nghiệp vụ.

**Tech Stack:** Java 21, Spring Boot, Thymeleaf, Spring Security, Tailwind Play CDN, Inter, Material Symbols; không thêm dependency.

**Spec:** [SDD §8.7](../SDD.md#87-hoàn-thiện-thiết-kế-ui-sau-các-đợt-triển-khai-chức-năng--2026-11-07); [design system và prototype Stitch](../design/stitch/README.md); D-18/D-23 trong [decisions](../decisions.md).

**Đối chiếu baseline trước UI-01 — 2026-11-08:** inventory khi đó là 42 trang + 9 fragment; root và completion có cùng nội dung template/static sau chuẩn hóa CRLF/LF. [Phân tích worktree và kế hoạch cập nhật](ui-worktree-analysis-2026-11-08.md) chốt root làm baseline đề xuất, bổ sung caller thứ sáu cho shell UI-01, chia UI-05/UI-06 thành A/B/C và liệt kê test theo đợt. Bản đối chiếu được viết trước khi triển khai; trạng thái UI-01 hiện tại nằm trong [bàn giao](ui-01-handoff.md). Dùng quyết định baseline/chia đợt trong bản cập nhật khi có khác biệt với hướng dẫn cũ ở dưới.

## 1. Phạm vi, baseline và giới hạn bằng chứng

- Ngày khảo sát: **2026-11-07**. Root `feat/phase5-profile-password`, HEAD `b829b05`, nhiều thay đổi chưa commit. Worktree completion `feat/completion-profile-pilot`, HEAD `1862aa9`, cũng có thay đổi riêng. Trước mỗi phiên phải kiểm tra lại branch, dirty files và chênh lệch; không giả định hai nơi có cùng source.
- Phản ánh trực tiếp: `/owner/dashboard`, `/payment/finances?view=collected` khó dùng, nút và bố cục chưa hoàn thiện.
- Đã kiểm kê **51 HTML: 42 trang đầy đủ, 9 fragment**, đọc layout/token, các template và controller/read model liên quan. Phân loại dưới đây là đánh giá source, không phải điểm thẩm mỹ đo trên tất cả trang đang chạy.
- Browser mới quan sát `/login`: `window.tailwind` tồn tại, computed background `rgb(249, 249, 255)`, font Inter. Chưa có phiên Owner/Admin/Customer đăng nhập để chụp hai trang yêu cầu. Kiểm tra này chỉ xác nhận assets tại trang login, không chứng minh mọi route tải assets tốt.
- Bằng chứng cục bộ không theo Git: `.scratch/ui-audit-20261107/template-inventory.json`, `before.json`, `browser-login-assets.json`. Không chứa khóa/credential. Khi clone máy khác, dùng inventory trong tài liệu này và tạo lại ảnh; không phụ thuộc `.scratch` để hiểu kế hoạch.
- Không lấy số lượng dòng hoặc việc có class làm tiêu chí chất lượng: mọi `<button>` trong inventory đều có `class`, nhưng hierarchy/focus/kích thước/active state chưa đồng đều. Nhiều trang có UI sẵn, không gọi chung là “chưa có CSS”.

### Quy định xuyên suốt các đợt

- Giữ D-18/D-23: token Stitch hiện tại, Thymeleaf, Tailwind Play CDN. Không React/Bootstrap/chart library, không migration Tailwind build trong backlog này.
- Giữ route, tên field, HTTP method, CSRF, `sec:authorize`, điều kiện `th:if`, escape `th:text`, masking và read scope. Không thay GET thành mutation. Không coi ẩn nút là kiểm tra quyền.
- Không sửa service tiền, schema, seed, provider credentials, `application-local`, flag runtime; không bật scheduler/provider hoặc restart 8080 chỉ để kiểm tra style. Fixture có DB riêng, provider giả; mọi thao tác test tiền chỉ chạy fixture.
- Không thêm API/tổng hợp mới: không biểu đồ, số dư khả dụng, tổng thu ròng, tìm kiếm/lọc mới, xuất Excel, duyệt hoàn hoặc chuyển tiền thủ công. Không yêu cầu thêm SePay/payOS key để làm UI.
- Dữ liệu thiếu/module unavailable khác 0; pending/processing khác thành công; debt/overdue không tự khóa tài khoản. Không lẫn phí gói Admin với tiền thuê Owner.
- Không stage/commit/push trừ khi được yêu cầu; bảo toàn dirty baseline. Mỗi đợt có manifest, evidence và bàn giao, không tự nhận thêm đợt kế tiếp.

## 2. Ma trận toàn bộ trang và fragment

Tên file dưới đây tính từ `src/main/resources/templates/`. Mức A = cần thiết kế lại bố cục, B = có UI cần đồng bộ, C = trạng thái hệ thống, R = giữ làm tham chiếu/hồi quy. Đây là mức ưu tiên thiết kế, không phải kết luận thiếu chức năng.

| Template | Route / ngữ cảnh | Đánh giá và hướng xử lý | Đợt |
|---|---|---|---|
| `owner/dashboard.html` | `/owner/dashboard` | A — link chữ, card số liệu thiếu hierarchy; shell B + KPI + gói + cảnh báo | UI-02 |
| `payment/finances.html` | `/payment/finances?view=paid|collected|refunds|managed` | A — tabs chưa có active style, danh sách nhiều đoạn chữ; bảng/card có nhãn | UI-02 |
| `payment/checkout.html` | `/payment/intents/{id}` khi còn thanh toán | A — QR/card cơ bản, hướng dẫn và thao tác chưa phân cấp | UI-03 |
| `payment/result.html` | `/payment/intents/{id}/result` hoặc kết quả intent | A — nhóm trạng thái + bước tiếp theo, giữ nhánh rental | UI-03 |
| `payment/refund.html` | `/payment/refunds/{id}` | A — form ngân hàng/preview/xác nhận/theo dõi cần thiết kế theo bước | UI-03 |
| `payment/refund-summary.html` | `/payment/finances/refunds/{id}` | A — summary theo quyền, số tiền/trạng thái/deadline rõ | UI-03 |
| `subscription/plans.html` | `/owner/subscriptions` | A — tier cards chưa nổi bật, giải thích dài; plan hiện tại + 3 gói | UI-04 |
| `subscription/detail.html` | `/owner/subscriptions/orders/{id}` | A — order toàn chữ; snapshot + status + CTA theo trạng thái | UI-04 |
| `admin/dashboard.html` | `/admin/dashboard` | A — các nhóm KPI/link thiếu nhịp điệu, chưa có shell B | UI-05 |
| `admin/finance.html` | `/admin/finance` | A — filter đã có, tabs/rows/actions cần thống nhất | UI-05 |
| `admin/finance-detail.html` | `/admin/finance/{kind}/{id}` | A — chi tiết rất gọn, cần mô tả chia nhóm, badge, navigation | UI-05 |
| `admin/users.html` | `/admin/users` | A — chưa theo đầy đủ prototype quản lý user; filter + table/card | UI-05 |
| `admin/user-detail.html` | `/admin/users/{id}` | A — form cập nhật trạng thái cần phân cấp và giải thích giới hạn | UI-05 |
| `admin/bookings.html` | `/admin/bookings` | A — danh sách và bộ lọc tối thiểu; table/card + badge nhiều trạng thái | UI-05 |
| `admin/booking-detail.html` | `/admin/bookings/{id}` | A — timeline/tài chính/bằng chứng đang xếp dọc, cần chia section rõ | UI-05 |
| `profile/view.html` | `/profile` | B — đã có card và nút, đồng bộ spacing/typography/summary | UI-07 |
| `profile/edit.html` | `/profile/edit` | B — đã có label/error/focus, chuẩn hóa field và action row | UI-07 |
| `profile/change-password.html` | `/profile/change-password` | B — đã có form, giữ bảo mật và lỗi; không cần thiết kế lại chức năng | UI-07 |
| `booking/list.html` | `/bookings` | B — giữ list đã thiết kế, đồng bộ badge/CTA mới | UI-06 |
| `booking/detail.html` | `/bookings/{id}` | B — shell gốc đã có UI; gom workflow 30/70/biên bản/hủy | UI-06 |
| `owner/bookings.html` | `/owner/bookings` | B — giữ sidebar/list, dùng shell chung và rõ bước tiếp theo | UI-01/06 |
| `owner/booking-detail.html` | `/owner/bookings/{id}` | B — nhiều khối thao tác thêm sau, cần phân cấp theo trạng thái | UI-01/06 |
| `inspection/detail.html` | `/bookings/{id}/inspections/{BEFORE|AFTER}` và `/draft` | B — đã có form, bảng so sánh, gallery; làm rõ nháp/gửi/bất đồng | UI-06 |
| `owner/cars.html` | `/owner/cars` | B — đã theo Stitch, đồng bộ shell/quota/listing actions | UI-01/06 |
| `owner/car-form.html` | `/owner/cars/new`, `/owner/cars/{id}/edit` | B — giữ form đã có, đồng bộ nút/ảnh/lỗi/shell | UI-01/06 |
| `admin/cars.html` | `/admin/cars` | B — giữ prototype duyệt xe, dùng shell dùng chung | UI-01/06 |
| `admin/car-detail.html` | `/admin/cars/{id}` | B — đã có trang duyệt/dialog; thay shell chung trước, kiểm tra dialog, ảnh và action states ở đợt sau | UI-01/06 |
| `payment/finance-unavailable.html` | fallback tài chính khi module chưa sẵn sàng | C — status panel có lý do/đường quay lại, không giả số 0 | UI-07 |
| `admin/unavailable.html` | fallback các trang Admin khi module chưa sẵn sàng | C — đồng bộ status panel, giữ HTTP/role hiện tại | UI-07 |
| `error/400.html` | lỗi request/filter | C — đã có UI, đồng bộ nút và nội dung | UI-07 |
| `error/403.html` | `/access-denied` / 403 | C — đã có icon/nút; giữ không lộ tài nguyên | UI-07 |
| `error/404.html` | không tìm thấy | C — đã có UI, kiểm tra CTA hợp lệ | UI-07 |
| `error/405.html` | sai method | C — giữ method/Allow từ backend, đồng bộ style | UI-07 |
| `error/413.html` | upload quá dung lượng | C — hướng dẫn thử lại, không làm mất ý nghĩa nháp | UI-07 |
| `error/error.html` | lỗi chung | C — không stack trace, có đường phục hồi | UI-07 |
| `notification/list.html` | `/notifications` | B — UI khá đầy đủ: icon, unread badge, empty state; chỉ đồng bộ actions | UI-07 |
| `home/index.html` | `/` | R — giữ thiết kế public, kiểm tra shared header/CSS không gây hồi quy | UI-01/07 |
| `cars/list.html` | `/cars` | R — giữ tìm xe/bộ lọc/card | UI-01/07 |
| `cars/detail.html` | `/cars/{id}` | R — giữ gallery/tóm tắt/CTA thuê | UI-01/07 |
| `auth/login.html` | `/login` | R — giữ auth layout, kiểm tra font/button khi dùng assets chung | UI-01/07 |
| `auth/register.html` | `/register` | R — giữ form/validation/role được phép | UI-01/07 |
| `booking/create.html` | `/bookings/new` | R — giữ form ngày/snapshot, kiểm tra CTA và lỗi | UI-01/07 |

**Đối soát số lượng:** A 15 + profile B 3 + workflow B 9 + unavailable C 2 + error C 6 + notification B 1 + reference R 6 = **42 trang**. Một template có thể phục vụ nhiều role, view và trạng thái; số 42 không phải số URL duy nhất.

| Fragment hiện có (9) | Vai trò / phụ thuộc | Đợt |
|---|---|---|
| `fragments/head.html` | assets/token, margin reset, font; không đổi token | UI-01 |
| `fragments/header.html` | navbar, authHeader, account menu, notificationBell; active route/role | UI-01 |
| `fragments/footer.html` | footer, kiểm tra không che nội dung | UI-01/07 |
| `fragments/messages.html` | flash/validation banner | UI-01 |
| `fragments/pagination.html` | pagination các trang đang gọi; các filter đặc thù vẫn giữ query riêng | UI-01/07 |
| `booking/fragments.html` | progressBadge/timeline/custody/return/balance summaries | UI-06 |
| `admin/finance-row.html` | fields của finance list/detail và Admin booking detail | UI-05 |
| `payment/refund-links.html` | link từng refund trên result/order, giữ đúng recipient | UI-03 |
| `subscription/overview.html` | dashboard, plans và trang xe gọi chung | UI-02/04 |

## 3. Thiết kế đủ để bắt đầu triển khai

### 3.1. Shell, thành phần và navigation

- Dùng đúng màu/font/radius trong `fragments/head.html`; cỡ chữ là **cặp** `font-headline-md text-headline-md`, `font-title-md text-title-md`... Không dùng `font-title-lg`/`font-title-sm` không tồn tại trong config. Có 9 dòng cần sửa ở dashboard Owner, plans/overview, booking fragments, Admin booking-detail.
- Layout B: desktop từ 1024px sidebar 256px, topbar 64px; main offset theo sidebar, padding 24px, width co được. Dưới 1024px, menu `<details><summary>Menu chủ xe/quản trị</summary>…</details>` hoặc tương đương có keyboard/no-JS; main không bị fixed menu đè. Các page hiện có breakpoint `md` đổi đồng bộ khi port shell.
- OWNER nav: Tổng quan, Xe của tôi, Đơn thuê, **Gói đăng xe**, nhóm Tài chính (Khoản tôi thu → `view=collected`, Khoản tôi thanh toán → `view=paid`, Khoản tôi nhận hoàn → `view=refunds`), Xem trang khách, Hồ sơ. ADMIN nav: Tổng quan, Duyệt xe, Đơn thuê, Tài khoản, Tra cứu tài chính → `/admin/finance`, các khoản riêng tại `/payment/finances` có nhãn phạm vi. Giữ logout POST và notificationBell.
- `managed` tại trang tài chính vẫn theo quyền Admin; `/admin/finance` là đường tra cứu có bộ lọc, không xóa view cũ. Không thêm mục Owner vào Customer hoặc link chức năng bị gate tắt như thể đã sẵn sàng; dùng thông báo unavailable hiện có khi cần.
- Profile, notifications và giao dịch checkout/result/refund tiếp tục layout A gọn; có navigation quay lại ngữ cảnh từ dữ liệu/link đã có. Không dựng sidebar cho auth hoặc bắt người trả tiền đi qua menu quản trị.
- Primary button: `min-h-11 inline-flex items-center justify-center gap-space-sm px-space-md py-2 bg-secondary text-on-secondary rounded-lg font-label-md text-label-md hover:bg-secondary-container focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-secondary`. Secondary dùng nền surface/viền outline; destructive dùng error hoặc error-container. Link phụ có underline/focus. Nút icon phải có accessible name và target ít nhất 44×44px.
- Shared UI fragment chỉ cho những khối thật sự dùng chung (page heading, status/empty panel); không tạo generic form/button fragment nuốt `th:field`, CSRF hoặc che giấu `th:if`. Giữ class Tailwind tường minh; không tạo class động khiến CDN không sinh style.
- Kiểm tra global `main > :first-child/last-child` đang đặt margin 0 và ẩn scrollbar trong head. Dùng padding/gap cho main. Chỉ điều chỉnh rule nếu browser chứng minh gây lỗi; vùng bảng cuộn phải có dấu hiệu/nhãn và keyboard focus, không mở rộng thành viết lại CSS toàn app.

### 3.2. Wireframe Owner dashboard

```text
Desktop — sidebar | nội dung
Tổng quan chủ xe / tên Owner                 [Quản lý xe] [Xem đơn thuê]
[Xe công khai] [Chờ duyệt] [Chủ động ẩn] [Đơn thuê]
[Gói hiện tại · hiệu lực · hạn · quota · pending/tạm ẩn · Mua/Gia hạn]
Tiền đã ghi nhận                            [Xem khoản tôi thu]
[Tiền thuê đã phân bổ] [Đã hoàn thành công]
Cần theo dõi
[Hoàn chờ · số khoản/số tiền] [Hoàn quá hạn · số khoản/số tiền]
[Nợ 70% · số tiền]           [Nợ 70% quá hạn · số khoản/số tiền]
[Giải thích ngắn: tiền phân bổ/hoàn/nợ là các số liệu riêng]
```

- Mobile: heading → CTA → 4 KPI (1 cột ở 320, 2 cột khi đủ 390) → gói → tài chính → cảnh báo. Không cần chart hoặc danh sách booking mới vì model chưa có dữ liệu đó.
- Bảo toàn `owner-dashboard`, `owner-counts`, `subscription-overview`, `subscription-unavailable`, `financial-unavailable` và toàn bộ `data-metric`. Không tự tạo badge cần xử lý từ số tổng booking; chỉ dùng các counter hiện tại.
- Giữ cả số lượng và số tiền của pending/overdue. Dữ liệu module tắt hiện panel unavailable; `publicCars == null` là “Chưa có dữ liệu”. Trạng thái không giả bằng KPI 0.

### 3.3. Wireframe tài chính cá nhân

```text
Tài chính                            [Tải lại trạng thái]
Phạm vi: Khoản tôi thu · tiền được ghi nhận vào kênh của tài khoản này
[Khoản tôi thanh toán] [KHOẢN TÔI THU] [Khoản tôi nhận hoàn] [Quản trị*]
Nghĩa vụ thanh toán
Mã / Tham chiếu | Mục đích | Phải trả | Đã ghi nhận | Trạng thái | Thao tác
Khoản tôi hoàn trả
Mã | Số tiền | Trạng thái / quá hạn | TK được che | Chi tiết
Giao dịch cần kiểm tra
Thông báo: đã ghi có nhưng chưa xác nhận phân bổ
Mã | Số tiền | Nội dung kiểm tra | Thời điểm ghi có
[Trang trước] Trang n [Trang sau]
* Chỉ Admin
```

- Dùng label theo vai trò/view: `paid` là tiền tôi phải trả, `collected` là khoản tôi thu và hoàn trả, `refunds` là khoản tôi nhận hoàn, `managed` là phạm vi Admin. Không biến mọi section thành “Doanh thu”.
- `FinancialViews.ObligationRow` chỉ có `id, purpose, subjectId, amountDue, amountSettled, status, latestIntent, payer`; không thiết kế thêm tên khách/xe/timestamp chưa có. Link latestIntent chỉ khi `payer` như hiện tại. Admin chỉ có link timeline rental khi đúng purpose.
- `RefundRow`: `id, amount, reason, status, account, dueAt, overdue, recipient`; `account` đã theo quyền, không lấy lại tài khoản đầy đủ ở list. `ReviewReceiptRow`: `id, amount, creditedAt, message`. Thời gian dùng `Asia/Ho_Chi_Minh`.
- `page` hiện dùng chung cho ba Page 20 bản ghi; nút next nếu một nguồn có next. Giữ cơ chế này và thông báo empty riêng từng section, kể cả khi section khác có dữ liệu. Không hiển thị “không có giao dịch” cho toàn trang khi chỉ một nhóm rỗng.
- Số tiền căn phải desktop, có label mobile và `tabular-nums`; ID/code dài wrap. Không gộp ba nguồn thành một ledger vì không có cùng loại dữ liệu hoặc trạng thái.

### 3.4. Thanh toán và hoàn tiền

- Checkout: header “Thanh toán chuyển khoản”, số tiền lớn + badge; thẻ QR (giữ tỉ lệ/alt, không crop) và mã/nội dung/hạn, phần hướng dẫn + ngữ cảnh rental 30%/70% hoặc order. Nút tải lại secondary; hủy destructive, giữ nhãn “Hủy đặt xe” khác “Hủy lần thanh toán”. Không thêm dữ liệu ngân hàng từ config ra model khi chưa có field được phép.
- Result: icon theo loại kết quả, nhãn backend, mã/số tiền, hành động backend cho phép. ACTIVE có mở QR; balance có tạo lần mới khi `canCreate`; receipt cần kiểm tra vẫn hiện cảnh báo; refund pending có danh sách từng refund. Không kết luận EXPIRED/CANCELLED là không thể còn receipt muộn.
- Refund recipient: tóm tắt tiền/trạng thái/hạn; bước 1 nhập BIN 6 số và STK, tùy chọn lưu; bước 2 hiển thị ngân hàng/STK/tên do verifier trả và checkbox xác nhận; tiếp theo là theo dõi. Dùng tên bước, không vẽ thanh tiến độ chi tiền giả. Khi verifier không hỗ trợ, hiển thị lỗi và giữ input hợp lệ; không cho nhập tay tên thay xác minh.
- Refund summary: thông tin chỉ đọc, nhấn `WAITING_RECIPIENT/READY/WAITING_FUNDS/PROCESSING/SUCCEEDED`, badge quá hạn độc lập, link cung cấp tài khoản chỉ cho recipient. Panel `SUCCEEDED` cần message nhà cung cấp xác nhận, không dựa thao tác submit.
- Giữ `payment-checkout`, `payment-status`, `payment-qr`, `payment-cancel`, `financial-status`, `polling-message`, toàn bộ `data-status-url/data-result-url/data-expires-at/data-booking-advance/data-state/data-terminal/data-intent` và contract của `static/js/payment-polling.js`. Không đổi JS polling chỉ vì di chuyển DOM.

### 3.5. Gói, Admin và workflow

- Gói: overview là một card dùng lại trên dashboard/plans/cars; hiển thị quota đã giữ gồm xe pending, xe tạm ẩn, hiệu lực/hạn. Thanh quota chỉ khi có quota hợp lệ, text vẫn hiện số thật; không chia cho 0 hoặc giấu vượt hạn. Tier card SMALL/STANDARD/BUSINESS cùng chiều cao, giá/30 ngày/số xe, nhãn “Gói hiện tại” đúng dữ liệu. Không tự gắn “phổ biến nhất” hoặc giảm giá. Disabled tier khác có lý do đọc được; order APPLIED dẫn quản lý xe, PENDING dẫn QR, REFUND_PENDING dẫn từng refund.
- Admin dashboard: nhóm toàn hệ thống → doanh thu phí gói của Admin này → cần theo dõi. Số tiền đã vào kênh, đã phân bổ, đã hoàn vẫn tách nhau. Chỉ dùng các link filter hiện có (RECEIPTS/REVIEW_REQUIRED, REFUNDS/overdue…). Không đặt tiêu đề “số dư ví khả dụng” vì model không có số dư nhà cung cấp.
- Admin finance: giữ `kind,status,overdue,subjectType,subjectId,collectorId,sort,page`, các enum whitelist và filter lỗi 400. List table/card theo kind; detail chia định danh/phạm vi/số tiền/trạng thái/thời gian/đường liên quan từ fields hiện có. `admin/finance-row.html` đang dùng cả Admin booking detail, phải kiểm tra mọi caller khi đổi.
- Users: dùng prototype `admin-quan-ly-nguoi-dung.html`, thay dữ liệu và actions thực. Email/tên dài wrap; role/status có text; form đổi trạng thái giữ cấm tự cập nhật, không thêm đổi role/email. Không giả dữ liệu tài khoản ngân hàng hay KYC.
- Booking: 4 lớp status độc lập (booking, tiến độ xe, tiền, bất đồng). Chi tiết chia tóm tắt → bước tiếp theo hiện tại → timeline → biên bản/bằng chứng → tài chính/hủy, giữ mọi điều kiện hiện có. Admin chỉ đọc; Owner handover checkbox xác nhận hợp đồng ngoài app. Chưa đủ điều kiện thao tác phải có lý do từ dữ liệu hiện có, không tự nới `th:if` để hiển thị nút.
- Inspection: nhóm tình trạng/ảnh/gửi; trạng thái nháp, đã gửi, dispute rõ; upload form và gallery dùng được mobile/no-JS, giữ ảnh có auth và giới hạn hiện tại; so sánh BEFORE/AFTER cuộn trong vùng có nhãn, ảnh gốc không bị crop trong trang đọc bằng chứng. Không thêm ký điện tử, phân xử hoặc tự tính bồi thường.

## 4. Các đợt triển khai độc lập

Các đường dẫn template trong từng đợt tính từ `src/main/resources/templates/`; JS/CSS ghi đầy đủ khi nằm ngoài đó. Test Java nằm trong `src/test/java/com/carrent/payment/`. Không thêm test chỉ kiểm tra chuỗi class; kiểm tra render đúng quyền/trạng thái và interaction thực tế. Các bước kiểm tra chạy trên fixture riêng theo §5.

### UI-01 — Shell, navigation và quy chuẩn thành phần

**Phụ thuộc:** không. **Sản phẩm:** shell Owner/Admin tái sử dụng, active route chính xác, thành phần mẫu có trang dùng thật.

**File sửa:** `fragments/head.html`, `fragments/header.html`, `fragments/messages.html`, `fragments/pagination.html`; các shell lặp trong `owner/cars.html`, `owner/car-form.html`, `owner/bookings.html`, `owner/booking-detail.html`, `admin/cars.html`, `admin/car-detail.html`. Chỉ sửa `src/main/resources/static/css/custom.css`/`fragments/footer.html` khi có lỗi layout được chứng minh.

**File tạo:** `fragments/workspace.html` (sidebar/topbar theo role, active item truyền tường minh); chỉ tạo `fragments/ui.html` khi ít nhất hai caller dùng chung heading/status/empty panel. Các kiểu nút/field dùng class Tailwind nhất quán trong template, không ép mọi control qua fragment. Chữ ký dự kiến và cách giữ public header contract nằm ở §5 của bản cập nhật 08/11.

**Contract:** shell dùng principal/role hiện có và `notificationBell`, không yêu cầu thêm controller model cho tên người dùng; current section truyền từ page. Không render trùng header cố định. Các fragment mới chỉ mang presentation, không query dữ liệu hoặc quyết định quyền nghiệp vụ.

- [x] Ghi manifest baseline, đọc prototype Owner cars/Admin users và chụp layout hiện tại của các caller trên fixture.
- [x] Port shell B theo §3.1; giữ mọi link thật/logout/chuông, thêm đường vào Gói đăng xe và phạm vi tài chính; navbar public xác định active đúng route.
- [x] Thay shell lặp tại 6 trang đã có layout B, giữ nguyên phần body chức năng; áp dụng mẫu nút/form/flash trên các trang bị chạm. Nội dung Admin car detail vẫn thuộc UI-06C.
- [x] Kiểm tra 1440/1024/390/320, focus/menu/role/CSRF logout, public/header/account menu và notification badge không hồi quy. Hoàn thành khi không chồng header/sidebar, không mất link theo quyền và không có horizontal scroll toàn trang.
- [x] Lưu ảnh/evidence + bàn giao tên fragment và chữ ký thực tế để UI-02–07 dùng; chưa đánh dấu nội dung các trang khác hoàn thiện.

### UI-02 — Owner dashboard và tài chính cá nhân (ưu tiên đầu tiên sau shell)

**UI-02 — 2026-11-08:** dashboard Owner, finances đủ 9 role/view và overview dùng chung đã triển khai, kiểm chứng cục bộ: focused **47/47**, Chrome **1233 kiểm tra**, **28 ảnh trước/139 ảnh sau**; 20 bảng fixture không đổi, provider 0, cleanup đã xác nhận. Xem [bàn giao UI-02](ui-02-handoff.md). Human visual review/native zoom 200% còn chờ; UI-03 trở đi và gate live giữ mở.

**Phụ thuộc:** UI-01. **File sửa:** `owner/dashboard.html`, `payment/finances.html`, `subscription/overview.html`; dùng fragments chung, không đổi `FinancialViews`, `FinancialController`, `OwnerDashboardService/Controller`.

**Phân tích chi tiết sau UI-01:** [kế hoạch UI-02 ngày 08/11](ui-02-analysis-and-plan-2026-11-08.md) bổ sung `fragments/workspace.html` chỉ để phân biệt active scope hai link tài chính Admin bằng tham số `financeView` đang có. Giữ ba trang/fragment chính nêu trên và các hợp đồng backend; UI-02 đã được triển khai và kiểm chứng theo [bàn giao](ui-02-handoff.md).

**Contract:** nhận `stats/subscriptionAvailable/subscription` hiện có; finances nhận `view/obligations/refunds/reviewReceipts/pageNumber`. Giữ DOM selectors của dashboard và ID overview.

- [x] Trên fixture, lập bảng giá trị kỳ vọng cho Owner có dữ liệu, không có dữ liệu và module unavailable; lưu ảnh trước. Đọc `OwnerRevenueTest.java`, `FinancialViewsTest.java`, `PaymentAccessAuditTest.java` và browser harness P5.7 trước khi tái sử dụng.
- [x] Dựng dashboard theo wireframe §3.2; CTA, gói, KPI, cảnh báo đủ dữ liệu; đổi token không hợp lệ sang token sẵn có.
- [x] Dựng finances theo §3.3 cho cả 4 view và 3 role; không chỉ sửa `collected`. Có badge/active tab, bảng desktop/card mobile, empty từng nhóm, paging giữ scope.
- [x] Kiểm tra matrix: Owner collected/refunds/paid; Customer paid/refunds và bị chặn collected/managed; Admin cả 4 view. Data của tài khoản khác không xuất hiện; latestIntent chỉ payer, tài khoản refund vẫn che đúng quyền.
- [x] Browser kiểm tra dữ liệu dài, 20+ hàng/paging, tiền lớn, phần nguồn không sẵn sàng, desktop/mobile/keyboard. Hoàn thành khi hai URL người dùng phản ánh có hierarchy/action rõ, số liệu đúng baseline và không gọi thêm API tổng hợp.
- [x] Ghi ảnh trước/sau và review trực quan, handoff; overview trên plans/cars không bị lỗi sau khi dùng lại.

### UI-03 — Checkout, kết quả và hoàn tiền

**UI-03 — 2026-11-08:** đã triển khai checkout/result/refund/refund-summary và refund-links; focused **135/135**, Node **8/8**, Chrome **828 kiểm tra + 28 bổ sung**, **36 ảnh trước/138 ảnh sau**. GET/poll/preview kiểm tra 20 bảng theo từng đoạn; 4 POST audit giữ invariant, fake payout 0; cleanup đã xác nhận. Xem [bàn giao UI-03](ui-03-handoff.md). Human visual review/native zoom 200% còn chờ; UI-04 trở đi và các gate live giữ mở.

**Phụ thuộc:** UI-01/02. **File sửa:** `payment/checkout.html`, `payment/result.html`, `payment/refund.html`, `payment/refund-summary.html`, `payment/refund-links.html`.

**Phân tích chi tiết sau UI-02:** [kế hoạch UI-03 ngày 08/11](ui-03-analysis-and-plan-2026-11-08.md) chốt layout A, guard CTA theo model thật (không có field `canCreate`), hai kiểu refund model với quyền/masking khác nhau, giữ polling/terminal và kiểm tra CSS `[hidden]`. Matrix fixture tách đọc/preview/POST, clock browser đồng bộ server; UI-03 đã triển khai và kiểm chứng cục bộ theo [bàn giao](ui-03-handoff.md).

**Contract:** giữ các model `payment/refund/recipientForm/destination`, POST preview/confirm/cancel/retry và toàn bộ polling selectors tại §3.4. `static/js/payment-polling.js` là caller phải kiểm tra, mặc định không sửa.

- [x] Chụp fixture checkout advance/balance/subscription, result các nhánh, refund nhập/preview/processing/success. Đọc `PaymentAccessAuditTest.java`, `RentalBalanceUiTest.java`, `RentalCancellationUiTest.java`, `RefundRecipientTest.java` và `RefundServiceTest.java`.
- [x] Dựng checkout/result theo §3.4, giữ rõ tiền 30%/70%, hạn server, hủy đặt xe/hủy intent và lời giải thích tiền muộn.
- [x] Dựng refund recipient 2 bước + summary theo quyền; validation field, preview tên đã xác minh, checkbox/CSRF, lỗi lookup vẫn hiển thị; không bỏ gate để hoàn thiện màn hình success.
- [x] Kiểm tra polling success/pending/expired/cancelled/refund pending và lỗi mạng; QR không crop/tràn, server deadline không đổi khi reload; no-JS có tải lại và form POST hoạt động.
- [x] Kiểm tra preview không chi tiền, xác nhận không tự báo SUCCEEDED, không lộ tài khoản đầy đủ ngoài recipient; provider fake ghi nhận không có live call. Hoàn thành khi tất cả nhánh đã có layout và contract JS/quyền vẫn đúng.
- [x] Lưu evidence/handoff, nêu riêng những trạng thái đã dùng fixture và blocker provider thật còn mở.

### UI-04 — Gói đăng xe và trạng thái order

**UI-04 — 2026-11-08:** đã hoàn thiện gói đăng xe và order trong workspace Owner; tái sử dụng overview/refund-links. Focused **69/69**, Chrome **348 kiểm tra**, **40 ảnh trước/51 ảnh sau**; 20 bảng theo từng đoạn đọc, 5 POST/2 control audit, browser payout 0; fixture đã dọn. Xem [bàn giao UI-04](ui-04-handoff.md). Human visual review/native zoom 200% còn chờ; UI-05A trở đi và gate live giữ mở.

**Phụ thuộc:** UI-01/02, dùng refund-links đã đồng bộ ở UI-03 khi có. **File sửa:** `subscription/plans.html`, `subscription/detail.html`, `subscription/overview.html`; kiểm tra callers `owner/dashboard.html`/`owner/cars.html`.

**Contract:** `plans/subscription/order/intentRefunds` và form `plan` hiện có; `intentRefunds` là model dùng trong `payment/refund-links.html`. Giữ `subscription-plans`, `subscription-order`, `subscription-overview`, `data-order-id/data-status` và snapshot order.

- [x] Tạo ảnh baseline fixture no-plan, active, expired, quota đầy, pending và tạm ẩn. Đọc `SubscriptionUiTest.java`, `SubscriptionOwnerUiTest.java`, `SubscriptionDowngradeTest.java`.
- [x] Dựng card theo §3.5, CTA trạng thái đúng loại gói; hiển thị quota/hạn/đơn mua gói hiện có, không thêm danh sách lịch sử chưa có model.
- [x] Dựng order PENDING/APPLIED/EXPIRED/CANCELLED/REFUND_PENDING + link từng refund; thao tác hủy vẫn POST và không hủy gói đã cấp.
- [x] Browser kiểm tra mua/gia hạn cùng tier trên fixture, tier khác đang active bị khóa có lý do, hạ gói sau expiry không tự chọn xe. QR/quyền vẫn theo backend; dữ liệu tier dùng catalog thật, không hardcode giá vào markup.
- [x] Lưu ảnh 3 kích thước + keyboard/no-JS, handoff. Hoàn thành khi Owner tìm thấy Gói đăng xe từ nav và hiểu gói/quota/thao tác tiếp theo trong mỗi trạng thái.

### UI-05 — Admin dashboard, tài chính, tài khoản và booking

**UI-05C — 2026-11-08:** đã hoàn thiện booking list/detail Admin, đủ **15/15 trang** về implementation/kiểm chứng local. Suite cuối **89/89**, Node **8/8**, Chrome **482 kiểm tra**, **20 ảnh trước/109 ảnh sau**; smoke cuối đủ 15 trang và shared callers, 20 bảng đọc không đổi, payout 0 và fixture đã dọn. Xem [bàn giao UI-05C](ui-05C-handoff.md). Còn **0 đợt** trong scope gốc; human/native zoom/live giữ riêng, chưa triển khai UI-06/UI-07.

**UI-05B — 2026-11-08:** đã hoàn thiện danh sách/hồ sơ tài khoản Admin (2 trang), giữ self/CSRF/status-only. Focused **22/22**, Chrome **296 kiểm tra**, **16 ảnh trước/37 ảnh sau**; 20 bảng đọc theo đoạn, 3 POST audit chỉ target status/updatedAt, payout 0; fixture đã dọn. Xem [bàn giao UI-05B](ui-05B-handoff.md). Đã triển khai **13/15 trang**, còn **UI-05C: 1 đợt / 2 trang** và smoke cuối. Human/native zoom/live giữ riêng.

**UI-05A — 2026-11-08:** đã hoàn thiện dashboard và tài chính Admin (3 trang), giữ `fields(r)` cho booking. Focused **32/32**, Chrome **427 kiểm tra**, **36 ảnh trước/53 ảnh sau**; 20 bảng GET không đổi theo đoạn và toàn phiên sau phục hồi control; payout 0, fixture đã dọn. Xem [bàn giao UI-05A](ui-05A-handoff.md). Còn **2 đợt / 4 trang: UI-05B và UI-05C** trong phạm vi 15 trang; human visual review/native zoom 200% và gate live giữ riêng.

**Phân đợt:** UI-05A/B/C và smoke cuối 15 trang đạt kiểm chứng cục bộ; human/native zoom/live giữ riêng.

**Phụ thuộc:** UI-01/02. **File sửa:** `admin/dashboard.html`, `admin/finance.html`, `admin/finance-detail.html`, `admin/finance-row.html`, `admin/users.html`, `admin/user-detail.html`, `admin/bookings.html`, `admin/booking-detail.html`.

**Contract:** không đổi controller/read model; giữ `kind/status/overdue/subjectType/subjectId/collectorId/sort/page`, user `q/role/status/page`, booking `status/attention/page`. Prototype users là tham chiếu chính, không copy các thao tác prototype mà hệ thống chưa có.

- [x] Chụp fixture của 7 trang, đọc `AdminRevenueTest.java`, `AdminPermissionsTest.java`, `AdminUserServiceTest.java`, `AdminBookingTest.java`; ghi rõ finance-row dùng ở list/detail/booking.
- [x] Dashboard + finance: shell B, KPI và cảnh báo tách phạm vi, tabs/toolbar/table/card/detail theo §3.5. Filter lỗi vẫn 400; phân trang không mất điều kiện.
- [x] Users + detail: table/card + badge + form trạng thái; giữ email/role chỉ đọc và cấm self-change. Không thêm bulk action/xóa user.
- [x] Booking list/detail: tóm tắt nhiều status, timeline và bằng chứng, tài chính theo nhóm. Admin không có button handover/refund/resolve.
- [x] Kiểm tra đủ 3 finance kind, filter/sort/page/deep link, role thường bị chặn, data dài/empty/module chưa sẵn sàng. Hoàn thành khi cả 7 trang dùng shell và components chung, không gộp nhầm phạm vi tiền hoặc mở thêm quyền.
- [x] Lưu ảnh/evidence và bàn giao. Nếu cần chia phiên vì dung lượng, dừng tại cụm dashboard+finance rồi users+bookings; checkbox toàn đợt chưa đóng cho tới khi cả hai cụm đạt.

### UI-06 — Đồng bộ workflow booking, biên bản và quản lý xe

**Phụ thuộc:** UI-01/02/03/04; Admin booking UI-05 dùng lại timeline nên kiểm tra cùng khi đổi fragment.

**File sửa:** `booking/list.html`, `booking/detail.html`, `booking/fragments.html`, `owner/bookings.html`, `owner/booking-detail.html`, `inspection/detail.html`, `owner/cars.html`, `owner/car-form.html`, `admin/cars.html`, `admin/car-detail.html` (9 trang + 1 fragment). Không làm lại gallery/car cards public.

**Contract:** giữ mọi `th:if` theo quyền/trạng thái, form/ID/dialog/data attributes và notification links; đổi presentation timeline không thay timestamps hay thứ tự nghiệp vụ.

- [ ] Trên fixture, lập matrix Owner/Customer/Admin theo PENDING, BEFORE, 30%, handover, AFTER/dispute, nhận lại, nợ70%, completed/cancelled; đọc `BeforeInspectionTest.java`, `AfterReturnUiTest.java`, `InspectionPrivacyTest.java`, `RentalBalanceUiTest.java`, `RentalCancellationUiTest.java`.
- [ ] Gom bước tiếp theo và trạng thái trong detail/timeline; không ẩn cảnh báo/lý do quan trọng trong tooltip chỉ hover; thay token font không có.
- [ ] Inspection: sắp nhóm form/ảnh/gửi/compare, giữ no-JS/upload/nháp riêng tư; kiểm tra thông tin nháp không bị render ở tài khoản khác và ảnh vẫn qua endpoint auth.
- [ ] Car management: shell/chip/action rows đồng bộ, phân biệt approval/listing/quota/expiry; form lỗi giữ nội dung hợp lệ, dialog reject/delete có focus và xác nhận; không nới điều kiện đăng xe.
- [ ] Browser kiểm tra flow fixture có quyền tương ứng, keyboard/modal close/focus-return, upload lỗi và no-JS. Hoàn thành khi 9 trang không có khối workflow mang hình thức khác hệ thiết kế và điều kiện hành động không thay đổi.
- [ ] Bàn giao evidence từng nhóm; không tự gọi luồng fixture là nghiệm thu tiền thật.

### UI-07 — Hồ sơ, thông báo, unavailable/error và hồi quy

**Phụ thuộc:** các đợt đã được chọn triển khai ở trên. **File sửa có điều kiện:** `profile/view.html`, `profile/edit.html`, `profile/change-password.html`, `notification/list.html`, `payment/finance-unavailable.html`, `admin/unavailable.html`, `error/400.html`, `403.html`, `404.html`, `405.html`, `413.html`, `error.html`, `fragments/footer.html`.

**Trang tham chiếu chỉ kiểm tra, sửa khi có hồi quy cụ thể:** `home/index.html`, `cars/list.html`, `cars/detail.html`, `auth/login.html`, `auth/register.html`, `booking/create.html`. Không biến hồi quy thành redesign các trang này.

- [ ] Đồng bộ nút/field/heading trên profile, giữ chỉ tên/điện thoại được sửa; không giữ lại mật khẩu trong DOM khi lỗi. Đọc `ProfilePasswordTest.java`, `ProfileSecurityTest.java`.
- [ ] Notification giữ unread/count/paging/read POST, tăng target/focus của action nếu cần; không đổi read scope hoặc tự mark-read bằng GET.
- [ ] Unavailable/error dùng panel đúng lý do + CTA hợp lệ; không giả dashboard empty, không lộ exception/ID riêng; giữ 400/403/404/405/413 và `Allow` hiện hành.
- [ ] Chạy matrix §5 cho mọi trang đã sửa và 6 trang tham chiếu; desktop/mobile/no-JS/zoom, không có console error mới liên quan UI. Mọi route trong §2 có trạng thái kiểm chứng hoặc blocker cụ thể.
- [ ] Cập nhật SDD/checklist và evidence; ghi riêng human visual review. Chỉ đánh dấu backlog UI hoàn tất khi các đợt đều đạt; số task nghiệp vụ/provider không đổi.

## 5. Kiểm tra và tiêu chí nghiệm thu

### 5.1. Chuẩn bị an toàn cho phiên implement

1. Đọc file này, bản đối chiếu 08/11 và SDD §8.7, chọn đúng một đợt/phần đợt. `git status --short`, `git branch --show-current`, `git rev-parse --short HEAD`, `git worktree list`; lập manifest hash các file sẽ sửa và baseline cần bảo toàn. Đề xuất dùng root hiện hành, chỉ đổi presentation/test của đợt, giữ branch/backend/config/index. Nếu được yêu cầu checkout riêng, đưa đủ prerequisite theo manifest và kiểm chứng baseline trước; không copy đè dirty files hoặc mặc định `main` đã có source chưa commit.
2. Dùng fixture DB riêng từ harness/test hiện hành; đọc cấu hình trước khi chạy Maven, không truyền DB ứng dụng qua environment vô ý. Không reset/migrate database đang chạy 8080, không dùng thông tin ngân hàng người dùng vào fixture/screenshot.
3. Đọc harness P5.7/P5.8 tại `.scratch/phase5/p57/browser/` và `.scratch/phase5/p58/browser/` nếu còn. Đây là nguồn tham khảo/historical evidence; phải kiểm tra manifest và chạy lại trên source mới, không lấy ảnh cũ làm ảnh sau sửa. Máy clone không có harness thì tạo fixture tối thiểu theo test hiện có, provider offline, ghi cấu hình không secret vào handoff.
4. Đọc lại tên test thực tế bằng `rg --files src/test` khi bắt đầu; các tên trong kế hoạch là snapshot source 2026-11-07. Chỉ sửa test khi selector/interaction có lý do thay đổi; không xóa assertion bảo mật để làm test xanh.

### 5.2. Matrix bắt buộc theo phạm vi bị sửa

| Trục kiểm tra | Tình huống | Đạt khi |
|---|---|---|
| Role/scope | anonymous, Customer, Owner A/B, Admin; view bị cấm và stale/non-ACTIVE theo test hiện hành | đúng 302/403/404 theo contract, không lộ data hoặc action của role khác |
| Viewport | 1440×1000, 1024×768, 390×844, 320×740 | không tràn trang, không chồng nav, CTA/form/tab đọc và bấm được; bảng chỉ cuộn trong vùng |
| Accessibility | keyboard toàn trang, focus, 200% zoom, tên control, tương phản | không focus trap, target nút ≥44px, lỗi gắn field, status có chữ/aria-live phù hợp |
| Dữ liệu | empty, nhiều trang, tên/email/code dài, tiền lớn, missing module | không cắt mất nghĩa, không hiện 0 thay unavailable, paging/filter không mất scope |
| Tiền/trạng thái | paid/collected/refunds/managed; pending/review/overdue/succeeded | không gộp tiền chờ thành đã thu/đã hoàn; số liệu khớp fixture và GET không phát lệnh chi |
| Form | valid/invalid/CSRF thiếu, preview-confirm, submit lại, dialog cancel | method/CSRF/validation và kết quả backend giữ nguyên, không mất input hợp lệ |
| Polling/no-JS | mạng lỗi, 401/403, reload/deadline, tắt JS | không báo thành công giả; không gia hạn QR; có tải lại và thao tác không JS hiện hữu |
| Shared callers | header, overview, finance-row, timeline, pagination | mọi caller bị ảnh hưởng đã render theo role và query riêng đúng |

Ví dụ assertion browser cho các trang đã mở hợp lệ (chỉ là kiểm tra layout, không thay visual review):

```javascript
const overflow = await page.evaluate(() =>
  document.documentElement.scrollWidth > window.innerWidth + 1);
if (overflow) throw new Error('Trang bị tràn ngang');
const current = page.locator('nav[aria-label="Phạm vi tài chính"] [aria-current="page"]');
if (await current.count() !== 1) throw new Error('Thiếu hoặc trùng phạm vi hiện tại');
```

Không chạy snippet tab trên trang không có nav tài chính. Kiểm tra class/computed style active và ảnh bằng mắt; một `aria-current` đúng chưa đủ chứng minh tab nhìn rõ.

### 5.3. Evidence và điểm dừng mỗi đợt

- Trước/sau ở 1440 và 390 cho mỗi trang bị thay bố cục; 320 cho các layout hẹp, 1024 cho shell; ảnh trạng thái quan trọng trong matrix của đợt. Chỉ dùng dữ liệu fixture, che/loại secret và dữ liệu ngân hàng thật.
- Handoff dưới `docs/plans/ui-XX-handoff.md`: source baseline, file thay đổi, routes/roles/states đã kiểm tra, lệnh và kết quả test, đường ảnh, blocker và bước tiếp theo. Ảnh/harness có thể ở `.scratch/ui-XX/` kèm manifest; nếu cần review trên máy khác thì cung cấp artifact để người nhận truy cập được.
- Chạy build/template render và các test hiện hành liên quan trên DB fixture đã xác nhận. Shared shell cần smoke public/auth + Owner/Admin; payment cần polling regression. Không chạy full suite lặp lại cho từng chỉnh padding nếu không có rủi ro còn lại.
- Human visual review ghi `chờ review` cho đến khi có người review thật; agent tự kiểm không thay review đó. Một test pass không đủ đóng hạng mục thiết kế.
- Dừng sau đợt được giao; bàn giao phần chưa đạt bằng blocker cụ thể. Không đóng gate giao dịch thật SePay/payOS hoặc sửa 83/86 chỉ vì backlog UI tiến triển.

## 6. Lệnh giao việc ngắn cho phiên sau

> Đọc `docs/SDD.md` §8.7, `docs/plans/ui-completion-2026-11-07.md` và `docs/plans/ui-worktree-analysis-2026-11-08.md`. Chỉ triển khai **UI-01** theo Stitch/Tailwind hiện có; dùng root làm baseline đề xuất, bảo toàn mọi dirty changes và thay shell đủ 6 caller. Không sửa nghiệp vụ/DB/config provider, không restart 8080 hay chuyển tiền thật. Kiểm chứng shell trên public/Owner/Admin ở desktop/mobile/keyboard và lưu ảnh + handoff; dừng trước UI-02. UI-02 kế tiếp sẽ hoàn thiện `/owner/dashboard` và `/payment/finances` đủ các view/quyền.
