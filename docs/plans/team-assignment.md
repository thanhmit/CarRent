# CarRent — Phân công ba thành viên

**Checkpoint P5.8 + P5.9 local — 2026-11-04:** P5.8 hoàn thành **5/5**: ma trận quyền/CSRF/HMAC, biên thời gian, GET chỉ đọc, queue/quota/restart và tài liệu hiện hành. Sửa stale Owner/Admin khi mutation xe, vòng khóa notification/Admin status và HTTP405; regression RED/GREEN được giữ. P5.9 hoàn thành **5/6 checklist local**, còn **gate live**: hành trình HTTP mua gói → tạo/duyệt xe → booking mới → BEFORE →30%→Owner hợp đồng/giao→AFTER bất đồng→nhận lại→70%→COMPLETED; lối vào VIETQR_V1 mặc định tắt, giới hạn pilot và quyền Customer hiện tại. Full isolated **clean verify 1053/1053 JUnit, 0 failure/error/skip**, JAR; Node polling **6/6**; Chrome **256/256**, 108PNG tại1440/390/320. **83/86 task**, còn3 (`ducbinhnguyen07ars`2: P5.9/P8.8; `thanhmit`1: P4.2-A). **P5.9/P8.8/P4.2-A chưa đóng**, chưa có bằng chứng giao dịch/provider/human/migration live. Bảo toàn DB ứng dụng/config/seed/uploads/phase0/dirty prerequisites/refs/index; không kích hoạt hoặc Git publication. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.7 — 2026-11-04:** hoàn thành **4/4 checklist local** dashboard Owner và trang chủ: gói/hạn/quota, xe/booking scoped principal, tiền thuê đã phân bổ và hoàn/nợ riêng; hoàn một phần giữ receipt, pending chưa tính đã hoàn, deadline theo Clock. Home tối đa8 xe mới nhất dùng public policy Phase8 hiện có; quyền preview riêng được giữ. Current ACTIVE OWNER/stale session/IDOR/XSS và GET chỉ đọc đã kiểm chứng. Focused **33/33**, full isolated **clean verify 1016/1016 JUnit, 0 failure/error/skip**, JAR; Chrome **152/152**, 36PNG ở1440/390/320; SQL invariants/provider0/cleanup đã xác nhận. **82/86 task**, còn **4** (`ducbinhnguyen07ars`3, `thanhmit`1; `huy69332`0). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. **P8.8/P4.2-A live vẫn pending**. Tiếp theo **P5.8 → P5.9**, không đóng P5.9 khi gate thật chưa đạt. Bảo toàn P5.5/P5.6 chưa commit, phase0/config/seed/uploads/DB ứng dụng/refs/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.5 + P5.6 — 2026-11-04:** hoàn thành **3/3 + 3/3 checklist local**: profile của tài khoản hiện tại, chỉ sửa tên/điện thoại; đổi mật khẩu kiểm tra BCrypt hash DB mới nhất, tối thiểu6ký tự/tối đa72byte UTF-8, form lỗi không giữ mật khẩu. Kiểm tra ACTIVE/role hiện tại dưới khóa User; chống IDOR/mass assignment/CSRF/XSS và ghi đè hash khi đồng thời. Navbar tên cache được thông báo đăng nhập lại; giữ policy session/logout hiện hành. Focused **12/12**, full isolated **clean verify 1005/1005 JUnit, 0 failure/error/skip**, JAR; Chrome **113/113**, 24PNG ở1440/390/320; SQL 15khoảng/20bảng, provider0, cleanup đã xác nhận. **81/86 task**, còn **5** (`ducbinhnguyen07ars`4, `thanhmit`1; `huy69332`0). Phụ trách `huy69332`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A live vẫn pending. Dừng sau đợt này; tiếp theo **P5.7 riêng của ducbinhnguyen07ars**. Bảo toàn phase0/config/seed/uploads/DB ứng dụng; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.4 — 2026-11-04:** hoàn thành **4/4 checklist local** Admin booking/biên bản/khoản còn nợ: list/detail chỉ đọc, nghĩa vụ30/70 và receipts/refunds đúng booking, BEFORE/AFTER private kể cả COMPLETED còn bất đồng; lọc nợ70% quá hạn1giờ và hoàn quá hạn theo Clock/deadline nghiệp vụ. Admin không ký/giao nhận thay Owner hoặc buộc resolve AFTER. Full isolated **clean verify 993/993 JUnit, 0 failure/error/skip**, JAR; Chrome **205/205**, 30 PNG ở1440/390/320. **79/86 task**, còn **7** (`ducbinhnguyen07ars`4, `huy69332`2, `thanhmit`1). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A live vẫn pending. Tiếp theo **P5.5 + P5.6 của huy69332**. Bảo toàn dirty prerequisites/phase0/config/seed/uploads/DB ứng dụng/HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.3 — 2026-11-04:** hoàn thành **4/4 checklist** Admin quản lý tài khoản: q/role/status/page20, hồ sơ và số liệu scoped target, POST status ACTIVE/INACTIVE/BANNED dưới khóa và kiểm tra Admin hiện tại; chống self-change/role-email tamper/CSRF, banned login và stale request trong queue. Full isolated **clean verify 986/986 JUnit, 0 failure/error/skip**, JAR; Chrome **107/107**, 33 PNG. **78/86 task**, còn **8** (`ducbinhnguyen07ars`5, `huy69332`2, `thanhmit`1). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A gate thật vẫn pending. Tiếp theo **P5.4 riêng**. Bảo toàn dirty P8/P5,phase0/config/seed/uploads,DB ứng dụng,HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.1 + P5.2 — 2026-11-04:** hoàn thành **5/5 + 5/5 checklist** về read model và dashboard/tài chính Admin. Phí gói từ order applied của đúng Admin; tiền thuê, tiền trùng/chờ đối soát và hoàn tiền tách riêng. Tháng theo giờ Việt Nam; filter/phân trang/sort whitelist, kiểm tra Admin ACTIVE hiện tại, tài khoản được che và GET chỉ đọc. Full isolated **clean verify 973/973 JUnit, 0 failure/error/skip**, JAR; Chrome **103/103**, 33 PNG. **77/86 task**, còn **9**: `ducbinhnguyen07ars` 6, `huy69332` 2, `thanhmit` 1. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. Đây là đợt code độc lập được chuẩn bị theo roadmap khi P8.8 đã xong local; **P8.8 và P4.2-A vẫn pending gate migration/human/provider/live**. Đợt code tiếp theo **P5.3 riêng**. Bảo toàn P8 chưa commit/phase0/config/seed/uploads, DB ứng dụng, HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P8.8 local — 2026-11-04:** hoàn tất phần triển khai/kiểm chứng local: cặp Owner/Admin pilot mặc định từ chối, chặn tạo/reuse và ẩn QR ngoài phạm vi; receipt/refund/cancel/đối soát giữ nguyên. Full isolated **clean verify957/957 JUnit, 0 failure/error/skip**, JAR; Chrome **110/110**, 45PNG. E2E Small/quota/pending/expiry/gia hạn/Business→Small/manual selection và duplicate/late refund dùng provider offline. **P8.8 chưa đóng**: chờ migration/human review, P4.2-A, giao dịch/hoàn tiền thật và cho phép kích hoạt. Tiến độ giữ **75/86 task**, còn11. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.8 local chưa commit trên feature `705f320`/main `6948664`; DB ứng dụng/phase0/config/seed/uploads giữ nguyên. Không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.7 — 2026-11-04:** hoàn thành **5/5 checklist**: UI Owner xem gói/hạn/quota gồm pending và xe tạm ẩn; gia hạn đúng loại; order/result dẫn từng refund của đúng Owner. Dùng chung Phase4: Admin thiếu tiền giữ WAITING_FUNDS, thông báo Admin và cảnh báo quá hạn; không dùng kênh Owner hoặc báo hoàn thành giả. Full isolated **clean verify 950/950 JUnit, 0 failure/error/skip**, JAR; Chrome **123/123**, 54PNG. **75/86 task**, còn11 (45 task mới xong34). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.7 chưa commit, feature `705f320`/main `6948664` giữ nguyên; phase0/config/seed/uploads và DB ứng dụng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước **P8.8 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.6 — 2026-11-04:** hoàn thành **5/5 checklist**: gia hạn giữ ý định đăng; hạ gói sau expiry ẩn toàn bộ xe cũ atomic với receipt/entitlement, Owner tự chọn xe theo quota. Full isolated **clean verify 943/943 JUnit, 0 failure/error/skip**, JAR; Chrome **87/87**, 42PNG ở320/390/1440 và no-JS forms. **74/86 task**, còn12 (45 task mới xong33). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review Car/Booking; agent review không thay human review. P8.1–P8.6 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.7; tiếp theo **P8.7 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.4 + P8.5 — 2026-11-04:** hoàn thành **6/6 + 6/6 checklist**: quota theo Owner, current reads chống vượt suất, public predicate trực tiếp theo hạn gói và POST booking kiểm tra lại. Full isolated **clean verify 930/930 JUnit, 0 failure/error/skip**, JAR; Chrome **74/74**, 36PNG ở320/390/1440 và no-JS. **73/86 task**, còn13 (45 task mới xong32). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review tích hợp Car/Booking; agent review không thay human review. P8.1–P8.5 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.6; tiếp theo **P8.6 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.2 + P8.3 — 2026-11-03:** hoàn thành **5/5 + 6/6 checklist**: order/QR Admin 15 phút, receipt cấp/gia hạn 30 ngày đúng một lần, hoàn trùng/muộn/hủy/không tương thích từ Admin. Full isolated **clean verify 914/914 JUnit, 0 failure/error/skip**, JAR; Chromium **47/47**, 320/390/1440 và no-JS. **71/86 task**, còn15 (45 task mới xong30). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`; agent review không thay human review. P8.1–P8.3 chưa commit; P6.12/P6.13 đã tích hợp main `6948664` (feature `705f320`). Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.4; tiếp theo **P8.4 + P8.5**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.1 — 2026-11-02:** hoàn thành **4/4 checklist** catalog/schema/mapping; SMALL 4/5.000, STANDARD 8/10.000, BUSINESS 16/15.000 VND, mỗi gói30ngày. Hai bảng mới, snapshot và obligation UNIQUE; không booking giả/tự cấp gói. Full isolated **clean verify 879/879 JUnit, 0 failure/error/skip**, JAR; schema21/21, catalog/JPA2/2. Browser không chạy lại vì đợt này không đổi UI; P6 có bằng chứng109/109 trước đó. **69/86 task**, còn17 (45task mới xong28). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`; có agent review, chưa có human review mới. Bảo toàn thay đổi P6 chưa commit và phase0/config/seed/uploads; DB ứng dụng vẫn7bảng. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Tiếp theo **P8.2 + P8.3**; gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P6.12 + P6.13 — 2026-11-02:** hoàn thành riêng **5/5 + 5/5 checklist**. Timeline Customer/Owner/Admin chỉ đọc, các mốc tiền/vật lý/hoàn độc lập; audit queue/deadline/current actor và gate rental liên tục. Full isolated **clean verify 856/856 JUnit, 0 failure/error/skip**, JAR; Chromium **109/109** trên source cuối, 320/390/1440 và no-JS. **68/86 task**, còn18 (45task mới xong27). Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. Nhánh `feat/phase6-rental-gate` từ main `82a3852`, chưa stage/commit/push. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên. Không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. Dừng trước Phase8; tiếp theo **P8.1 riêng**, phụ trách `ducbinhnguyen07ars`. P4.2-A live vẫn chờ.

