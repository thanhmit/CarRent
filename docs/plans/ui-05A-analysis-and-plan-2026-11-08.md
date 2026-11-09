# UI-05A — Phân tích và kế hoạch dashboard / tài chính Admin

> **For agentic workers:** Dùng `superpowers:executing-plans`, thực hiện một đợt trong phiên hiện tại, không subagent. Sau kiểm chứng/bàn giao dừng trước UI-05B; không commit/push hoặc chuyển worktree.

**Goal:** Hoàn thiện ba trang dashboard, tra cứu và chi tiết tài chính Admin; giữ phân biệt tiền Admin/Owner và trạng thái thu/hoàn.

**Architecture:** Tái sử dụng workspace UI-01 và token hiện có. Sửa đúng bốn template, giữ `fields(r)` để Admin booking detail tiếp tục dùng; không thay controller/service/query hoặc JS/CSS production.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Tailwind; Maven và browser fixture MySQL riêng.

**Spec:** [15 trang/UI-05A](ui-15-pages-readiness-2026-11-08.md), [wireframe §3.5](ui-completion-2026-11-07.md), [SDD §8.7](../SDD.md), [bàn giao UI-04](ui-04-handoff.md).

## Global Constraints

- Root `feat/phase5-profile-password`, giữ dirty changes và file xóa sẵn; không checkout/reset/stash/worktree/stage/commit/push/merge.
- Không đổi Java main, schema/seed ứng dụng, root POM, `.env`, local config/provider, scheduler, runtime 8080; chỉ schema/port fixture riêng, fake provider. Không chuyển tiền hoặc gọi API tài chính thật.
- Giữ GET/form/filters/enum/masking/role/stale checks, fragment signature, các link hiện hành và timezone Việt Nam. Không thêm số dư khả dụng, chart, thao tác chi tiền hoặc suy trạng thái thành công.
- UI-05B/C, human review, native zoom 200%, full suite/JAR và live là các mục riêng.

## 1. Source và model

`DashboardStats` có counts toàn hệ thống (users/cars/bookings, unmatchedReceipts, refundsPending/Overdue, balanceDebtsOverdue) và số tiền theo Admin đang đăng nhập (subscriptionRevenue, monthlySubscriptionRevenue, grossCashReceived, allocatedCash, refundsSucceeded). Không cộng tiền Owner vào doanh thu Admin hoặc gọi gross cash là số dư.

`AdminFinanceService.Filter(kind,status,overdue,subjectType,subjectId,collectorId,sort,page)` dùng ba kind OBLIGATIONS/RECEIPTS/REFUNDS, sort LATEST/OLDEST/AMOUNT, Page size 20. Giữ đủ tám query khi paging. Tabs và xóa bộ lọc tiếp tục reset theo contract cũ; submit filter không gửi page để quay trang đầu. Invalid enum/status/ID trả 400; không tự sửa query sai thành kết quả rỗng.

`Row(id,status,message,purpose,subjectType,subjectId,payerId,collectorId,collectorRole,amount,allocated,occurredAt,dueAt,overdue,account)` là projection đã che account. Receipt unmatched có purpose/subject/payer null và vẫn hiển thị; due/account null không tạo dữ liệu giả. Refund đang chờ, quá hạn hoặc chờ số dư không được báo đã chi.

`fields(r)` dùng trên finance list, detail và Admin booking detail; giữ một tham số. Không đổi caller booking để redesign sớm UI-05C. Label phân bổ/thanh toán theo kind nằm tại detail như trước, không suy kind trong fragment chung.

Dashboard/list/detail đều dùng `sidebar('dashboard',null)` hoặc `sidebar('finances','managed')` và topbar Admin. `unavailable` giữ route/fallback cũ, không giả KPI 0 khi thiếu schema.

## 2. File và ranh giới

| File | Trách nhiệm |
|---|---|
| `templates/admin/dashboard.html` | Shell; KPI hệ thống, phí gói Admin, tiền thu/phân bổ/đã hoàn riêng, cảnh báo và deep links |
| `templates/admin/finance.html` | Shell; tab active; filter có label/focus/target; result cards responsive, empty, paging |
| `templates/admin/finance-detail.html` | Shell; nhóm snapshot/nguồn/tiền/trạng thái/hạn, allocation theo kind, navigation chỉ đọc |
| `templates/admin/finance-row.html` | Bố cục fields(r), amount/status nổi bật, source/masking/null/deadline rõ; caller booking giữ nguyên |
| `AdminPermissionsTest.java` | Regression public GET của cả ba page: navigation Admin active, GET vẫn chỉ đọc |
| `.scratch/ui-05A/` | Baseline/POM/build/runtime/screenshots/audit cách ly |
| `docs/plans/ui-05A-handoff.md`, README/SDD/checklists | Kết quả và số đợt còn lại |

