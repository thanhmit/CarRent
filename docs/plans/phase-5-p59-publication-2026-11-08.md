# P5.9 local: phạm vi Git ngày 2026-11-08

## Phạm vi và trách nhiệm

- Chủ dự án cho phép commit, push và merge vào `main` phần task của thành viên đang có credential GitHub; giữ nguyên nhánh tính năng.
- Đã xác minh qua Git Credential Manager và GitHub API: tài khoản `ducbinhnguyen07ars`, có quyền push vào `thanhmit/CarRent`. Git author dùng danh tính hiện có của cùng thành viên. Thao tác do Codex thực hiện theo ủy quyền, không thay human contributor review.
- Nhánh `feat/p59-local-integration` tách từ `origin/main` tại `1862aa9`. P5.7/P5.8 đã ở main, không commit lại.
- Giữ riêng P5.5/P5.6 của `huy69332`, thay đổi SePay/payOS/setup của `thanhmit`, UI-01/UI-02, cấu hình private, phase-0 và upload bị xóa. Không stage toàn bộ checkout gốc.

## Thay đổi được đưa lên

1. `BookingService` và `PaymentPilot`: admission VIETQR_V1 do server quyết định, mặc định tắt, đúng cặp pilot và đủ hai handler advance/balance; xác minh Owner/Admin hiện tại. Đọc lại Customer sau khi chờ khóa Car cho cả VIETQR_V1 và LEGACY.
2. `FinalRentalJourneyTest`: hành trình HTTP thật từ mua gói/đăng và duyệt xe đến BEFORE/30%/hợp đồng/giao xe/AFTER bất đồng/nhận lại/70%/COMPLETED; guard cấu hình, giả mạo field và bốn trường hợp pilot/LEGACY với Customer bị ban/đổi role khi chờ khóa.
3. `SubscriptionService`: chuẩn hóa thời điểm tạo order xuống microsecond để round-trip MySQL DATETIME(6), giữ equality giữa hạn order và nghĩa vụ thanh toán.
4. `SubscriptionOrderTest`, `SubscriptionPaymentTest`, `SubscriptionOwnerUiTest`: ba gói tạo/hủy được với clock có nanosecond; sai lệch deadline một microsecond vẫn bị chặn; HTTP mua gói → webhook offline → đăng/duyệt/ẩn xe.

Hai file P5.9 lấy từ worktree tích hợp đã có tại `.scratch/completion-20261105/worktree` để giữ hardening LEGACY, các file còn lại lấy từ snapshot checkout gốc đầu phiên. Không đưa matrix profile của P5.5/P5.6 vào P5.8 đã commit.

## Kiểm chứng phiên này

- Baseline trên main sạch: **53/53 test**, 0 failure/error/skip; gồm subscription order/payment/UI, pilot và access audit.
- Full `clean verify` trên tree Git riêng: **1.048/1.048 test trong 66 suite**, 0 failure/error/skip, exit 0; JAR đóng gói thành công. Lệnh: `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m -XX:ActiveProcessorCount=2' clean verify`. Maven dùng heap 512 MiB/2 CPU, test JVM dùng 768 MiB/2 CPU. Lượt chạy tổng hợp đầu đạt trong 12 phút 47 giây.
- `node --test src/test/js/payment-polling.test.cjs`: **6/6 đạt**, không failure/skip.
- Review độc lập trên đúng diff ứng viên không có actionable finding hoặc code blocker; điều kiện kiểm chứng full đã đạt. Đây là agent review, không thay human contributor approval.
- DB test do fixture tự tạo và dọn. Cấu hình riêng của worktree chỉ có thông tin kết nối DB, không có khóa provider live; scheduler tài chính/deadline tắt.

Không dùng kết quả 1053/1055 JUnit hoặc browser của source kết hợp ở các handoff cũ làm kết quả của tree Git riêng này. Phiên này không thay giao diện và không thực hiện browser/live-provider E2E mới.

## Gate còn mở

Publication chỉ đưa phần triển khai local lên Git. **P4.2-A/P8.8/P5.9 vẫn chưa được nghiệm thu live**: còn contract/quyền lookup người nhận, capability và recovery provider, contributor review/cutover theo runbook, giao dịch do người dùng chủ động và đối soát tiền thật. Không đánh dấu hoàn tất toàn bộ phase hoặc bật workflow/provider chỉ vì test offline đạt.

Nhánh tính năng phải được giữ trên local và remote sau merge. Checkout gốc tiếp tục giữ branch/HEAD/index và các thay đổi ngoài phạm vi.

## Evidence local

Tại checkout gốc, `.scratch/publish-ducbinh-20261108/` giữ baseline, snapshot nguồn đã chọn, provenance root/completion, log và JSON của baseline 53 test/full 1.048 test, manifest source, review độc lập, kết quả Git và kiểm tra bảo toàn. Cấu hình DB-only của worktree được Git bỏ qua; không có khóa provider live trong candidate.