**Tích hợp P6.11 — 2026-11-02:** feature `6de7526`, merge main `7aeb8a8`, author `huy69332`; đã push và giữ nhánh `feat/phase6-paid-cancellation`. Source merge khớp source đã clean verify816/816/JAR và browser79/79; sau merge chạy lại32/32 cancellation/UI test trên schema riêng. Phase0 riêng nằm ngoài commit và giữ nguyên. Tiến độ66/86, còn20; tiếp theo P6.12→P6.13, phụ trách `huy69332`, reviewer `thanhmit`. Không migrate DB ứng dụng/bật workflow/provider/scheduler/restart8080 hoặc chuyển tiền thật.

**Tích hợp Git 2026-11-02:** P6.8–P6.10 dùng author `huy69332`, commit tính năng `983e7c9`, merge main `0a1d9e6`; giữ nhánh tính năng. Clean verify mới783/783/JAR; evidence `.scratch/phase6/p610/integration/full-clean-verify.log`. Thay đổi riêng phase-0 nằm ngoài commit và giữ nguyên; không migrate/bật DB ứng dụng/provider/scheduler hoặc chuyển tiền thật. Có agent review, chưa có human contributor review. P6.11 đã hoàn thành trong working tree; tiếp theo P6.12→P6.13.

**Ngày kiểm tra/phân công:** 2026-10-27. **Repo:** [thanhmit/CarRent](https://github.com/thanhmit/CarRent).

**Mục tiêu:** ghi lại đóng góp Git hiện có, phân công bảo trì các module đã triển khai và giao toàn bộ **45 task mới** sau khi thêm P4.2-A ngày 2026-10-29. Đây là phân công trong tài liệu local; chưa tạo issue, gửi thông báo, đổi Git author hoặc cập nhật assignee trên GitHub.

## 1. Kết quả kiểm tra GitHub

Đã đọc trực tiếp GitHub REST API công khai, đối chiếu commit local và remote main. Main trên GitHub là `d03f37b03f389cabd988df7e73585e0d4eddaf0d`, trùng HEAD local tại thời điểm kiểm tra; lần push hiển thị gần nhất là 2026-10-17. Các plan mới đang là thay đổi local, chưa thuộc commit này.

| Thành viên GitHub | Commit GitHub ghi nhận | Bằng chứng hiện có |
|---|---:|---|
| [huy69332](https://github.com/huy69332) | 5 | Commit Phase 2, Phase 3/P4.1, tìm xe theo ngày và hai merge commit |
| [thanhmit](https://github.com/thanhmit) | 2 | Initial commit và commit nền tảng dự án/auth/schema/tài liệu ban đầu |
| [ducbinhnguyen07ars](https://github.com/ducbinhnguyen07ars) | 1 | Tạo PR #2 và merge luồng booking/notification vào main |

Tên author `Huy1442005@` và `huy69332` được GitHub liên kết về cùng tài khoản `huy69332`; `thanhmit9503` và `thanhmit` về cùng tài khoản `thanhmit`. Vì vậy số chuỗi author trong git shortlog có thể lớn hơn ba tài khoản contributor.

Nguồn kiểm chứng:

- [Contributors API](https://api.github.com/repos/thanhmit/CarRent/contributors?per_page=100), [commits API](https://api.github.com/repos/thanhmit/CarRent/commits?per_page=100).
- [Nền tảng — d218caa](https://github.com/thanhmit/CarRent/commit/d218caaaa2ef8061327a45663f5a426af29aa05d).
- [Car management — a880f13](https://github.com/thanhmit/CarRent/commit/a880f1354f89cda184779ecd8c7e7af977cc0089).
- [Booking/notification/P4.1 — aab8035](https://github.com/thanhmit/CarRent/commit/aab8035d5904f1339f04ade40c6fdccad4ff853b).
- [Tìm xe theo ngày — bc3f34f](https://github.com/thanhmit/CarRent/commit/bc3f34f8b849aa0795719604ac02254f31263742).
- [PR #2](https://github.com/thanhmit/CarRent/pull/2), [merge 8e5fc21](https://github.com/thanhmit/CarRent/commit/8e5fc21b59dff325c5a801219369892713acddc5).

Số commit bao gồm merge, không phải tỷ lệ công sức. Git xác nhận author/PR/merge; chưa chứng minh toàn bộ phần trao đổi, pair programming hoặc kiểm thử ngoài lịch sử đó. Không ghi rằng thành viên đã tự viết một module chỉ vì được giao phụ trách nó từ nay.

## 2. Phần đã làm — phân công lại trách nhiệm bảo trì

| Module đã triển khai | Git đang ghi nhận | Người phụ trách từ nay | Công việc bàn giao/bảo trì |
|---|---|---|---|
| Nền Spring Boot, DB/seed, authentication, session/BCrypt/CSRF | `thanhmit`, d218caa | **thanhmit** | Giữ cấu hình bảo mật, bootstrap và nền tảng |
| Car Owner CRUD, ảnh listing, public catalog/tìm xe theo ngày | `huy69332`, a880f13/bc3f34f | **huy69332** | Giữ nghiệp vụ xe, search/availability và tích hợp booking |
| Booking lifecycle, khóa DB và chống đặt trùng | `huy69332`, aab8035/bc3f34f | **huy69332** | Giữ transitions, date/price snapshot và concurrency |
| Payment mapping/repository legacy P4.1 | `huy69332`, aab8035 | **thanhmit** | Tiếp nhận mapping đã có, bảo toàn legacy khi thêm tài chính mới |
| Admin duyệt xe, giao diện quản trị đang có | `huy69332`, a880f13 và các cập nhật sau | **ducbinhnguyen07ars** | Tiếp nhận luồng duyệt; nối quota/gói và dashboard Admin |
| Notification/chuông/lịch sử thông báo hiện có | `huy69332`, aab8035 | **ducbinhnguyen07ars** | Giữ UI/query thông báo; hỗ trợ hai module tiền và thuê thêm loại thông báo đúng recipient |
| Tích hợp PR và rà tài liệu toàn hệ thống | `ducbinhnguyen07ars` tạo/merge PR #2; tài liệu có commit của `thanhmit`/`huy69332` | **ducbinhnguyen07ars** | Điều phối checklist tích hợp, liên kết plan và E2E cuối |

“Phụ trách từ nay” là quyền sở hữu công việc bảo trì/review được phân lại, không thay đổi tác giả hoặc người thực hiện lịch sử. Khi bàn giao module, người phụ trách mới đọc luồng hiện có và tái hiện smoke test trước khi sửa.

## 3. Phần sắp làm — giao 45 task mới sau tách P4.2-A

**Checkpoint lịch sử P6.11 — 2026-11-02:** đủ **5/5 checklist**: Customer/Owner hủy trước handover, snapshot thời gian/phí/lý do và nghĩa vụ hoàn atomic; receipt gốc giữ tiền đã nhận. Full isolated clean verify **816/816 JUnit, 0 failure/error/skip**, JAR; Chromium **79/79**,25PNG,7/7SQL invariant. **66/86 task**, còn20 (45task mới xong25). Tiếp theo **P6.12 → P6.13** cùng đợt. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. P6.11 đã tích hợp main; xem checkpoint Git phía trên. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên; không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. P4.2-A live vẫn chờ.

Trong45task mới: xong42,còn3: `thanhmit`1(P4.2-A), `huy69332`0, `ducbinhnguyen07ars`2(P8.8/P5.9). P5.1–P5.8 hoàn tất local; P5.9 đạt5/6 checklist nhưng gate live chưa đóng. Checkpoint bên dưới giữ lịch sử.

**Checkpoint P6.7 trước tích hợp Git:** P6.7 hoàn thành đủ **5/5 checklist**: Owner xác nhận hợp đồng và giao xe atomic, kiểm tra BEFORE/đủ30%/ngày Việt Nam/custody; UI Đang thuê và chặn hủy sau giao xe. Full isolated clean verify **683/683 JUnit test, 0 failure/error/skip** (638 hồi quy + RentalHandoverTest45), JAR đóng gói; **32/32 kiểm tra Chromium** desktop/mobile/no-JS đạt. DB ứng dụng vẫn7bảng, fingerprint schema/toàn bộ rows và file phase-0 riêng giữ nguyên. Không migration DB ứng dụng, bật workflow/provider/scheduler, restart8080, commit/push, tiền thật hoặc human review mới. **62/86 task**, còn24; tiếp theo **P6.8 — Customer gửi AFTER và nhắc Owner**. P4.2-A tiếp tục hoãn; gate rental mặc định tắt. Trong45task mới: xong21, còn24; thanhmit cònP4.2-A, huy69332 còn8 (P6.8–P6.13/P5.5–P5.6), ducbinhnguyen07ars còn15.

**Tích hợp P6.4–P6.7 — 2026-11-01:** theo yêu cầu commit/push/merge của chủ dự án, author Git dùng danh tính đã liên kết `huy69332 <174874768+huy69332@users.noreply.github.com>`, đúng phân công bốn task. Commit `4a6af85`, merge main `66c2d8b`; thao tác do Codex thực hiện, không suy ra thành viên đã tự viết/review toàn bộ thay đổi. Có agent review trước tích hợp, chưa có human contributor review mới. Thay đổi riêng phase0 nằm ngoài commit; snapshot lúc tích hợp giữ62/86. Đợt P6.8/P6.9 sau đó đã đóng; hiện tiếp tục P6.11 từ working tree theo handoff mới.

**Checkpoint 2026-10-27:** Phase 7/P7.1–P7.4 đã triển khai và kiểm chứng (19 lõi queue + 34 hồi quy + 41 HTTP queue + 9 transaction = 103 test đạt); `thanhmit` tiếp theo làm P4.2, còn P4.2–P4.11. Phân công reviewer `huy69332` giữ nguyên; chưa ghi nhận review của contributor khác. Tổng task mới còn lại: 40. Chi tiết tại [plan Phase 7](phase-7-car-queue.md). Nhánh triển khai P7.3: `feat/car-queue-http`; P7.4: `feat/car-queue-transaction`.

Mỗi người làm đầy đủ backend, UI liên quan, kiểm chứng và tài liệu của mảng mình; không dồn toàn bộ kiểm thử cho một thành viên.

**Cập nhật P4.2 cùng ngày:** `thanhmit` đã triển khai cấu hình/verifier SePay và adapter payOS offline. Chủ dự án đã chốt payOS cho API chi hộ, nguồn tiền ví Bảo Kim của đúng Owner/Admin. Còn xác minh điều kiện provider và gate live. `huy69332` tiếp tục là reviewer được phân công, chưa ghi nhận human review. P4.2 chưa hoàn tất nên tổng còn 40 task. Nhánh: `feat/payment-provider-adapters`; xem [checkpoint Phase 4](phase-4-payment.md).

| Thành viên | Mảng chính | Task chịu trách nhiệm | Số task mới |
|---|---|---|---:|
| **thanhmit** | Nền tảng concurrency và tài chính | **P7.1–P7.4**, **P4.2–P4.11**, **P4.2-A** | **15** |
| **huy69332** | Luồng thuê, biên bản/hợp đồng, profile | **P6.1–P6.13**, **P5.5–P5.6** | **15** |
| **ducbinhnguyen07ars** | Gói Owner, quản trị, tích hợp cuối | **P8.1–P8.8**, **P5.1–P5.4**, **P5.7–P5.9** | **15** |
| **Tổng** | | | **45** |

Task không có độ khó bằng nhau: mảng thanh toán phải kiểm chứng provider, idempotency, payout và phục hồi sau crash. Số lượng gần nhau là cách theo dõi phạm vi, không phải cam kết công sức chính xác.

**Lịch thực thi được chủ dự án duyệt 2026-11-01:** giữ phân công45task và checklist. Phase6 đã đóng P6.1–P6.13, P8.1–P8.7 và P5.1–P5.8 đã đóng local; **83/86**, còn3. P5.9 local đã kiểm chứng, còn gate live cùng P8.8/P4.2-A. Giữ thứ tự/phân công đã duyệt, không suy human review hoặc cho phép kích hoạt từ agent review/fixture.

### 3.1. thanhmit — Queue và thanh toán thật

- Queue FIFO theo carId, giới hạn và transaction boundary; nối entry point hiện có.
- Payment core: schema/mapping, nghĩa vụ/lần QR, SePay verifier/inbox, receipt idempotent, polling.
- API payOS (nguồn tiền ví Bảo Kim), recipient, payout attempt, retry/reconcile và các cảnh báo hoàn.
- Kiểm chứng kênh Owner/Admin thật; ghi trung thực gate chưa được nhà cung cấp kích hoạt.
- **P4.2-A:** thực hiện cấu hình tài khoản/provider/live sau đăng ký/kích hoạt đủ tài khoản và khi inbox/worker cần kiểm chứng đã có; không gộp kết quả offline P4.2 thành live pass.
- Giữ vai trò điều phối schema/migration/bootstrap để ba nhánh không viết chồng `scriptDB.sql`.
- **Reviewer chính:** huy69332, tập trung khóa DB, race, tiền và nguồn chi đúng chủ thể.

### 3.2. huy69332 — Thuê xe và profile

- BEFORE/AFTER, ảnh private và quyền truy cập; Customer đồng ý BEFORE rồi trả trước 30%.
- Checkbox hợp đồng Owner và xác nhận giao xe; chặn hủy từ handover.
- Trả xe độc lập bất đồng; khoản 70%, các hạn 1 giờ và tự hoàn thành khi đủ điều kiện.
- Policy hủy theo ngày lịch, phí/hoàn và webhook chậm không giành xe của B.
- Profile/đổi mật khẩu P5.5–P5.6, giữ policy auth đã có.
- **Reviewer chính:** thanhmit, tập trung ghép financial handler, clock, quyền và bảo toàn lịch sử.

### 3.3. ducbinhnguyen07ars — Gói Owner và Admin

- Catalog gói 30 ngày, order snapshot, nhận phí qua SePay vào Admin và hoàn trùng/late từ Admin.
- Quota, expiry/public visibility, gia hạn và chọn xe bật lại khi mua gói thấp hơn.
- Dashboard đúng nguồn doanh thu, Admin users/booking/finance, Owner dashboard.
- Chủ trì ma trận quyền và E2E cuối P5.8–P5.9; thanhmit/huy69332 cùng cung cấp test/fixture/bằng chứng cho mảng của mình.
- **Reviewer chính:** thanhmit; thay đổi quota/availability trong CarService/BookingService cần huy69332 review thêm phần tích hợp.

## 4. Thứ tự gate và việc có thể chuẩn bị song song

**Phân biệt lịch:** bảng dưới là các giai đoạn phối hợp ba thành viên, không phải đơn vị triển khai của mỗi chat. Lịch đợt chi tiết hiện hành là quyết định2026-11-01 ở mục3 và từng phase; không tự gộp thêm task từ gợi ý chuẩn bị song song.

**Quy tắc subagent được chủ dự án duyệt 2026-11-01:** Được chủ động dùng subagent cho công việc độc lập và review. Agent chính chịu trách nhiệm phân công, tránh sửa chồng file, tích hợp và kiểm chứng kết quả cuối. Phân công agent không thay người phụ trách/reviewer GitHub; ghi rõ agent review và human contributor review theo bằng chứng thực tế. Quy tắc này thay giới hạn cũ chỉ dùng subagent khi được yêu cầu.

Giữ thứ tự tích hợp/đạt gate **7 → 4 → 6 → 8 → 5** tại [roadmap](README.md). Việc chuẩn bị nhánh/test/UI hoặc task độc lập không được coi là đã hoàn thành một phase còn thiếu phụ thuộc.

| Đợt | thanhmit | huy69332 | ducbinhnguyen07ars |
|---|---|---|---|
| 1 — Chốt contract, Queue | Làm Phase 7; khảo sát điều kiện provider P4.2 | Review Queue; chuẩn bị test policy rental và P5.5–P5.6 độc lập | Đọc Admin/notification hiện có; chuẩn bị catalog, UI gói và P5.3 độc lập |
| 2 — Nền thanh toán | Phase 4 sau gate Queue | Chuẩn bị migration/ảnh/policy Phase 6 trên nhánh riêng, dùng contract đã chốt | Chuẩn bị model/UI/test gói, read DTO Admin; chưa apply receipt hoặc entitlement thật |
| 3 — Rental tích hợp | Hỗ trợ/review adapter tiền, sửa lỗi core thuộc mảng mình | Tích hợp và chốt Phase 6 | Hoàn thiện nhánh Phase 8 theo interface đã ổn định; chưa bật public quota gate |
| 4 — Gói tích hợp | Review nguồn thu/hoàn Admin và khóa quota | Review Car/Booking integration, hồi quy rental | Tích hợp/chốt Phase 8, nối dashboard vào dữ liệu thật |
| 5 — Admin/E2E | Kiểm chứng webhook/payout/restart, review finance | Kiểm chứng nhận/trả/hủy/quyền ảnh, hoàn tất profile | Chốt P5.1–P5.4/P5.7–P5.9, tổng hợp kết quả cả nhóm |

Không mở checkout live sớm hoặc đổi dependency để chạy cho xong nhánh. Gate bật tiền thật vẫn đòi một Owner + Admin có đủ kênh hợp lệ, đủ handler và kiểm chứng end-to-end.

## 5. File giao nhau và cách phối hợp

| File/module | Người điều phối thay đổi | Quy tắc |
|---|---|---|
| `concurrency/**`, `payment/**`, `SecurityConfig`, handler lỗi chung | thanhmit | PR thay đổi contract được huy69332 review; các nhánh khác gọi service đã chốt |
| `BookingService`, `BookingRepository`, `CarService`, public search/availability | huy69332 | ducbinhnguyen07ars thêm quota qua SubscriptionPolicy; thống nhất adapter và patch nhỏ, tránh cùng rewrite cả file |
| `inspection/**`, ảnh private, booking UI | huy69332 | thanhmit review các guard có payment/refund và IDOR |
| `subscription/**`, `admin/**`, Admin/Owner dashboard | ducbinhnguyen07ars | thanhmit review ledger/query; huy69332 review quota/visibility ảnh hưởng rental |
| `user/**` | thanhmit điều phối security; huy69332 làm profile | Form không tự đổi role/status/email; không tạo hai UserService trùng trách nhiệm |
| Migration `-01-payment`, `-02-rental`, `-03-owner-subscriptions` | Từng chủ module viết; thanhmit điều phối thứ tự | Migration mới, không sửa migration đã apply; một người tích hợp `scriptDB.sql` ở mỗi đợt |
| `docs/plans`, SDD, decisions | Chủ module cập nhật; ducbinhnguyen07ars tổng hợp | Không đổi author lịch sử; giữ trạng thái thiết kế/fixture/live rõ ràng |

Không dùng một nhánh chung cho cả ba người. Gợi ý nhánh khi bắt đầu thực hiện: `feat/phase7-car-queue`, `feat/phase4-real-payments`, `feat/phase6-rental-workflow`, `feat/phase8-owner-subscriptions`, `feat/phase5-profile`, `feat/phase5-admin-finance`. Chưa tạo các nhánh này trong lần phân công; giữ các nhánh GitHub đang có.

## 6. Điều kiện bàn giao mỗi task

1. Người phụ trách đọc đúng spec/task và thống nhất interface với module tiêu thụ.
2. Hoàn thành code, UI cần thiết, test có ý nghĩa và ghi bằng chứng thật trong plan của mình.
3. Reviewer kiểm tra business rule, bảo mật, tiền và phần giao nhau; người viết xử lý finding.
4. Chỉ tick sau kiểm chứng tích hợp phù hợp. Người điều phối không tick hộ dựa trên việc PR đã được tạo.
5. Mỗi thành viên dùng danh tính Git/email đã liên kết đúng tài khoản của mình cho phần thực sự thực hiện; merge commit không được báo cáo là toàn bộ code của PR do người merge viết.

**Giới hạn:** phân công dựa trên lịch sử repo và roadmap đã chốt, chưa có thông tin lịch rảnh/chuyên môn tự khai của từng người. Có thể đổi owner khi nhóm thống nhất; phải cập nhật cả file này và trường Phụ trách trong từng task, không đổi lịch sử commit để làm đều thống kê.
