# SePay + VietQR local — bàn giao 2026-11-07

**Cập nhật cuối ngày 2026-11-07:** đã tạo webhook thật trên SePay, cấu hình CR + 30 ký tự số/chữ, lưu HMAC secret vào `.env`, bật kênh thu 3/4 và chạy profile `sepay-local`. Hai lượt kiểm chứng mới đạt 186/186; ngrok/HMAC đã qua kiểm tra không ghi giao dịch. Chưa thu tiền thật. Các thông tin “secret trống / chưa activation / chưa restart” bên dưới là snapshot trước cập nhật này. Xem [bàn giao webhook đã kết nối](sepay-webhook-connected-2026-11-07.md).

## Trạng thái hiện tại

- Đã triển khai và kiểm chứng local phần thu tiền vào tài khoản TPBank chung cho Owner/Admin. Chưa có giao dịch ngân hàng/webhook Live được nghiệm thu.
- Root: `feat/phase5-profile-password`, HEAD `b829b05`; worktree `.scratch/completion-20261105/worktree`, nhánh `feat/completion-profile-pilot`, HEAD `1862aa9`. Cả hai đã dirty từ trước. Không commit/push/merge.
- Thay đổi SePay đã đồng bộ có kiểm tra baseline. `BookingService` và khác biệt P5.9 không bị ghi đè; `PaymentIntentTransactions` chỉ áp dụng các hunk thuộc phạm vi này và giữ kiểu xuống dòng của root.
- `.env` riêng có BIN TPBank **970423**, gateway **TPBank** và tài khoản nhận do người dùng cung cấp. Không lưu số tài khoản, tên chủ tài khoản hoặc HMAC vào tài liệu. HMAC secret còn trống tại lần kiểm tra.
- ngrok được kiểm tra bằng `/api/tunnels`: `https://cloak-siberian-cartoon.ngrok-free.dev` → `http://localhost:8080`. Không đọc inspector chứa payload/header.

## Những gì đã hoàn thiện

1. Intent mới dùng `CR` + 30 ký tự hex, tương thích hậu tố tối đa 30 của SePay. Không sửa mã/lịch sử intent cũ. Trước Live cần intent mới; không cắt mã thủ công.
2. Một webhook có thể định tuyến theo mã intent sang collector Owner/Admin có cùng BIN, tài khoản nhận và secret. Kiểm tra gateway, tài khoản, timestamp và HMAC raw body trước ghi inbox.
3. Khóa các kênh cùng tài khoản theo ID, transaction `READ_COMMITTED`, dedup liên kênh theo provider transaction ID. Delivery/replay đồng thời không tạo hai receipt hoặc thanh toán hai đơn bằng cùng giao dịch. Payload đầu tiên được giữ.
4. Intent cũ thuộc kênh sibling đã tắt vẫn có thể nhận tiền qua webhook chung đã bật và cùng trust boundary. Mã không khớp hoặc khác tài khoản/secret không được phân bổ cho đơn khác.
5. Giới hạn tạo QR: tài khoản 1–19 chữ số, amount tối đa 13 chữ số; dữ liệu ngoài giới hạn bị từ chối trước khi tạo intent. Parser nhận tiền vẫn giữ khả năng ghi nhận account/amount theo contract webhook, không đánh mất tiền chỉ vì giới hạn phát QR.
6. `sepay-activate`: validate + tạo/cập nhật đúng hai mapping thu trong một transaction, yêu cầu HMAC secret, không sửa payOS, không chạy scheduler, thoát sau khi in URL.
7. `sepay-local`: bật pilot mua gói/thuê xe 30%/70%, inbox worker và deadline; `carrent.payment.payout.enabled=false` chặn cả dispatch lẫn query chi, provider payOS tắt. Profile mặc định vẫn tắt thu và không tự provision.

HMAC giữ nguyên contract chính thức: `X-SePay-Timestamp`, `X-SePay-Signature: sha256=<hex>`, ký `{timestamp}.{raw_body}`, ±300 giây, constant-time compare. Không fallback sang API Key khi chữ ký lỗi.

## Mapping local đã tạo

| ID | User | Role | Provider / purpose | Reference | Enabled |
| --- | --- | --- | --- | --- | --- |
| 1 | 2 | OWNER | PAYOS / PAYOUT | owner-payout | false |
| 2 | 1 | ADMIN | PAYOS / PAYOUT | admin-payout | false |
| 3 | 2 | OWNER | SEPAY / COLLECT | owner-live | false |
| 4 | 1 | ADMIN | SEPAY / COLLECT | admin-live | false |

**Chỉ tạo một webhook cho ngân hàng chung:**

```text
https://cloak-siberian-cartoon.ngrok-free.dev/webhooks/sepay/3
```

Đây là ID CarRent trên máy này. Máy clone khác phải tự tạo mapping và dùng domain/ID riêng. Không dùng ID payOS 1/2 làm endpoint SePay.

## Verification và evidence

Evidence riêng ở `.scratch/sepay-20261107/`, không đưa credential/raw financial payload vào Git.

