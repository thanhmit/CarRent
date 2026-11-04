# P8.8 — điều kiện và quy trình demo một Owner

Tài liệu chuẩn bị vận hành, **không phải phê duyệt hoặc bằng chứng đã kích hoạt**. P8.8 và P4.2-A còn pending khi thiếu gate thật. Xem [checklist Phase8](phase-8-owner-subscriptions.md#task-p88--gate-tích-hợp-gói-và-kích-hoạt-demo-một-owner), [provider contract](payment-provider-contract.md) và [quy trình migration](../migrations/README.md).

## 1. Phân biệt các chốt chặn

| Chốt chặn | Vai trò | Không thay thế |
| --- | --- | --- |
| Cặp ID pilot | Giới hạn một Owner và một Admin được chọn | Kích hoạt provider, credential, review hoặc migration |
| Handler/workflow flags | Cho phép nghiệp vụ rental/subscription chạy | Quyền payer, role hiện tại và cặp ID pilot |
| Payment channels/provider configuration | Chọn đúng nguồn thu/chi và cấu hình provider | Xác nhận phí, khả năng lookup và giao dịch thật |
| P4.2-A và human review | Xác nhận capability và điều kiện live | Test bằng fake provider |

Hai property mới được đọc tại startup:

```properties
# Chỉ điền ID đã được người vận hành duyệt trong cấu hình local/environment.
carrent.payment.pilot.owner-id=0
carrent.payment.pilot.admin-id=0
```

Biến môi trường tương ứng: `CARRENT_PAYMENT_PILOT_OWNER_ID`, `CARRENT_PAYMENT_PILOT_ADMIN_ID`. Mặc định thiếu/0/âm hoặc hai ID trùng nhau: không mở QR. Hai ID phải là user khác nhau, đúng Owner/Admin ACTIVE theo kiểm tra nghiệp vụ hiện có. Không đưa credential thật vào tài liệu/Git/log.

- SUBSCRIPTION: payer phải là Owner pilot và collector phải là Admin pilot.
- ADVANCE/BALANCE: collector phải là Owner pilot; Customer vẫn là payer đúng booking, không phải Owner pilot.
- Owner mua gói không phải liên kết SePay/payOS để làm người trả tiền hoặc nhận hoàn. Khi phát sinh refund mới yêu cầu tài khoản nhận và xác minh theo Phase4.
- Pilot không tự chọn Admin mới: quy tắc duy nhất một Admin có kênh thu hợp lệ vẫn giữ nguyên. Các kiểm tra channel/provider, quyền và CSRF vẫn áp dụng.
- Sau thay đổi cấu hình cần quy trình triển khai/restart được cho phép. Phiên implement không tự restart8080 hoặc thay cấu hình ứng dụng.

## 2. Bằng chứng cần có trước khi xin kích hoạt

| Gate | Bằng chứng cần ghi | Trạng thái hiện tại |
| --- | --- | --- |
| Code/local | Full regression, browser E2E, source/JAR hashes, SQL invariants | Theo checkpoint P8.8 local và verification-summary.json |
| Review | Reviewer `thanhmit`; Car/Booking `huy69332` khi liên quan; phiên bản code được duyệt | Pending human review |
| Migration | Backup đúng schema, restore thử, migration source/target review, fingerprint trước/sau | Chưa migrate DB ứng dụng |
| Owner và Admin | Mỗi bên có bộ thu SePay và nguồn chi payOS/Bảo Kim riêng được duyệt | Pending P4.2-A |
| Lookup/refund | API kiểm tra đích không chuyển tiền, phí/tối thiểu kể cả 5.000 VND, funding/credit, đối soát/recovery | Pending P4.2-A; production verifier hiện từ chối |
| Giao dịch thật | Người dùng xác định khoản tiền/kênh/đích; ghi nhận receipt, activation và refund đối soát được | Chưa thực hiện |
| Kích hoạt | Chủ dự án cho phép cụ thể migration, cấu hình/flags và triển khai | Chưa có |

Không dùng bootstrap `scriptDB.sql` để thay DB đang có hoặc cấp entitlement hàng loạt. Theo thứ tự phụ thuộc trong migration README; JAR hiện hành cần schema payment/refund/rental/cancellation/subscription, dù flags tắt. Giữ Hibernate không tự sửa schema. DDL MySQL implicit commit, không xem rollback transaction là phục hồi migration.

## 3. Kiểm chứng local có thể thực hiện

DB ngẫu nhiên dùng riêng, provider fake/offline, không mock business endpoints:

1. Owner pilot mua Small; nhận signed SePay receipt offline và chỉ cấp một entitlement.
2. Đăng 4 suất có pending; suất thứ 5 bị chặn, public chỉ thấy xe đã duyệt.
3. Đúng mốc hết hạn: public ẩn, hồ sơ/ý định đăng và booking cũ giữ nguyên. Gia hạn cùng loại phục hồi đúng lựa chọn.
4. Business hết hạn → Small: toàn fleet ẩn; Owner tự chọn 4, xe thứ 5 bị chặn. Không đổi approval hoặc booking cũ.
5. Receipt trùng/late dẫn đúng refund Owner từ nguồn Admin; thiếu tiền giữ WAITING_FUNDS và cảnh báo quá hạn, không giả SUCCEEDED.
6. Owner ngoài pilot bị từ chối dù đã đăng nhập. Gỡ pilot: QR đang ACTIVE thành UNAVAILABLE, không xóa nghĩa vụ/intent; cancel và xử lý tiền đã đến vẫn hoạt động.
7. Native forms/CSRF/IDOR, desktop/mobile, JAR/source manifest và fingerprint DB ứng dụng đối chiếu trước chốt.

Runner local: `java '@.scratch/phase8/p88/full-suite.args'`. Runner dùng Maven Wrapper với POM/output và datasource schema tạm, `-Dmaven.compiler.useIncrementalCompilation=false -Dtest=*Test clean verify`, rồi dọn schema. Không chuyển tiền thật hoặc chứng minh gate live.

## 4. Khi đủ gate thật và được cho phép

1. Người vận hành xác nhận review và backup/restore/migration theo README, đối chiếu source/JAR đã duyệt. Không chạy lại bootstrap trên DB ứng dụng.
2. Cấu hình riêng một Owner/Admin và channel references đúng chủ thể; giữ disabled cho chủ thể khác. Kiểm tra API/lookup/nguồn tiền bằng bằng chứng P4.2-A, không đưa secrets vào báo cáo.
3. Đặt cặp pilot bằng ID thật đã duyệt; chỉ bật các flags/provider/scheduler đã được cho phép sau khi đủ gate Phase6/8. Không tự bật mọi thứ vì đã đặt ID.
4. Người dùng chủ động thực hiện giao dịch kiểm chứng đã xác định. Ghi provider IDs đã che dữ liệu nhạy cảm, tiền/thời điểm, trạng thái DB, entitlement và đối soát refund. Duplicate phải là hai giao dịch ngân hàng khác nhau, không chỉ replay webhook.
5. Late order không cấp gói, hoàn đủ theo policy. Chờ bằng chứng tiền thực sự được hoàn/đối soát; READY/PROCESSING/WAITING_FUNDS chưa phải thành công.
6. Chỉ tick gate live và đóng P8.8 khi reviewer xác nhận đủ bằng chứng. Thiếu một gate bắt buộc thì giữ pending.

## 5. Dừng mở checkout và xử lý sự cố

- Gỡ cặp pilot theo quy trình cấu hình/triển khai được cho phép sẽ chặn mở/reuse và ẩn QR ACTIVE. Không thay persisted intent/order, không xóa debt/receipt/refund hoặc entitlement đã cấp.
- QR đã chụp hoặc mã chuyển khoản đã biết không thể bị thu hồi bởi giao diện. Receipt đến sau vẫn phải được ghi nhận/apply/refund đúng lifecycle; không chặn webhook hoặc đối soát chỉ vì đã gỡ pilot.
- Không tắt worker/handler một cách làm mất xử lý tiền đang pending. Nếu cần dừng một phần, người vận hành đánh giá receipt/refund/UNKNOWN hiện tại và giữ đường đối soát an toàn. UNKNOWN chỉ query, không tự gửi lệnh chi mới.
- Không đổi nguồn kênh chi đã snapshot để xử lý thiếu tiền; Admin thiếu tiền vẫn WAITING_FUNDS/notify Admin, không lấy tiền Owner. Ghi evidence và liên hệ người vận hành theo quy trình dự án.
- Migration thất bại: dừng, kiểm tra schema partial và phục hồi theo backup được duyệt; không tự drop bảng, chạy `--force`, seed lại hoặc downgrade DB.
