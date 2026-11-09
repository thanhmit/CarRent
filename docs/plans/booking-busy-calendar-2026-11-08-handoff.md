# Bàn giao lịch bận tại chi tiết xe — 2026-11-08

Đã triển khai lịch tháng tại `/cars/{id}`: ngày bận làm mờ/khóa, chọn ngày nhận/trả và truyền khoảng hợp lệ sang form đặt xe. Dữ liệu lấy từ booking PENDING/CONFIRMED của đúng xe, chỉ chiếu hai trường ngày. Không thêm bảng, API, dependency hoặc thay đổi lifecycle/khóa chống trùng.

## Hành vi

- Khoảng thuê `[startDate,endDate)`, đúng server: nhận xe tại ngày đơn trước kết thúc và trả xe tại ngày đơn sau bắt đầu vẫn hợp lệ.
- Chọn ngày trả kiểm tra toàn khoảng; hai đầu rảnh nhưng xuyên ngày bận vẫn bị chặn. Ngày từ search hoặc nhập tay cũng được kiểm tra.
- Khoảng không hợp lệ bỏ href và chặn click của link đặt xe; chọn lại khoảng hợp lệ khôi phục link và hai tham số ngày.
- Chuyển tháng/năm, tháng nhuận, bàn phím Tab/Enter; hỗ trợ desktop/mobile. Không hiển thị widget cho preview xe chưa public.
- Khi JS tắt, ẩn widget chưa khởi tạo, giữ link cũ và thông báo kiểm tra tại server. Không hiển thị bản tóm tắt ngày cũ bên dưới lịch khi JS đã chạy.
- Form `/bookings/new` giữ input/tính giá hiện có, không thêm lịch tháng thứ hai trong scope 30 phút.

## Kiểm chứng đã chạy

| Kiểm tra | Kết quả cuối |
|---|---|
| `CarDateSearchIntegrationTest` trên MySQL schema riêng | 40/40, 0 fail/error/skip |
| `node --test src/test/js/*.test.cjs` ở worktree và root sau copy | 18/18, gồm 10 test lịch mới |
| Chrome headless desktop/390px/320px | 18 assertion; khóa ngày, sát biên, xuyên khoảng bận, đổi tháng, login rồi mở form đúng ngày, no-JS fallback, không overflow |
| Kiểm tra ảnh | Đã xem ảnh desktop và mobile; sửa CSS flex ghi đè hidden trước lần kiểm chứng cuối |
| `git diff --check` của các file sửa | Đạt |

Test mới được chạy trước implementation: Node 10 test fail; JUnit 6 trường hợp fail do model chưa có `busyDateRanges`. Lượt tích hợp đầu sau triển khai có 1 test cũ không còn phù hợp vì yêu cầu detail không có input ngày; đã cập nhật để kiểm tra public detail có lịch và owner preview không có widget. Các lượt fail ban đầu được giữ riêng với kết quả pass cuối.

Browser lần đầu dùng xe seed INACTIVE nên trả 404; đã kích hoạt xe trong schema fixture riêng. Không sửa trạng thái xe hoặc booking của schema ứng dụng.

## Artifact và nguồn

- Worktree giữ tại `.scratch/booking-busy-calendar-20261108/worktree`, branch `feat/car-busy-calendar`.
- Baseline nguồn hiện tại có manifest, bao gồm thay đổi local trước task; worktree không phải một branch sạch chỉ chứa task này. Không commit toàn bộ worktree.
- `.scratch/booking-busy-calendar-20261108/`: `java-red.log`, `node-red.log`, `java-verified.log`, `node-verified.log`, `root-node-verified.log`, `browser-evidence.json`, ảnh desktop/mobile, `local-publication.json`, `cleanup.json`.
- 8 file nhiệm vụ đã copy về checkout root sau khi kiểm tra hash baseline chống ghi đè sửa đồng thời; index và cấu hình riêng không đổi. Hash source root khớp source đã kiểm chứng.
- Runtime riêng 18094 đã dừng, schema fixture đã xóa. App 8080 vẫn PID 480. Không commit/push/merge, không sửa cấu hình, không provider calls hoặc ghi DB ứng dụng.

## Giới hạn

Chưa restart/deploy ứng dụng 8080, nên instance đang chạy cần nạp lại code để có UI mới. Không chạy full regression toàn repo; đã chạy toàn suite date-search và toàn bộ Node tests. Lịch là snapshot, server vẫn quyết định khi gửi booking; không realtime refresh và không xử lý xe trả muộn vượt ngày hợp đồng trong task này.