Giữ card list thay vì thêm table/DOM mobile trùng lặp: model nhiều field, card đã có seam dùng chung và phù hợp các viewport.

## 3. Checkpoint A — Baseline và regression

**UI-05A — 2026-11-08:** đã hoàn thiện dashboard và tài chính Admin (3 trang), giữ `fields(r)` cho booking. Focused **32/32**, Chrome **427 kiểm tra**, **36 ảnh trước/53 ảnh sau**; 20 bảng GET không đổi theo đoạn và toàn phiên sau phục hồi control; payout 0, fixture đã dọn. Xem [bàn giao UI-05A](ui-05A-handoff.md). Còn **2 đợt / 4 trang: UI-05B và UI-05C** trong phạm vi 15 trang; human visual review/native zoom 200% và gate live giữ riêng.

- [x] Manifest mới trước source edit; baseline `AdminRevenueTest,AdminPermissionsTest,AdminBookingTest,WorkspaceUiTest`; chụp trước dashboard, ba kind, detail và booking caller.
- [x] RED qua GET `/admin/dashboard`, `/admin/finance`, `/admin/finance/OBLIGATIONS/201`: HTML phải có `aria-label="Điều hướng quản trị"` và link tương ứng `aria-current="page"`; không test chuỗi CSS. Chạy test mới xác nhận thiếu shell trước sửa.

## 4. Checkpoint B — Triển khai bốn template

- [x] Dashboard dùng `<th:block th:replace="~{fragments/workspace :: sidebar('dashboard', null)}"></th:block>`; giữ cả 12 field stats và toàn bộ warning links, phân cấp ba nhóm số liệu, thêm target/focus.
- [x] Finance dùng `sidebar('finances','managed')`, tab `aria-current` + style active, filter GET giữ names/values/action; class input/select/button target ≥44px, cards/empty/paging không tràn; paging giữ đúng tám query hiện có.
- [x] Detail dùng shell finance, reuse `<dl th:replace="~{admin/finance-row :: fields(${row})}"></dl>`; labels allocated chỉ RECEIPTS/OBLIGATIONS như cũ, REFUNDS không tự báo đã trả. Fragment vẫn `fields(r)` với text/model null/masking/time nguyên nghĩa, amount/status và source nhóm rõ.
- [x] GREEN focused tests và render caller booking, không sửa header/workspace/unavailable/booking source.

## 5. Checkpoint C — Kiểm chứng và bàn giao

- [x] Browser 1440/1024/390/320: một h1, no overflow, controls sửa ≥44px, menu mobile/keyboard/no-JS, labels/escaping và reflow 720 CSSpx. Native zoom/human còn riêng.
- [x] Test ba kind, statuses, overdue trước/đúng/sau hạn, null/unmatched, empty, amounts lớn, collector Admin/Owner, source scope và detail masked. Filters/paging kiểm tra URL thật, mọi query được giữ; input enum/ID sai 400.
- [x] Native login Admin A/B, Owner/Customer/anonymous/stale; các GET không ghi DB qua snapshot 20 bảng; payout 0. Có fixture control đổi state/clock thì tách segment, phục hồi lại snapshot đầu khi kết thúc.
- [x] Dọn schema/ready/credentials và kiểm tra schema absent/port đóng/8080 giữ nguyên. Audit hash/config/index/HEAD/contracts và resource build khớp source; bảo toàn tài liệu cập nhật song song nếu phát hiện, không ghi lại baseline gốc.
- [x] Cập nhật bốn checkbox UI-05A trong kế hoạch 15 trang; backlog UI-05 tổng chỉ đánh phần dashboard/finance đã đạt, không đóng user/booking/handoff tổng. Sau UI-05A còn UI-05B và UI-05C: **2 đợt, 4 trang**. UI-06/07 ngoài phạm vi 15 trang.

Lệnh focused:

```powershell
.\mvnw.cmd -f .scratch/ui-05A/pom.xml "-Dtest=AdminRevenueTest,AdminPermissionsTest,AdminBookingTest,WorkspaceUiTest" test
```
