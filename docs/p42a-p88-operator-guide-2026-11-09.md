# P4.2-A/P8.8 — cấu hình lookup và nghiệm thu vận hành

Hướng dẫn ngày **2026-11-09**, đi cùng [kế hoạch triển khai](plans/p42a-p88-implementation-2026-11-09.md) và [contract mới](plans/p42a-p88-implementation-contract-2026-11-09.md). Không tự bật payout, không phải bằng chứng đã hoàn tiền thật.

## 1. Người nhận xác nhận từng khoản hoàn như thế nào?

1. Nghiệp vụ tạo khoản hoàn; người nhận mở `/payment/refunds/{id}`. Customer nhận hoàn tiền thuê từ Owner; Owner nhận hoàn phí gói từ Admin.
2. Form điền sẵn tài khoản mặc định nếu người nhận đã chọn lưu trước đó. Người nhận có thể chọn ngân hàng/STK khác khi refund chưa bắt đầu xử lý.
3. Bấm xem trước: backend tra tên từ đúng BIN/STK; bước này không ghi READY hoặc phát lệnh chi. Lookup không có tên đáng tin thì dừng.
4. Người nhận kiểm tra tên và chọn xác nhận. Khi POST xác nhận, backend tra lại cùng BIN/STK; tên đổi hoặc thông tin/quyền/trạng thái đã thay đổi thì yêu cầu xem lại.
5. Xác nhận đúng mới lưu snapshot đích trên refund, trạng thái READY và hạn xử lý lần đầu +1giờ. Lưu tài khoản mặc định chỉ là opt-in; sửa tài khoản sau đó không reset hạn.
6. Worker chỉ phát lệnh khi nguồn chi được cấu hình/kích hoạt theo phạm vi đã duyệt; tạo attempt bền vững trước network. Đổi tài khoản mặc định không sửa attempt/refund đang xử lý. READY/PROCESSING chưa có nghĩa người nhận đã được hoàn.

Owner/Admin không có bước bấm duyệt từng refund trong ứng dụng hiện tại; họ phải duy trì đúng nguồn tiền. Điều kiện OTP/duyệt ở PayOS phải được xác nhận riêng theo kênh.

## 2. Các trường cần người vận hành cung cấp

Không gửi credential, STK/tên người nhận, OTP hoặc mật khẩu trong chat. Điền trực tiếp vào file private dưới `.scratch/` hoặc file local được bảo vệ. Có thể chỉ thông báo đường dẫn và tình trạng đã điền.

| Nhóm | Trường và ý nghĩa |
| --- | --- |
| Lookup, bắt buộc để gọi thật | Xác nhận entitlement áp dụng cho `https://api.vietqr.io/v2/lookup`, evidence/path; `CARRENT_REFUND_LOOKUP_CLIENT_ID`, `CARRENT_REFUND_LOOKUP_API_KEY`. Đây là khóa VietQR lookup riêng, không suy dùng lại khóa payout PayOS. |
| Một lượt kiểm tra lookup | `CARRENT_REFUND_LOOKUP_CHECK_CHANNEL_ID`, `..._BANK_BIN`6 số, `..._ACCOUNT_NUMBER`chỉ số6–19 giữ0 đầu, `..._EXPECTED_NAME`tên chuẩn kỳ vọng của người nhận được phép kiểm tra. |
| Pilot và nguồn chi | ID Owner/Admin CarRent và PAYOS/PAYOUT channel tương ứng; quyết định nguồn chung hay riêng được duyệt. ID hiện có phải đọc từ DB, không lấy ID ngân hàng hoặc provider account thay userId. |
| Capability kênh chi | Evidence quyền POST, auto-approval/OTP/duyệt tay, min/max/phí, principal và credit dịch vụ. Cần xử lý được hoàn gói Small5.000đ và các khoản lẻ trong scope;5.000đ là giá ứng dụng, không phải minimum provider đã xác nhận. |
| Nghiệm thu thật | Người nhận Owner và Customer trong file private; khung thời gian, tổng tiền tối đa và phí tối đa được duyệt; người dùng chủ động chuyển các giao dịch ngân hàng đã chốt. |
| Đối soát/review | Evidence SePay webhook↔API v1 cùng giao dịch; reviewer và evidence duyệt đúng source/JAR/schema/restore. Không đóng gate bằng file đánh dấu true tự khai. |

