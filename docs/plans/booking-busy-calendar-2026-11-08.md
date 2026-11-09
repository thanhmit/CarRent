# Lịch bận tại chi tiết xe — kế hoạch triển khai 30 phút

> **For agentic workers:** Use `superpowers:executing-plans` inline, không chia phiên/sub-agent. Implementation và kiểm chứng local đã hoàn tất; xem handoff đi kèm.

**Goal:** Tại `/cars/{id}`, Customer xem lịch tháng, nhận biết ngày bận được làm mờ và không chọn được khoảng thuê giao với đơn đang giữ lịch.

**Architecture:** `BookingRepository` → `CarService` → model `CarController.detail` → widget JavaScript trong khung đặt xe. Chọn ngày hợp lệ cập nhật link sang form booking. Server tiếp tục kiểm tra xung đột dưới khóa khi tạo đơn.

**Tech stack:** Spring MVC/JPA/MySQL, Thymeleaf, JavaScript thuần, Tailwind hiện có, Node test và JUnit/MockMvc.

**Spec:** Yêu cầu mới nhất ngày 2026-11-08: tối đa 30 phút, ưu tiên lịch khóa/làm mờ ngày bận ngay tại chi tiết từng xe. Kế hoạch này thay thế bản 2,5–3,5 giờ trước đó.

**Status:** IMPLEMENTED / VERIFIED LOCAL — Java 40/40, Node 18/18, Chrome 18 assertion. Source đã đưa về root; chưa restart/deploy app 8080. Xem [bàn giao](booking-busy-calendar-2026-11-08-handoff.md).

## Phạm vi để nhắm 30 phút

- Đặt widget trong aside giá/Đặt thuê xe; giữ bố cục trang.
- Hiển thị một tháng, chuyển tháng trước/sau và chọn ngày nhận/ngày trả. Ngày bận giữ ô/số ngày màu xám, có chú thích và bị khóa khi không hợp lệ; không xóa ô làm lịch lệch thứ.
- Tận dụng `CarService` đã có `BookingRepository`, không thêm dependency controller/service không cần thiết.
- Dữ liệu khoảng bận render cùng HTML, chỉ gồm ngày bắt đầu/kết thúc. Không thêm API, migration, thư viện datepicker hoặc polling.
- Khoảng đã chọn ở trang tìm kiếm được điền sẵn và kiểm tra lại. Khoảng hợp lệ truyền sang `/bookings/new?carId=…&startDate=…&endDate=…`.
- Đợt 30 phút tập trung lịch tháng tại **chi tiết xe**. Form booking giữ input/tính giá và kiểm tra server hiện có; không nhân đôi lịch tháng ở form trong đợt này.
- Không thay luồng BEFORE, Payment Settings, provider, trạng thái booking hoặc quy tắc search.

## Quy tắc bắt buộc

1. PENDING/CONFIRMED giữ lịch; CANCELLED/REJECTED/COMPLETED/EXPIRED không giữ lịch. GET không tự hết hạn hoặc giải phóng booking.
2. Khoảng thuê là `[startDate,endDate)`, cả LEGACY và VIETQR_V1. Trùng khi `start < busyEnd && end > busyStart`.
3. Xe bận 10→12: ngày nhận 10/11 bị khóa, nhận ngày 12 được phép nếu không có đơn khác. Trả ngày 10 cũng được phép nếu khoảng trước đó trống.
4. Ngày trả xét toàn khoảng: 09→13 vẫn bị chặn dù hai đầu nằm ngoài khoảng bận 10→12. Không khóa nhầm ngày trả chỉ vì đó là ngày bắt đầu của đơn tiếp theo.
5. Khóa quá khứ và ngày trả không sau ngày nhận. Button HTML chuẩn có tên truy cập đầy đủ, dùng Tab/Enter; không tự xây ARIA grid.
6. Chỉ đưa lịch vào model sau khi kiểm tra quyền xem xe; preview Owner/Admin không hiện widget đặt xe. Không lộ booking ID/customer/ghi chú/thanh toán.
7. Lịch là snapshot, chưa giữ chỗ. Có booking mới sau khi mở trang thì server từ chối khi POST; không khẳng định chắc chắn xe còn trống.
8. JS tắt/lỗi: thông báo lịch tương tác chưa sẵn sàng; link đặt xe cũ và kiểm tra server còn hoạt động.

## File cần chạm

| File | Công việc |
|---|---|
| `src/main/java/com/carrent/booking/BookingDateRange.java` | Record hai LocalDate startDate/endDate |
| `src/main/java/com/carrent/booking/BookingRepository.java` | Query projection đúng xe, PENDING/CONFIRMED, endDate > today, sort start/end |
| `src/main/java/com/carrent/car/CarService.java` | Method read-only busyDateRanges(Long carId, LocalDate today), dùng bookings sẵn có |
| `src/main/java/com/carrent/car/CarController.java` | Thêm today/busyDateRanges vào model detail sau getPublicCar, cho public listing |
| `src/main/resources/templates/cars/detail.html` | Widget tháng và dữ liệu ISO qua Thymeleaf attributes |
| `src/main/resources/static/js/car-availability.js` | Render, disable ngày, chọn khoảng và cập nhật link |
| `src/test/js/car-availability.test.cjs` | Node/VM test khoảng và tương tác lịch/link |
| `src/test/java/com/carrent/car/CarDateSearchIntegrationTest.java` | Query/model/status và regression detail → booking |

