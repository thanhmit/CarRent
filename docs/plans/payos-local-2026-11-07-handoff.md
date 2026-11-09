# payOS local — kết nối, mapping và giới hạn nghiệm thu

Ngày: 2026-11-07. Phạm vi được yêu cầu: kiểm tra bằng truy vấn trước, hoàn thiện code payOS và chuẩn bị chi thử. Không có lệnh chi thật được phát trong phiên này.

## Kết quả xác nhận

- Bộ khóa trong file local gọi được `GET /v1/payouts`: HTTP 200, code 00, chữ ký phản hồi hợp lệ, ban đầu không có lệnh chi.
- Chạy JAR từ source worktree với profile `payos-check`: danh sách lệnh và `GET /v1/payouts-account/balance` đều được xác thực. Nguồn trả về số dư 0 VND; hai reference hiện dùng chung bộ khóa qua alias trong cấu hình local.
- GET-only check không thay đổi fingerprint/count của cả 20 bảng ứng dụng.
- Profile `payos-setup` tạo hai mapping PAYOS/PAYOUT disabled: Owner user 2/channel 1/reference owner-payout; Admin user 1/channel 2/reference admin-payout. Đây là ID kênh chi payOS trong CarRent, không phải ID webhook SePay.
- Chỉ bảng payment_channels thay đổi khi setup; các bảng còn lại giữ fingerprint. Không có receipt/refund/payout attempt giả để mở đường chi thử.
- application-local.properties và .env giữ nguyên hash; không chép khóa vào source, docs hoặc evidence.

## Thay đổi code

1. `HttpPayOsPayoutClient.checkConnection`: chỉ GET list và source balance; kiểm tra chữ ký, schema, VND/nguyên/không âm; không cần bật chi; trả trạng thái có tiền hay không, không lộ số tài khoản/số dư cụ thể. Lỗi HTTP không đưa body/cause chứa dữ liệu provider vào startup log.
2. Recovery theo reference yêu cầu pagination.hasMore có kiểu boolean false. Response thiếu hoặc sai kiểu giữ UNKNOWN.
3. `LocalPayOsSetup`: profile payos-check chỉ đọc; payos-setup kiểm tra trước rồi provision transactionally. User phải ACTIVE/đúng role/khác nhau; IDs fallback sang cấu hình SePay. Mapping mới disabled; chạy lại giữ ID và activation; mismatch rollback, không repoint.
4. Hai profile properties và main đóng context sau lệnh. Scheduler payment/rental bị loại trừ ngay từ cấu hình trong cả payos-check/payos-setup/sepay-setup, kể cả khi cờ bên ngoài bật nhầm; runner cũng kiểm tra cờ trước thao tác.
5. Thêm placeholder payOS vào application.properties/.env.example; local credentials đã có vẫn được dùng. Không tự đổi cờ enabled của người vận hành.
6. Tích hợp phần setup SePay đã kiểm chứng từ checkout gốc vào worktree hoàn thiện; không sửa shared-bank routing hoặc mã thanh toán trong phạm vi payOS này.

Nền làm việc: `.scratch/completion-20261105/worktree`, branch feat/completion-profile-pilot. Chỉ các file payOS và caller/config/test/docs liên quan được đồng bộ về checkout gốc; giữ khác biệt BookingService/P5.9 có sẵn giữa hai cây. Chưa stage/commit/push/merge.

## Kiểm chứng và evidence

Evidence riêng được gitignore tại `.scratch/payos-20261107/` của checkout gốc:

| Lượt | Kết quả |
| --- | --- |
| Baseline worktree | 52/52 test payOS/properties |
| Pagination RED | 2 assertion fail, 0 error: thiếu/sai kiểu hasMore vẫn được nhận trước sửa |
| API check/setup RED | Compilation fail do API/class mới chưa tồn tại; lưu check-red.log và setup-red.log, không gọi là assertion RED |
| Scheduler RED | 3 assertion fail, 0 error: scheduler vẫn xuất hiện trong profile chẩn đoán trước sửa |
| Worktree regression | 152/152, 0 failure/error/skip, 9 suites |
| Root regression sau đồng bộ | 152/152, 0 failure/error/skip, cùng 9 suites; không cộng thành 304 test khác nhau |
| Package | Worktree và root đều build JAR thành công |
| Real GET / setup | JAR worktree kiểm tra + tạo mapping disabled; source ZERO, 0 payout tạo mới |