Hai file đã chuẩn bị tại workspace hiện tại:

Read-only ngày09/11 xác nhận Owner user2/channel1 và Admin user1/channel2, hai reference đang dùng chung credentials; hai kênh PAYOUT trong DB vẫn tắt. Biểu mẫu đã điền các ID này và ghi `SHARED_CURRENT_CONFIGURATION_SCOPE_UNVERIFIED`; cần evidence quyền/phạm vi dùng chung cho pilot, không coi hai reference là hai ví độc lập. Hai signed GET `/v1/payouts` và `/v1/payouts-account/balance` hiện trả HTTP403. Chưa xác định nguyên nhân, chưa chứng minh quyền POST/activation hoặc tiền nguồn.

- `.scratch/p42a-p88-implementation-20261109/operator-lookup.properties`: nhập lookup keys và một đích kiểm tra. Mặc định `CARRENT_REFUND_LOOKUP_ENABLED=false`; chỉ chuyển true sau khi entitlement được xác nhận.
- `.scratch/p42a-p88-implementation-20261109/operator-acceptance.json`: các thông tin/phê duyệt/evidence còn thiếu. Đây là biểu mẫu, **không có code đọc file để tự bật payout**; null/blank/UNDECIDED là chưa đủ.

Không cần nhập lại khóa PayOS đã được cấu hình nếu kiểm tra hiện tại xác nhận vẫn hợp lệ; signed GET và quyền POST là hai bằng chứng khác nhau. IP egress/allowlist có thể đổi từ lần kiểm tra trước.

## 3. Chạy kiểm tra lookup không chuyển tiền

Chỉ sau khi đã điền file private và có quyền lookup, dùng JAR đã build theo manifest trong bàn giao và được review; không chọn `.jar.original`. Chạy từ root workspace hiện tại:

```powershell
java -jar 'C:/Users/Admin/Documents/hoc/Car/.scratch/p42a-p88-implementation-20261109/build/carrent-0.0.1-SNAPSHOT.jar' '--spring.profiles.active=refund-lookup-check' '--spring.config.additional-location=file:./.scratch/p42a-p88-implementation-20261109/operator-lookup.properties'
```

Profile này bind loopback/port0, initSQL never/DDL none, tắt financial/domain jobs; scheduler bị loại bằng profile ngay cả khi nguồn cấu hình khác ép bật. Runner vẫn từ chối nếu effective maintenance/deadlines=true. Lookup chỉ POST endpoint tra tên, đọc metadata source rồi so tên chuẩn; không tạo refund/attempt, không enable kênh, không gọi payout. Output thành công chỉ `Recipient lookup VERIFIED. No payout was created or activated.`; không in tên/STK/key. Main đóng context sau khi hoàn tất diagnostic.

Lỗi được báo chung để không lộ dữ liệu riêng. Kiểm tra lại entitlement/quyền, private inputs và IP/network trong môi trường vận hành; không tắt TLS/signature hoặc cho redirect để làm check pass. Không chạy bằng cách gắn profile lookup vào app8080 đang phục vụ người dùng.

## 4. Recovery cho UNKNOWN/PROCESSING/REVIEW_REQUIRED

Phân công một người vận hành tài chính cho mỗi nguồn Owner/Admin và một reviewer. Trước pilot ghi rõ thời hạn phản hồi trong `liveAcceptanceWindow`/biên bản vận hành; mốc1giờ là hạn xử lý/cảnh báo nghiệp vụ, không tự chứng minh ngân hàng thất bại.

