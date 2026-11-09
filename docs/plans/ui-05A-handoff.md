# UI-05A — Bàn giao dashboard và tài chính Admin

Ngày 2026-11-08. Root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. **UI-05A triển khai và kiểm chứng cục bộ, dừng trước UI-05B.**

## 1. Đã thay đổi

- `admin/dashboard.html`: shell Admin, active Tổng quan; nhóm KPI toàn hệ thống, phí gói theo Admin hiện tại và cảnh báo riêng. Giữ cả 12 field backend và các deep link hiện có. Tiền nhận, phân bổ, hoàn thành công tách nhau; không gọi gross cash là số dư khả dụng hoặc doanh thu.
- `admin/finance.html`: shell Admin, tab ba kind có active style/aria-current; filter GET, control/label/focus, card responsive, empty và paging. Giữ đủ `kind/status/overdue/subjectType/subjectId/collectorId/sort/page` trên paging; filter submit quay trang đầu, reset giữ kind như trước.
- `admin/finance-detail.html`: shell Admin, snapshot tài chính, allocation theo kind, thông báo chỉ đọc và đường về danh sách; dữ liệu/guard vẫn từ projection cũ.
- `admin/finance-row.html`: giữ `fields(r)`, làm nổi tiền/trạng thái; nhóm nguồn/người nhận/tham chiếu/hạn/account đã che; cảnh báo overdue có ngữ nghĩa dt/dd. Ba caller list/detail/booking tiếp tục hoạt động; không redesign booking sớm.
- `AdminPermissionsTest`: thêm public GET regression xác nhận active navigation Admin trên ba trang và dữ liệu trước/sau không đổi. RED đã fail đúng thiếu shell trước sửa.

Không đổi Java main/controller/service/query, CSS/JS production, header/workspace, caller booking, unavailable, schema/seed ứng dụng hoặc config/provider. Không thêm dependency, generic component, chart, chức năng gửi tiền hoặc số dư giả.

## 2. Phân tích và contract

Xem [phân tích/kế hoạch UI-05A](ui-05A-analysis-and-plan-2026-11-08.md).

`DashboardStats` có số đếm hệ thống và số tiền thuộc Admin đang đăng nhập; Admin A/B không chia sẻ doanh thu. `AdminFinanceService.Row` là projection masked; unmatched receipt có field null, refunds đang chờ/quá hạn/chờ số dư không phải đã hoàn. `Filter` có tám tham số, ba kind, ba sort, enum/status whitelist và page size 20.

`fields(r)` vẫn một tham số, được gọi bởi `admin/finance.html`, `finance-detail.html` và `booking-detail.html`. Không đưa payload/credential/account đầy đủ vào template hoặc dùng role Admin để mở form recipient. Contract th:text/th:if/th:each/input/select/time/links được đối chiếu với baseline; no-cache và fallback unavailable giữ nguyên.

## 3. Kiểm chứng mới

| Kiểm chứng | Kết quả |
|---|---|
| Baseline | **31/31** Java, 0 failure/error/skip |
| Regression RED | **1/1 fail** đúng thiếu navigation Admin |
| Focused cuối | **32/32**, 0 failure/error/skip |
| Chrome | **427 kiểm tra**, 0 JavaScript error |
| Ảnh | **36 trước / 53 sau** |
| GET | **12 đoạn**, 20 bảng không đổi trong từng đoạn; snapshot toàn phiên cuối bằng ban đầu sau phục hồi control |
| Provider browser | Fake payout create/get/find **0**, không gọi provider thật |
| Cleanup | **3 schema browser có metadata** đã xóa và truy vấn lại, fixture port đóng; ready/credentials đã dọn, 8080/config/index/HEAD giữ nguyên |

Suite gồm `AdminRevenueTest`, `AdminPermissionsTest`, `AdminBookingTest`, `WorkspaceUiTest`. Source cuối khớp 63 resources trong build cách ly. Không lấy kết quả của UI-04 làm kiểm chứng UI-05A mới.

Browser tại 1440×1000, 1024×768, 390×844, 320×740: dashboard hai Admin, cả ba kind và toàn bộ status enum, detail khoản nợ/receipt chưa khớp/refund READY/null/SUCCEEDED; số tiền tới 9.000.000.000.000.000 VNĐ không làm tràn. Source Admin/Owner tách, tên Admin dài có markup được escape, account chỉ còn ••••7890; payload/name tài khoản đầy đủ không hiện.

Filter thật qua form GET và pagination 21 hàng giữ cả tám query; thứ tự AMOUNT, LATEST, OLDEST; reset, empty và input sai 400. Deadline được kiểm tra trước/đúng/sau; SUCCEEDED không bị coi overdue. Role Owner/Customer/anonymous/stale banned hoặc mất role bị từ chối; thiếu schema có thông báo unavailable, không trả KPI 0 giả.