Các suite: PayOsConnectionCheckTest, PayOsPayoutClientTest, ProviderPaymentPropertiesTest, LocalPayOsSetupTest, LocalSePaySetupTest, PaymentSetupSchedulerIsolationTest, PayoutWorkerTest, PaymentMaintenancePayoutTest, RefundRecipientTest. Test HTTP dùng response ký fixture độc lập, test DB dùng schema riêng và dọn qua fixture.

Maven dùng POM evidence chỉ thay source/resource/output/workingDirectory và giới hạn JVM, không thay dependency. Source root và worktree được build riêng. Những lỗi auxiliary snapshot ban đầu do giả định mọi bảng có cột id đã được sửa bằng đọc primary-key metadata; không chạy DDL hoặc sửa dữ liệu để xử lý lỗi đó.

Hồ sơ: connection-first.json, read-only-check-evidence.json, setup-evidence.json, database-before/after-check/after-setup.json, các log regression/package/live-java và source manifests. Không gọi bộ 1055 test/browser cũ là kiểm chứng source mới; phiên này không chạy lại toàn dự án/browser.

## Phần chưa thể hoàn tất bằng bộ khóa hiện có

### 1. Xác minh người nhận

Production RefundDestinationVerifier tiếp tục từ chối vì chưa có API lookup độc lập không chuyển tiền đã xác nhận. API `/v1/payouts-account/balance` là tài khoản NGUỒN; `toAccountName` trong kết quả payout không phải lookup trước khi chuyển.

Cần provider cung cấp: sản phẩm/endpoint/method, auth và quyền kênh, request bank BIN/account, response tên chủ tài khoản/cách xác thực, mẫu invalid/timeout, phí/rate limit. Nếu payOS không có sản phẩm này, chủ dự án cần chọn dịch vụ lookup phù hợp hoặc duyệt thay đổi yêu cầu. Không dùng tên tự khai hay tạo payout để tra tên.

### 2. Quyền chi, thiếu tiền và recovery

GET thành công chỉ chứng minh quyền truy vấn và checksum tương ứng; chưa chứng minh quyền POST/approval/credit/minimum/fees. Nguồn hiện 0 VND; cần nạp riêng ví và kiểm tra credit dịch vụ trước chi thật.

Public API/SDK đã đọc không cung cấp bảo đảm đủ rõ cho NOT_SENT, retention/replay idempotency và từng mã lỗi thiếu tiền. Adapter giữ UNKNOWN cho HTTP error/FAILED/CANCELLED/REVERSED, không retry POST. Cần response và bảo đảm provider đã che thông tin trước khi map retry/WAITING_FUNDS production.

### 3. Chi thử và nghiệm thu

Chuẩn bị một refund hợp lệ, người thực hiện, môi trường, source, BIN/tài khoản đích do người dùng kiểm soát, số tiền VND, thời gian/kết quả dự kiến và reviewer. Thông tin ngân hàng/khóa chỉ nhập vào nơi bảo vệ, không gửi chat/docs.

Chỉ sau khi lookup và quyền chi được giải quyết mới bật phạm vi cần thiết và thực hiện chi thử qua luồng refund hiện có. Không cung cấp một endpoint/CLI chuyển tiền trực tiếp để vượt kiểm tra nghiệp vụ. Luồng E2E vẫn cần hoàn thiện SePay dùng chung ngân hàng và độ dài mã riêng theo hồ sơ trước.

Giữ P4.2-A/P8.8/P5.9 mở; số task local 83/86 không đổi. Hướng dẫn vận hành: [local-payos-setup.md](../local-payos-setup.md).


## Ki?m tra ch?t

- JAR root ch?y l?i payos-setup th?nh c?ng; hai channel v?n l? 1/2 v? disabled, fingerprint c? 20 b?ng gi? nguy?n so v?i sau setup l?n ??u.
- Kh?ng c? baseline file ngo?i ph?m vi b? thay ??i; hai file private gi? nguy?n hash; qu?t artifact li?n quan kh?ng c? kh?a th?t.
- Root JAR SHA256: `630ee55db3d52fab4e2e1f9fdcb3e0ec515cf6b9b29bf83ece512f175315024c`. Artifact v?n ch? human review.
- B?ng ch?ng t?ng h?p: `.scratch/payos-20261107/final-evidence.json` ? checkout g?c.
