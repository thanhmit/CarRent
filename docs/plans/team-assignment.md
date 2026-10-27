# CarRent — Phân công ba thành viên

**Ngày kiểm tra/phân công:** 2026-10-27. **Repo:** [thanhmit/CarRent](https://github.com/thanhmit/CarRent).

**Mục tiêu:** ghi lại đóng góp Git hiện có, phân công bảo trì các module đã triển khai và giao toàn bộ 44 task mới cho ba thành viên. Đây là phân công trong tài liệu local; chưa tạo issue, gửi thông báo, đổi Git author hoặc cập nhật assignee trên GitHub.

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

## 3. Phần sắp làm — giao 44 task mới

**Checkpoint 2026-10-27:** P7.1–P7.3 đã triển khai và kiểm chứng (19 lõi queue + 34 hồi quy + 41 HTTP queue = 94 test đạt); `thanhmit` tiếp theo làm P7.4, sau gate Phase 7 là P4.2–P4.11. Phân công reviewer `huy69332` giữ nguyên; chưa ghi nhận review của contributor khác. Tổng task mới còn lại: 41. Chi tiết tại [plan Phase 7](phase-7-car-queue.md). Nhánh triển khai P7.3: `feat/car-queue-http`.

Mỗi người làm đầy đủ backend, UI liên quan, kiểm chứng và tài liệu của mảng mình; không dồn toàn bộ kiểm thử cho một thành viên.

| Thành viên | Mảng chính | Task chịu trách nhiệm | Số task mới |
|---|---|---|---:|
| **thanhmit** | Nền tảng concurrency và tài chính | **P7.1–P7.4**, **P4.2–P4.11** | **14** |
| **huy69332** | Luồng thuê, biên bản/hợp đồng, profile | **P6.1–P6.13**, **P5.5–P5.6** | **15** |
| **ducbinhnguyen07ars** | Gói Owner, quản trị, tích hợp cuối | **P8.1–P8.8**, **P5.1–P5.4**, **P5.7–P5.9** | **15** |
| **Tổng** | | | **44** |

Task không có độ khó bằng nhau: mảng thanh toán phải kiểm chứng provider, idempotency, payout và phục hồi sau crash. Số lượng gần nhau là cách theo dõi phạm vi, không phải cam kết công sức chính xác.

### 3.1. thanhmit — Queue và thanh toán thật

- Queue FIFO theo carId, giới hạn và transaction boundary; nối entry point hiện có.
- Payment core: schema/mapping, nghĩa vụ/lần QR, SePay verifier/inbox, receipt idempotent, polling.
- payOS/Bảo Kim, recipient, payout attempt, retry/reconcile và các cảnh báo hoàn.
- Kiểm chứng kênh Owner/Admin thật; ghi trung thực gate chưa được nhà cung cấp kích hoạt.
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
