> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment settings — worktree và bàn giao kế hoạch

> **Nghiệm thu S1 — 09/11/2026:** S1 COMPLETE trong worktree: 1.230/1.230 Java tests (80 suites), 81 test mới, Node 18/18, clean verify/JAR và audit đạt. S2/S3 NOT_STARTED. Xem [bàn giao S1](payment-settings-S1-handoff.md); chỉ ghi nhận local completion, chưa tích hợp root hoặc bật provider thật.

> **Checkpoint 09/11/2026:** source/config công khai được đối chiếu có 31 path khác nhau giữa root và worktree, gồm BEFORE/calendar/fixtures/UI. Theo [S1 hiện hành](payment-settings-S1-execution-2026-11-09.md), giữ snapshot 08/11 và refresh baseline có allowlist/hash một lần trước S1; chưa thực hiện refresh source hoặc chạy test mới. Các tài liệu cập nhật được đồng bộ riêng sang worktree.

Ngày **2026-11-08**. Trạng thái: SDD và kế hoạch đã viết; **chưa implement tính năng, chưa migrate DB ứng dụng, chưa commit/push**.

## Worktree đã tạo

- Path: `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`
- Branch: `feat/payment-settings-ui`.
- Base commit: main `00a60396f2153dfb697e82f6b84a5bd743daf8dc`.
- Checkout gốc giữ branch `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`; HEAD/index/main/private config giữ nguyên khi chuẩn bị.
- Chọn `.scratch/` theo các worktree đang dùng của dự án; `git check-ignore` xác nhận đã được ignore. Không sửa .gitignore hoặc sử dụng thư mục `.worktrees` chưa được ignore.

## Snapshot phụ thuộc

Worktree là **main + snapshot source/docs local hiện tại**, không phải clean main và không phải feature đã implement. Đã đóng băng 409 file trong src/docs, pom.xml, scriptDB.sql và .env.example để có các thay đổi PayOS read-check, SePay shared account, UI shell và profile đang được dùng ở máy này.

Snapshot gốc không đổi: `.scratch/payment-settings-20261108/baseline-source/`; manifest SHA-256: `baseline-source-manifest.json`; ghi nhận thao tác: `root-before.json`, `worktree-preparation.json`. Các path này ở root dự án, ngoài thư mục worktree.

Các khác biệt kế thừa của thành viên khác không thuộc feature mới. Khi implement, so với snapshot để tính feature diff; **không git add -A hoặc commit toàn bộ status**. Trước publication sau này phải chọn/tích hợp prerequisite riêng theo provenance và reviewer. Phiên lập kế hoạch không đổi author/credential Git.

Không copy `.env`, cấu hình có khóa provider, private uploads, backup DB hoặc runtime target của root. Worktree có `application-local.properties` **DB-only**, Git-ignored, chỉ cho fixture tạo schema UUID riêng. Không dùng app DB làm fixture. Seed asset tracked từ main được giữ trong worktree, không khôi phục/xóa asset trong root.

## Baseline vừa kiểm chứng

Lệnh Maven trên source snapshot, JDK 21 và output target mới của worktree:

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:MAVEN_OPTS='-Xmx768m'
./mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m' '-Dtest=PayOsConnectionCheckTest,PayOsPayoutClientTest,PaymentPilotConfigurationTest,SePayWebhookVerifierTest,PaymentPilotTest,SePaySharedAccountTest' test
```

Kết quả mới: **96 tests, 6 suites, 0 failure/error/skip; BUILD SUCCESS**, Maven 57.382 giây. Báo cáo `baseline-tests.json` và `baseline-tests.log` trong `.scratch/payment-settings-20261108/`. Không gọi kết quả này là full verification hay kiểm chứng tính năng mới.

## Tài liệu phải đọc trước implement

1. [SDD bổ sung](../design/payment-settings-2026-11-08.md), PS-01–08 và giới hạn 240 phút.
2. [Kế hoạch implementation](payment-settings-implementation-2026-11-08.md), PS-A → PS-F.
3. [SDD chính §8.8](../SDD.md#88-trang-kết-nối-thanh-toán-payossepay--2026-11-08).
4. [P4.2-A/P8.8 hiện tại](p42a-p88-completion-analysis-2026-11-08.md) để không nhầm UI setup với provider/live acceptance.
5. [Ba phiên implement S1/S2/S3](payment-settings-sessions-2026-11-08.md), mỗi phiên 80 phút; S2/S3 đọc handoff phiên trước rồi kiểm tra actual source.
6. [S1 phân tích chi tiết trước implement](payment-settings-S1-preimplementation-2026-11-08.md): danh sách file, interfaces, migration boot-path, ma trận test và chính sách lỗi; preflight bổ sung 49/49 test đạt.

Các tài liệu được lưu cả ở root để người dùng review và trong worktree để phiên implement có đủ context. Nếu thay đổi thiết kế, đồng bộ đúng file tài liệu sau kiểm tra hash. Refresh source một lần theo S1-0 ngày 09/11, không kéo lại toàn bộ root hoặc sao chép private config; không đồng bộ tùy ý giữa các phiên.

## Lệnh bắt đầu phiên thực thi sau này

```powershell
Set-Location 'C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree'
git branch --show-current
git rev-parse HEAD
git status --short
```

Xác nhận đúng branch/base rồi dùng `superpowers:executing-plans`, bắt đầu S1 (PS-A/B), bàn giao rồi tiếp S2 (PS-C/D), S3 (PS-E/F) trên cùng worktree. Mục tiêu 4 giờ đã được người dùng đồng ý cho tính năng cấu hình đã giới hạn; không tự chuyển sang làm lookup production, tạo webhook tự động hoặc gửi tiền để đạt con số 100%.

Điểm dừng: có đủ UI/DB/resolver và verification/handoff thì báo local completion; provider quyền chi/lookup/giao dịch thật giữ trạng thái riêng. Không tự migrate DB ứng dụng, bật payout, restart8080, commit/push/merge hoặc xóa nhánh.