Admin booking detail vẫn render ba nhóm finance qua fragment chung. GET toàn bộ ma trận không ghi dữ liệu; seed/clock/rename bảng trong fixture được tách segment và phục hồi trước so snapshot cuối. No-JS filter/detail, menu mobile, keyboard, target ≥44px cho control sửa và reflow 720 CSSpx đã kiểm tra. Agent xem ảnh đại diện; human review vẫn riêng.

## 4. Evidence và lệnh

```powershell
.\mvnw.cmd -f .scratch/ui-05A/pom.xml "-Dtest=AdminRevenueTest,AdminPermissionsTest,AdminBookingTest,WorkspaceUiTest" test
$env:UI05A_OUT = '.scratch/ui-05A/browser-verified'
node .scratch/ui-05A/browser-check.cjs
```

Browser cần Java `Ui05ABrowser` fresh fixture cùng output đã ready; không reuse schema/credentials đã dọn hoặc chạy trên 8080. POM/build/harness tại `.scratch/ui-05A/` là evidence local, không phải prerequisite sản phẩm.

- `baseline.json`, `port8080-before.json`: manifest fresh gồm file xóa sẵn, dirty files, config/index/HEAD/branch.
- `baseline-tests.log`, `red-tests.log`, `final-tests.log`: các lượt giữ riêng.
- `browser-before-verified/capture.json`: 36 ảnh trước mới; `browser-verified/report.json`, SQL trước/segment/sau, `read-post-audit.json`, `provider-calls.json`, `accessibility.txt`, metadata/cleanup: evidence cuối.
- `schema-cleanup-audit.json`, `fixture-port-audit.json`, `validation.json`: cleanup, contract/hash/allowlist và resource match.

Ảnh:

- [Dashboard Admin A](../../.scratch/ui-05A/browser-verified/dashboard-admin-a-1440.png), [Admin B mobile](../../.scratch/ui-05A/browser-verified/dashboard-admin-b-390.png).
- [Tra cứu nghĩa vụ](../../.scratch/ui-05A/browser-verified/list-obligations-1440.png), [paging bộ lọc](../../.scratch/ui-05A/browser-verified/paging-filter-last-390.png), [empty](../../.scratch/ui-05A/browser-verified/empty-320.png).
- [Receipt chưa khớp](../../.scratch/ui-05A/browser-verified/detail-unmatched-320.png), [refund READY](../../.scratch/ui-05A/browser-verified/detail-refund-ready-390.png), [booking caller](../../.scratch/ui-05A/browser-verified/booking-caller-390.png).

## 5. Giới hạn và lỗi setup giữ riêng

- Hai lượt fixture seed đầu bị CHECK chặn: bank BIN có giá trị nhưng account null, sau đó business_key của nghĩa vụ demo không đúng công thức. Đã sửa fixture để bank/account đồng bộ null và tạo booking/tham chiếu hợp lệ; không nới schema hoặc sửa service. Các lượt lỗi giữ log riêng; try-with-resources đóng/drop schema, nhưng chưa có metadata nên không tính chúng vào số schema được truy vấn theo ID trong cleanup audit.
- Browser ma trận đầu dừng vì selector đếm KPI tìm strong trong h2 thay vì section cảnh báo. Đã sửa selector theo aria-labelledby, giữ yêu cầu đủ 12 metric và chạy lại trên fixture mới; artifact lỗi giữ riêng, không tính lượt lỗi là passing aggregate.
- QR/provider không thuộc nghiệm thu UI-05A. Tailwind/fonts dùng cache local và external request bị chặn. Provider thật, full suite/JAR, native zoom 200% chưa kiểm chứng; reflow CSS và agent xem ảnh không thay human visual review.
- `.env`, local config, schema ứng dụng, seed/uploads, backend, index/HEAD và dirty changes ngoài phạm vi giữ nguyên; `uploads/seed-car-5.png` đã xóa trước baseline, không phục hồi. Không commit/push/stage/merge/chuyển worktree/restart 8080/bật scheduler/provider ứng dụng.

## 6. Còn bao nhiêu đợt?

Trong phạm vi **15 trang ban đầu**, sau UI-05A đã triển khai 11/15 trang, còn **2 đợt / 4 trang**:

1. **UI-05B** — Admin user list/detail (2 trang).
2. **UI-05C** — Admin booking list/detail (2 trang), rồi smoke lại 15 trang và caller dùng chung trên source cuối.

UI-06/UI-07 thuộc backlog mở rộng, chưa nằm trong lần hoàn thiện 15 trang này. Human/native zoom/live là gate riêng. Backlog UI-05 tổng chỉ đóng mục dashboard+finance; phần users, bookings và nghiệm thu toàn nhóm giữ mở. Gate P4.2-A/P5.9/P8.8 không đổi.
