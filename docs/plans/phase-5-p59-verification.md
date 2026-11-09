# P5.9 — Kiểm chứng cuối và gate vận hành

Theo [checklist P5.9](phase-5-admin-polish.md#task-p59--e2e-cuối-gate-tiền-thật-và-đồng-bộ-tài-liệu). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`. Đã đạt **5/6 checklist**, còn gate live nên **chưa hoàn thành P5.9**. Source cuối đạt 1053/1053 JUnit, Node polling 6/6 và Chrome 256/256 với 108 PNG; chi tiết và lượt full đầu thiếu heap được ghi riêng tại [bàn giao đợt6](phase-5-batch-6-handoff.md). Kết quả local chưa cho phép kích hoạt.

## Tiếp tục P5.9 từ trạng thái hiện tại

**Bàn giao ngày 2026-11-04:** worktree `C:\Users\Admin\Documents\hoc\Car`, branch `feat/phase5-profile-password`; thay đổi triển khai và tài liệu đang ở working tree, chưa commit/push. Tiến độ **83/86 task**: P5.8 đã đóng; P4.2-A, P8.8 và P5.9 còn pending. Đọc mục này trước khi tiếp tục implement P5.9 hoặc chuẩn bị thanh toán live.

**P4.2-A pending là blocker trực tiếp của gate live P5.9.** Hoàn tất P4.2-A chưa tự hoàn tất P5.9: còn review, migration/kích hoạt pilot và E2E thật theo runbook. Năm mục local của P5.9 đã có bằng chứng; chưa có bằng chứng live để tick mục thứ sáu.

| Phần còn thiếu | Task / người phụ trách | Điều kiện để đi tiếp |
| --- | --- | --- |
| Tài khoản, kênh và capability provider thật | [P4.2-A](phase-4-payment.md#task-p42-a--tích-hợp-tài-khoản-thực-tế-và-kiểm-chứng-providerlive--hoãn), `thanhmit`, reviewer `huy69332` | Thu SePay, chi payOS/nguồn Bảo Kim riêng cho Owner/Admin; mapping, callback, quyền query/chi, phí/tối thiểu, funding và recovery có evidence |
| Xác minh tài khoản nhận hoàn trước dispatch | P4.2-A / nền P4.7 | Xác nhận lookup không chuyển tiền và nối adapter production thật; verifier mặc định hiện vẫn từ chối |
| Review, migration và kích hoạt đúng một pilot | [P8.8/runbook](phase-8-pilot-runbook.md), `ducbinhnguyen07ars`, reviewer `thanhmit` | Source/JAR được duyệt, backup/restore/migration được kiểm chứng, cấu hình/kích hoạt cụ thể được chủ dự án cho phép |
| Nghiệm thu nghiệp vụ thật cuối cùng | P5.9, `ducbinhnguyen07ars`, reviewer `thanhmit` | Người dùng chủ động giao dịch; receipt, gói, thu 30/70 và hoàn tiền cần dùng có đối soát thực tế; đồng bộ checklist với evidence |

### Trình tự phiên implement tiếp theo

1. Đối chiếu `git status`, branch và tài liệu hiện hành; giữ các thay đổi chưa commit. Đọc [P4.2-A](phase-4-payment.md#task-p42-a--tích-hợp-tài-khoản-thực-tế-và-kiểm-chứng-providerlive--hoãn), [provider contract](payment-provider-contract.md#4-gate-còn-lại--chuyển-sang-p42-a-chưa-thực-hiện) và [runbook](phase-8-pilot-runbook.md). Kiểm tra lại trạng thái các gate, vì bàn giao này là snapshot ngày 2026-11-04.
2. Nhận đầu vào không bí mật: ID Owner/Admin CarRent, xác nhận tài khoản/kênh thu/chi đã được provider kích hoạt, mapping channel/credential reference, public HTTPS callback và bằng chứng capability. Credential/secret được người vận hành cấu hình phía server; key, OTP và mật khẩu không đi vào chat/Git/log. Hiện chưa có đủ đầu vào và bằng chứng này.
3. Hoàn tất từng gate P4.2-A với provider thật. Đọc `src/main/java/com/carrent/config/PaymentProviderConfiguration.java`: bean `RefundDestinationVerifier` mặc định fail-closed. Chỉ triển khai adapter/mapping thật sau khi xác minh được API và quyền sử dụng; bổ sung regression theo payload đã che thông tin nhạy cảm. Thiếu lookup bắt buộc thì giữ pending, không dùng tên tự khai hoặc payout create để tra tên. Giữ `UNKNOWN` chỉ đối soát khi chưa có bảo đảm an toàn để gửi lại.
4. Sau capability/review, thực hiện phần migration và kích hoạt được cho phép theo [migration README](../migrations/README.md) và runbook P8.8. Xác minh đúng source/JAR/schema, cặp pilot và các flags cần dùng. Thao tác thực tế phải có phạm vi được duyệt; checklist local không cấp quyền thay DB, restart hoặc chuyển tiền.
5. Người dùng chủ động thực hiện giao dịch kiểm chứng đã xác định khoản tiền/kênh/đích. Ghi evidence đã che dữ liệu nhạy cảm: môi trường, channel/chủ thể, provider transaction/payout IDs, amount/thời điểm, receipt/entitlement/booking/refund và kết quả đối soát. Dùng bảng gate bên dưới để ghi kết quả từng mục; trạng thái chờ hoặc webhook replay không chứng minh đã nhận/chuyển tiền thật.
6. Nếu sửa code/provider contract, chạy lại regression/browser liên quan và full verification trên source cuối bằng datasource/schema thử nghiệm riêng. Nếu source không đổi, kiểm tra manifest/JAR và giữ bằng chứng local hiện có; không ghi kết quả cũ thành một lượt chạy mới. Chỉ tick gate live P5.9 khi đủ evidence và reviewer xác nhận; chốt P4.2-A/P8.8 theo checklist riêng, rồi cập nhật checkpoint/bộ đếm thực tế.

Bằng chứng local hiện ở `.scratch/phase5/p58/verification-summary.json` và các đường dẫn trong [bàn giao](phase-5-batch-6-handoff.md). `.scratch` là evidence local, không được giả định tồn tại trong clone/worktree khác. Nếu thiếu artifact hoặc source đã đổi, ghi rõ và kiểm chứng lại phần cần thiết. Trong lần cập nhật bàn giao này chỉ sửa tài liệu, không chạy lại test hoặc kiểm chứng provider live.

## Hành trình không dùng SQL để chuyển nghiệp vụ

`FinalRentalJourneyTest` dùng HTTP/Spring Security/CSRF và domain handler thật: Owner mua gói, nhận webhook SePay có chữ ký offline qua kênh Admin; tạo xe mới, Admin duyệt, Customer tạo booking mới; Owner nhận đơn và gửi BEFORE, Customer đồng ý, nhận đủ30%, Owner ký/giao xe; Customer gửi AFTER, Owner nhận lại có bất đồng, thu70%, COMPLETED. SQL chỉ chuẩn bị/đọc fixture; không ép trạng thái booking, gói hoặc tiền để bỏ qua bước.

Biên bản/ảnh/sự kiện AFTER phải giữ nguyên sau hoàn tất và replay webhook. Nghĩa vụ phí gói có collector Admin; nghĩa vụ thuê có collector Owner. Tiền thuê không trở thành doanh thu Admin.

## Lối vào workflow mới

Audit phát hiện `BookingService.createBooking` luôn tạo LEGACY, trong khi kiểm chứng lifecycle trước đó dùng booking VIETQR_V1 tạo sẵn. Lối vào mới được kiểm chứng bằng regression rồi nối vào public create:

```properties
# Mặc định false; đây là mô tả cấu hình, chưa thay đổi cấu hình ứng dụng.
carrent.rental.workflow.enabled=false
```

Chỉ khi được phép bật cờ, đúng cặp Owner/Admin pilot hợp lệ, đúng role/ACTIVE hiện tại và có cả handler advance/balance thì booking mới của Owner pilot dùng `VIETQR_V1`, `inspection_required=true`, trạng thái ban đầu PENDING. Thiếu handler hoặc tài khoản pilot không còn hợp lệ phải từ chối. Customer được refresh/READ-lock sau khi chờ khóa aggregate; ban/đổi role trong lúc chờ bị từ chối trước insert, không tạo booking/notification/tiền. Khi cờ tắt hoặc Owner ngoài pilot, giữ LEGACY. Form không quyết định workflow, inspection, amount, status hoặc thời điểm. Booking cũ không được đổi workflow hàng loạt.

Regression gồm12 cấu hình admission, kể cả workflow hợp lệ khi subscription tắt; hai race thực tế đổi status/role Customer khi đang chờ Car. Các lượt RED thật, lỗi compile/setup và GREEN ghi riêng trong `.scratch/phase5/p58/p59-journey-report.md`.

## Nhánh lỗi đã có kiểm chứng riêng

| Nhánh | Test chính được chạy trong full regression |
| --- | --- |
| Customer/Owner hủy trước giao, phí và quyền hủy | `RentalPaidCancellationTest`, `RentalCancellationGuardTest`, `RentalCancellationUiTest` |
| Hết hạn/late advance, booking B giữ lịch | `RentalAdvanceTest`, `RentalLifecycleGateTest`, `RentalConcurrencyTest` |
| Thiếu recipient/funds, provider mất response và retry/restart | `RefundRecipientTest`, `PayoutWorkerTest`, `PaymentMaintenancePayoutTest` |
| Replay webhook, receipt gói trùng/late | `SePayInboxTest`, `SubscriptionPaymentTest`, `SubscriptionOwnerUiTest` |
| Nợ70% quá hạn vẫn trả được | `RentalBalanceDeadlineTest`, `RentalBalanceTest` |
| Gói hết hạn/gia hạn/hạ gói và rental đang chạy | `SubscriptionVisibilityTest`, `SubscriptionDowngradeTest`, `SubscriptionRentalContinuityTest` |

Không suy pass từ tên test: kết quả full trên source cuối và browser được ghi ở bàn giao đợt6. Sáu test Node `src/test/js/payment-polling.test.cjs` chạy riêng vì Maven chưa gọi Node.

Browser dùng Chrome và business endpoint thật trên schema/port riêng. Kiểm tra1440/390/320: QR ẩn tại deadline client, polling tự điều hướng theo trạng thái server, input hợp lệ giữ qua lỗi validation, trạng thái chờ recipient/funds/processing không gọi là đã hoàn. CSS/font và bitmap QR dùng asset fixture local; không chứng minh QR quét được hoặc provider live. Browser lifecycle bắt đầu từ booking fixture; hành trình từ mua gói/tạo xe/tạo booking mới nằm ở test HTTP xuyên suốt bên trên.

## Gate còn cần bằng chứng thật

| Điều kiện bắt buộc | Bằng chứng cần cung cấp | Trạng thái |
| --- | --- | --- |
| Human review | Reviewer duyệt đúng source/JAR; migration được review | Chưa có bằng chứng |
| Một Owner + một Admin | ID user CarRent, chủ thể/kênh thu SePay và nguồn chi payOS/Bảo Kim riêng được duyệt | Chưa xác nhận live |
| Webhook public | HTTPS callback tới đúng channel, xác thực provider và đối soát giao dịch | Chưa kiểm chứng live |
| Credential | Cấu hình secret phía server qua file local/environment được bảo vệ | Không yêu cầu gửi secret vào chat |
| Lookup và payout capability | API xác minh đích không chuyển tiền, mức tối thiểu/phí, funding, lookup/recovery; production verifier hiện từ chối | Pending P4.2-A |
| Migration/kích hoạt | Backup/restore thử, migration đúng DB, cho phép cấu hình/triển khai | Chưa thực hiện |
| Người dùng chủ động giao dịch | Khoản tiền/kênh/đích được xác định; receipt, entitlement, hoàn tiền và đối soát thực tế | Chưa thực hiện |

Theo [pilot runbook](phase-8-pilot-runbook.md) và [provider contract](payment-provider-contract.md). Hoàn tất local không thay gate này. Không tick P5.9/P8.8/P4.2-A hoặc toàn hệ thống trước khi từng gate bắt buộc có bằng chứng.