| Trạng thái | Hành động đúng |
| --- | --- |
| WAITING_RECIPIENT | Người nhận xác minh và xác nhận đích. Không dùng tên tự khai hoặc SQL đặt READY. |
| READY nhưng không chạy | Đối chiếu đúng nguồn và provider reference, enabled flags/maintenance, quyền và cấu hình bản đang chạy. Không đổi sang nguồn của user khác. |
| WAITING_FUNDS | Chỉ hợp lệ khi có outcome chắc chắn chưa chuyển. Xác định thiếu principal hay credit, nạp đúng nguồn; worker mới có thể retry theo contract. |
| UNKNOWN/PROCESSING | Giữ attempt/reference/key/đích/số tiền; query cùng payout ID hoặc reference, đối chiếu provider/ngân hàng. Timeout/HTTP lỗi/list rỗng không cho phép tạo attempt mới. |
| REVIEW_REQUIRED | Auto-reconcile đã dừng theo thiết kế; thu evidence snapshot/source mismatch, phân công người xử lý và reviewer. Không tự UPDATE status SUCCEEDED hoặc sửa đích/attempt. |
| Source bị vô hiệu hóa | Xác minh lý do và thông tin nguồn gốc trước khi re-enable có phê duyệt. Khi nguồn hợp lệ được phục hồi, đối soát đúng attempt cũ. |
| Provider không có kết luận đủ chắc chắn | Giữ khoản chưa hoàn thành, liên hệ support theo quy trình được người vận hành thực hiện/ủy quyền; không ghi đã hoàn hoặc phát lại. Ghi giới hạn này vào nghiệm thu query-only. |

Adapter PayOS hiện chưa tạo NOT_SENT; giữ UNKNOWN query-only là lựa chọn an toàn được checklist P4.2-A cho phép. Không bắt buộc thêm endpoint replay hoặc giả lập phân loại lỗi chưa có contract.

SQL sau chỉ đọc, dùng đúng refundId sau khi có khoản thật;0 là giá trị không chọn khoản nào. Không query thông tin ngân hàng/credential hoặc xuất raw payload ra báo cáo:

```sql
SELECT r.id, r.status, r.amount, r.payout_channel_id, r.ready_at, r.due_at,
       a.id AS attempt_id, a.attempt_no, a.status AS attempt_status,
       a.reference_id, a.provider_payout_id, SHA2(a.idempotency_key,256) AS key_fingerprint,
       a.submitted_at, a.next_reconcile_at, a.last_error
FROM refunds r LEFT JOIN payout_attempts a ON a.refund_id=r.id
WHERE r.id=0 ORDER BY a.attempt_no;
```

Khi cần một thao tác khắc phục ghi DB, phải có phương án cụ thể theo evidence provider và review riêng. Hướng dẫn này không cấp quyền sửa trạng thái tiền bằng tay.

## 5. P8.8 và hai nguồn: thứ tự nghiệm thu

1. Review source/JAR/schema đúng manifest; sử dụng evidence restore hiện có và kiểm tra môi trường mục tiêu. Không chạy lại migration one-shot chỉ để tick checkbox.
2. Chốt Owner/Admin pilot và chính sách nguồn tiền. Cấu hình participant ngoài pilot vẫn bị từ chối checkout theo backend. Chỉ kích hoạt đúng scope khi có phê duyệt cụ thể.
3. Mua Small: người dùng thực hiện hai giao dịch ngân hàng **khác transaction ID** cho cùng một order/intent theo kịch bản được duyệt. Không replay webhook để thay chuyển thật.
4. Đối chiếu SePay webhook/v1 của từng giao dịch; một lần apply/gia hạn, một receipt trùng sinh refund từ nguồn Admin. Owner xác nhận tài khoản; chi và đối soát tiền vào đích.
5. Late order: giao dịch sau deadline theo backend không cấp gói, hoàn đủ từ Admin; ghi rõ thời gian/order/receipt/refund/attempt/provider IDs.
6. Nguồn Owner: kịch bản rental đã chốt như hủy trước giao hoặc nhận dư/trùng, refund về Customer từ đúng Owner; tiền vào đích và không chi trùng sau reconcile/restart. Không suy ca Admin thành bằng chứng nguồn Owner.
7. Thu evidence đã che PII: environment, source/JAR hash, mapping IDs, transaction/receipt/order/refund/attempt/provider IDs, amount/time, kết quả tiền vào đích và review. Ghi riêng fee/service-credit/funding nếu áp dụng.
8. Chỉ đóng từng checkbox P4.2-A/P8.8 sau khi đúng evidence và reviewer xác nhận. Code/local full verify không tự đóng giao dịch thật, quyền provider hoặc review.

Nếu lookup chưa có entitlement hoặc quyền chi/đích/ngân sách chưa được chốt, giữ payout/job tắt; tiếp tục hoàn thiện code/test/evidence kỹ thuật. Không yêu cầu gửi khóa hay OTP qua chat để vượt gate.
