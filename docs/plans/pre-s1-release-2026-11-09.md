# Khôi phục trước Payment Settings S1 và phát hành thay đổi local

Ngày 2026-11-09. Theo yêu cầu trực tiếp của chủ dự án: hủy Payment Settings S1/S2/S3, dùng phiên bản trước S1; commit, push và merge vào main bằng tài khoản `ducbinhnguyen07ars`, giữ nhánh.

## Phạm vi

- S1/S2 được thực hiện ở `.scratch/payment-settings-20261108/worktree`, nhánh `feat/payment-settings-ui`, chưa được commit hoặc tích hợp root. S3 chưa thực hiện.
- Checkout gốc `feat/phase5-profile-password` tại `b829b05` chưa có credential DB, resolver AES-GCM hoặc settings UI của S1/S2. Giữ toàn bộ code local không thuộc S1/S2, gồm profile, UI, calendar, BEFORE entry, provider diagnostics, recipient lookup và regression tests hiện có.
- Khôi phục 36 path S1/S2 từ snapshot trước S1 hoặc gỡ file mới sau khi sao lưu và xác minh SHA-256. Không reset nhánh, force push hoặc xóa nhánh/worktree. Lưu lịch sử tài liệu với nhãn CANCELLED, không còn xem chúng là kế hoạch đang thực thi.
- Cấu hình provider vẫn lấy từ properties/environment hiện có. Không thêm bảng `payment_channel_credentials`, master key hay route payment-settings vào bản phát hành.
- Giữ việc xóa `uploads/seed-car-5.png` đã có trong checkout theo ý chủ dự án; không tự khôi phục ảnh.
- Không commit `.env`, `application-local.properties`, token/khóa thật, runtime uploads, target hoặc `.scratch`. Không migrate DB ứng dụng, restart runtime hoặc chuyển tiền thật.

## Sao lưu và tích hợp

Snapshot trước thao tác: `.scratch/publish-pre-s1-20261109/root-before/` và `settings-before/`, manifest JSON tương ứng. Đã sao lưu 467 path root và 461 path settings, đối chiếu SHA-256; cấu hình private chỉ ghi hash, không sao chép ra tài liệu.

Nhánh phát hành `feat/pre-s1-local-release` bắt đầu từ main `00a6039`. Tích hợp mọi path modified/deleted/untracked không bị ignore của checkout gốc; giữ code main đã tích hợp mà checkout gốc không thay đổi. Tài liệu S2 chỉ có ở worktree được lưu với nhãn CANCELLED để ghi đúng lịch sử, không mang source S1/S2 vào bản phát hành.

Credential Manager đã được dùng để xác minh trực tiếp GitHub API `/user`: `ducbinhnguyen07ars`, ID `284896356`. Danh tính author/committer dùng email noreply của tài khoản này. Kết quả kiểm chứng source cuối ghi bên dưới; commit/merge được đối chiếu bằng lịch sử Git và remote. Kiểm chứng local không thay provider/live acceptance.

## Review và kiểm chứng trước phát hành

Review độc lập phát hiện snapshot local làm mất kiểm tra lại quyền Customer của nhánh LEGACY so với main. Đã giữ lại guard `requireCurrentActor(customer, CUSTOMER)` không điều kiện và bốn biến thể test race status/role của cả hai workflow. RED tái hiện đúng hai failure ở workflow=false; GREEN 4/4, không failure/error/skip. Đây là bảo toàn security đã có trên main, không triển khai lại Payment Settings.

Lượt full đầu được chủ động dừng khi review phát hiện regression, giữ riêng log và không dùng để nghiệm thu. Lượt RED ghi nhận DB ứng dụng có booking ngày 10–11/11 và thông báo mới từ runtime đang hoạt động, khác ngày fixture 01/12/2030; không xóa/rollback dữ liệu đó hoặc tuyên bố DB không đổi. Các lượt sau dùng tài khoản MySQL tạm chỉ có quyền schema `carrent_*`, đã kiểm tra bị từ chối truy cập DB `carrent`; tài khoản/schema tạm được dọn sau run. Cấu hình DB-only nằm trong file Git-ignored của worktree, không copy `.env` hoặc provider secrets thật.

## Kết quả kiểm chứng source cuối

- Full isolated clean verify: **1210/1210 JUnit, 79 suites, 0 failure/error/skip**, executable JAR. Node **18/18**.
- Reviewer độc lập đã xác nhận guard LEGACY được giữ và không còn finding Critical/Important; đây là agent review, không thay human contributor review.
- Kiểm tra JAR không có lớp, bảng hoặc trang Payment Settings S1/S2 và không chứa `.env`/`application-local.properties`. Manifest source không drift trong lượt cuối.
- Tài khoản test bị từ chối truy cập DB ứng dụng; phép thử tạo trigger trên schema riêng đạt. Tài khoản/schema tạm của lượt cuối đã dọn. Các thay đổi runtime của người dùng không bị rollback.
- Lượt full thiếu quyền tạo trigger đã được dừng và giữ riêng. Schema fixture bị bỏ lại do dừng được kiểm tra UUID, thời gian tạo trong lượt chạy, dữ liệu seed test và 0 kết nối trước khi dọn; các schema có sẵn khác giữ nguyên.
- Phát hành Git không phải deploy: không restart app, migrate DB ứng dụng, bật provider/scheduler hoặc chuyển tiền thật. Browser/human visual/live acceptance không chạy lại trong phiên phát hành.