- Baseline trước sửa: 71 test, 0 failure/error/skip.
- RED/GREEN riêng cho độ dài mã, shared routing, activation, chặn dispatch/query chi, gateway và giới hạn QR. RED gốc được giữ, không ghi đè bằng GREEN.
- `shared-regression` từng có một lỗi fixture: đổi BIN cả kênh PAYOS vi phạm constraint. Sửa SQL fixture chỉ cập nhật `provider='SEPAY'`; không coi lượt lỗi là pass.
- Gateway regression: 31/31 pass.
- QR boundary RED: 3 failure, 0 error trong 10 test; GREEN: 32/32 pass (`PaymentIntentTest`, `LocalSePaySetupTest`).
- Root cuối: **189/189 JUnit, 0 failure/error/skip**, gồm shared/inbox/HMAC/property/intent/receipt/setup/collection-only/scheduler/maintenance/subscription/advance/balance.
- Root package: Maven offline `package -DskipTests` exit 0; JAR riêng dưới `.scratch/sepay-20261107/root-build/`. Không ghi đè target của app đang chạy.
- Full source worktree được chạy riêng; xem kết quả tổng hợp cuối bên dưới hoặc `worktree-regression.log`. Lượt này biên dịch trước 3 ca QR boundary mới; 3 ca đó đã kiểm chứng trong GREEN và root 189 phía trên.
- CLI thật từ root classpath: `sepay-setup` exit 0, tạo đúng hai kênh tắt và in URL chung. Không migration hoặc request chuyển tiền.
- Snapshot 20 bảng trước/sau: chỉ `payment_channels` thay đổi (2 → 4 hàng); 19 bảng còn lại giữ nguyên fingerprint. Hai kênh payOS vẫn tắt. `application-local.properties` giữ nguyên hash. `.env` chỉ được cập nhật thông tin ngân hàng theo người dùng.
- `git diff --check` root/worktree exit 0. Các cảnh báo LF/CRLF không phải lỗi whitespace.

Các lệnh kiểm chứng dùng POM riêng trỏ nguồn/resources root hoặc worktree để bảo toàn tiến trình 8080. Test tạo/xóa schema UUID riêng; không bootstrap DB ứng dụng.

## Thao tác còn cần người dùng

1. Điền `CARRENT_SEPAY_WEBHOOK_SECRET` vào `.env`, cùng Secret Key trên Dashboard; không gửi qua chat. Webhook JSON, Tiền vào, HMAC-SHA256, đúng tài khoản chính TPBank, không VA/TKP; mẫu mã tiền tố `CR`, hậu tố 30 ký tự chữ/số. Cho nhận giao dịch không mã vào đối soát nếu form hỗ trợ.
2. Chạy riêng `sepay-activate`, sau đó dừng app cũ bằng terminal và chạy `sepay-local`. Hướng dẫn đầy đủ: [local-ngrok-sepay-setup.md](../local-ngrok-sepay-setup.md). Chưa chạy activation hoặc restart 8080 trong đợt này.
3. Người dùng tự thanh toán QR mới hợp lệ. Đối chiếu giao dịch SePay, ACK 200 + success, inbox PROCESSED, receipt APPLIED, gói/booking cập nhật đúng một lần. Thuê xe cần kiểm chứng cả 30% và 70% theo nghiệp vụ.
4. Khi webhook bị lỡ do local/tunnel dừng, gửi lại giao dịch cũ từ lịch sử SePay sau khi app hoạt động; không chuyển tiền lần hai. Chưa tích hợp client query SePay tự động. Giữ numeric ID v1, không coi UUID v2 là giao dịch mới của cùng khoản tiền.

## Yêu cầu mới: thử hoàn thật sau khi nạp Bảo Kim

- GET thật qua `payos-check` đã xác minh chữ ký và trả `source=FUNDED` cho Owner/Admin dùng chung nguồn. Evidence: `payos-funded-check-result.json`; không in số dư/tài khoản/credential.
- FUNDED chỉ có nghĩa nguồn lớn hơn 0; không chứng minh đủ phí/credit, khoản tối thiểu hoặc quyền POST/duyệt.
- DB ứng dụng hiện **0 receipt, 0 refund, 0 payout attempt, 0 tài khoản nhận hoàn đã xác minh**. Chưa có khoản hoàn thật để gửi.
- Production `RefundDestinationVerifier` vẫn từ chối vì chưa có API lookup độc lập được tích hợp. Không sửa READY giả hoặc dùng payout create để tìm tên.
- Người dùng đề nghị khoảng 1.000 VND; chưa xác minh min/phí của kênh cụ thể. Đã hỏi người dùng xác định tài khoản nhận vì tài khoản TPBank trước đó mới được chỉ định cho thu SePay; chưa dùng nó làm đích chi.
- Cần contract/entitlement/credential lookup không chuyển tiền (payOS/Casso/VietQR/CAS được provider xác nhận), khoản hoàn thực tế, đích được xác minh và điều kiện create/duyệt/phí phù hợp trước khi gửi. Không có payout thật được tạo trong đợt này.

## Giới hạn nghiệm thu

Chưa có bằng chứng browser/Dashboard hoặc thu/hoàn thật. Native computer-use không khả dụng trong phiên này; không lấy HTTP/MockMvc làm bằng chứng đã bấm Dashboard. `subAccount` khác rỗng chưa được hỗ trợ. PNG VietQR hiện còn được hỗ trợ theo tài liệu, cần xem lại khi provider dừng định dạng này. P4.2-A/P8.8/P5.9 vẫn mở; không thay human review bằng test local.

Nguồn đối chiếu: [SePay contract](sepay-collection-contract-2026-11-07.md), [payOS live prerequisites](payos-refund-live-prerequisites-2026-11-07.md).
