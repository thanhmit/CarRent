# P8.4 + P8.5 — bàn giao đợt3

## 1. Checkpoint và baseline

**Checkpoint P8.4 + P8.5 — 2026-11-04:** hoàn thành **6/6 + 6/6 checklist**: quota theo Owner, current reads chống vượt suất, public predicate trực tiếp theo hạn gói và POST booking kiểm tra lại. Full isolated **clean verify 930/930 JUnit, 0 failure/error/skip**, JAR; Chrome **74/74**, 36PNG ở320/390/1440 và no-JS. **73/86 task**, còn13 (45 task mới xong32). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review tích hợp Car/Booking; agent review không thay human review. P8.1–P8.5 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.6; tiếp theo **P8.6 riêng**, gate live vẫn chờ.

- Repo `C:\Users\Admin\Documents\hoc\Car`; branch `feat/phase6-rental-gate`.
- HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`. Không stage/commit/push. P8.1–P8.5 là working tree chưa commit; phase0 là thay đổi riêng phải bảo toàn.
- Spec: [Phase8](phase-8-owner-subscriptions.md), [SDD §14.41](../SDD.md#1441-p84--p85--quota-và-quyền-publicbooking-2026-11-04).

## 2. Hành vi đã hoàn thành

- P8.4: reserved ACTIVE+PENDING/APPROVED; Owner→entitlement→Car; current COUNT; tạo/gửi lại/bật lại kiểm quota; edit không cộng suất; hide/reject trả suất; hidden approve giữ INACTIVE. REJECTED gửi lại kiểm nội dung và về PENDING; flagoff giữ legacy.
- P8.5: public spec trực tiếp theo Clock, áp dụng home/search/detail/count/filter/pagination/booking UI; POST recheck Owner/hạn gói; expiry không sửa listing intent hay booking. Rental/inspection/payment/reminder hiện có chỉ thêm lock-only Owner trước Car để tránh đảo khóa, không bắt gói hợp lệ.
- Gia hạn cùng gói phục hồi đúng ACTIVE; Owner chủ động ẩn vẫn ẩn. Chưa triển khai kịch bản hạ gói16→4/P8.6 hoặc dashboard P5.7.

## 3. Bằng chứng và giới hạn

- `.scratch/phase8/p84-p85/targeted-green.log`:16/16. `full-clean-verify.log`:930/930,0failure/error/skip,JAR. Runner Java FullSuite tạo schema MySQL ngẫu nhiên, truyền datasource vào Maven rồi xóa schema; verification-pom giữ dependency/source của project và đưa output vào scratch. Lệnh `java '@.scratch/phase8/p84-p85/full-suite.args'` chạy `mvnw.cmd -f .../verification-pom.xml -o -Dmaven.compiler.useIncrementalCompilation=false -Dtest=*Test clean verify`.
- Browser: `browser/browser-evidence.json`, manifest, PNG và `browser-sql-invariants.txt`; actual HTTP/CSRF/session, không mock business endpoints. SePay inbox ký offline, visual CDN assets cache local, không có provider live/chuyển tiền thật. Các lỗi selector/navigation/classpath harness ban đầu đã sửa, không dùng kết quả stale làm gate cuối.
- No-JS xác minh form/CSRF/quota/resubmit/approve native hoạt động khi tắt JavaScript. CSS Tailwind CDN hiện cần JavaScript; ảnh no-JS không được coi là bằng chứng responsive styling. Không đổi hệ thống CSS trong đợt quota/public.
- MySQL regression hai carId tranh suất cuối với snapshot cũ; rollback/khác Owner; NOWAIT chứng minh rental chờ Owner trước Car. Hai case missing/expired gói vẫn hoàn thành toàn rental, report/images/events và debt đúng.
- `baseline.json`, `verified-source-hashes.json`, `verification-summary.json`, fingerprints DB ứng dụng7bảng và protected files. Agent review đã xử lý blocker; chưa có human contributor review.

## 4. Đợt tiếp theo — P8.6 riêng

- Phụ trách `ducbinhnguyen07ars`; reviewer `thanhmit`, `huy69332` review tích hợp Car/Booking. Tiến độ73/86,còn13: `ducbinhnguyen07ars`10, `huy69332`2, `thanhmit`1.
- Đọc plan P8.6: gia hạn cùng loại không bật xe INACTIVE; sau expiry mua gói thấp hơn, giữ dữ liệu và đưa ý định các xe cũ về INACTIVE trong transaction apply; Owner tự bật tối đaquota, không tự chọn xe và không reset APPROVED.
- Gate chính16Business→expiry→Small: public/reserved ban đầu0, bật4được, thứ5fail,16xe và booking cũ còn nguyên. Giữ Owner→entitlement→order→Car ID tăng→financial; current reads sau chờ mutex và lock-only trên rental cũ.
- Dừng trước P8.7. Không migrate/reset/seed DB ứng dụng, bật workflow/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push. Không reset/checkout làm mất P8 dirty hoặc phase0. Gate live P4.2-A/P8.8 vẫn chờ.