## Một phiên 30 phút

### Phút 0–3: baseline và test nhỏ

- [x] Lưu diff/hash file nhiệm vụ để bảo toàn dirty worktree. Xác nhận fixture datasource riêng sẵn sàng; không chạy test mặc định vào schema ứng dụng.
- [x] Thêm test model lịch bận và phép giao khoảng trước khi nối UI; chạy để ghi nhận hành vi còn thiếu.

### Phút 3–8: dữ liệu lịch

- [x] Thêm record/query projection đọc không khóa, không serialize entity.
- [x] Thêm read method ở CarService. Controller lấy LocalDate.now() một lần, bổ sung model sau kiểm tra quyền/listing.

### Phút 8–21: widget chi tiết xe

- [x] Render khoảng bận thành data-start/data-end bằng Thymeleaf và lịch tháng tại aside hiện có.
- [x] Render theo UTC/date-only, chuyển tháng/năm đúng; không giới hạn tùy tiện số tháng tương lai.
- [x] Dùng chung phép giao khoảng cho ngày trả và khoảng từ search:

```javascript
const overlaps = (start, end, busyStart, busyEnd) =>
  start < busyEnd && end > busyStart;
```

- [x] Khóa ngày nhận bận; ngày trả chỉ enable nếu sau ngày nhận và toàn khoảng không giao booking khác. Highlight khoảng chọn và có chữ chú thích ngày bận.
- [x] Khoảng điền từ search bị bận hiển thị lỗi ngay. Link đặt xe không dẫn tiếp với cặp ngày sai: chặn activation/click thật, không chỉ giảm opacity/aria-disabled.
- [x] Khoảng hợp lệ cập nhật ngày hiển thị và link booking. Giữ chính sách role/anonymous và kiểm tra server hiện có.

### Phút 21–28: kiểm chứng tập trung

- [x] Node test bận 10→12: 09→10=false; 12→13=false; 10→11=true; 11→12=true; 09→13=true; thêm hai đơn sát nhau và chuyển tháng/năm.
- [x] Test DOM/VM khẳng định button bận disabled, ngày trả sát biên được chọn và link chứa đúng hai ngày.
- [x] Chạy `node --test src/test/js/car-availability.test.cjs`.
- [x] Chạy `./mvnw.cmd '-Dtest=CarDateSearchIntegrationTest' test` sau khi cấu hình/xác nhận datasource test riêng. Test detail khoảng dòng 193 hiện khẳng định không có availability-start/availability-end: cập nhật assertion theo UI mới nếu dùng các ID này; giữ kiểm tra truyền ngày và POST stale-page rejection.
- [x] Smoke desktop/mobile bằng fixture/runtime riêng: lịch, đổi tháng, ngày bận, xuyên khoảng bận, sát biên và mở form đúng ngày. Không sửa booking thật.

### Phút 28–30: kiểm diff và bàn giao

- [x] Kiểm diff chỉ đúng nhiệm vụ, không secrets/schema/provider thay đổi; lưu test và ảnh smoke.
- [x] Báo đúng kiểm tra đã chạy, lỗi còn lại nếu có. Không tự commit/push/restart ứng dụng 8080.

## Điều kiện hoàn thành

- [x] Chi tiết xe có lịch tháng làm mờ/khóa ngày bận; chọn ngày trả xét toàn khoảng.
- [x] Từ search hoặc mở detail trực tiếp đều chọn được khoảng mới và truyền đúng ngày sang booking.
- [x] Status/xe khác không khóa nhầm; dữ liệu người đặt không xuất hiện trong HTML.
- [x] Test tập trung và smoke desktop/mobile đạt; kiểm tra server chống trùng vẫn hoạt động.

## Thời gian và giới hạn

**30 phút là timebox mục tiêu, chưa phải thời gian đã đo hoặc cam kết bỏ qua kiểm chứng.** Mốc này dựa trên build/fixture/browser hiện có sẵn sàng. Nếu phút 30 còn vướng môi trường hoặc lỗi, báo phần hoàn thành/chưa hoàn thành; không tự sửa dữ liệu thật để chạy kịp.

Không chạy full regression toàn repo, không viết lại test concurrency vì lock/server không đổi; giữ test stale-page hiện có. Không realtime refresh hoặc xử lý trả xe muộn ngoài ngày hợp đồng. Phân biệt implementation/test riêng với triển khai lên app 8080.
