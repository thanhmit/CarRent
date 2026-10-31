# CarRent — Kế hoạch Queue, thanh toán thật và gói Owner

**Ngày chốt:** 2026-10-27; cập nhật 2026-10-31. **Trạng thái:** P6.2 hoàn thành policy VIETQR_V1 30/70 và phí hủy, cùng guard backend chặn hủy sau giao xe. Full isolated clean verify **456 JUnit test đạt, 0 failure/error/skip**, gồm **42 policy + 7 guard/HTTP/DB test mới**; JAR đóng gói thành công. DB ứng dụng vẫn 7 bảng, fingerprint schema/toàn bộ rows giữ nguyên; không migration hoặc bật workflow/provider/scheduler mới. **57/86 task**, còn 29; tiếp theo **P6.3 — nháp và ảnh private**. P4.2-A tiếp tục tạm hoãn; chưa ghi nhận provider live hoặc human review mới.

**Spec:** các quyết định brainstorming được tổng hợp tại file này; [SDD](../SDD.md) §1.4–1.6 lưu backlog và phạm vi tiền thật. Những phần mock payment/inspection cũ trong SDD và D-04/D-06/D-07/D-12/D-25 là thiết kế hoặc checkpoint trước lần cập nhật này. Khi thực hiện các phase bên dưới, dùng hợp đồng mới tại đây cho workflow mới; giữ bằng chứng lịch sử và hành vi legacy. Đồng bộ các chương chi tiết của SDD trước khi kích hoạt workflow mới.

## 1. Thứ tự thực hiện

Giữ số phase đã tồn tại để không đổi định danh task lịch sử. Số phase không phải thứ tự chạy:

| Thứ tự | Plan | Đầu ra |
|---|---|---|
| 1 | [Phase 7 — Queue](phase-7-car-queue.md) | FIFO theo carId, request đợi kết quả, transaction bắt đầu khi đến lượt |
| 2 | [Phase 4 — Thanh toán thật](phase-4-payment.md) | SePay, nghĩa vụ thanh toán, inbox bền vững, polling, payOS và hoàn tiền |
| 3 | [Phase 6 — Thuê xe và biên bản](phase-6-inspection.md) | BEFORE/AFTER, 30/70, hợp đồng, giao/trả xe, hủy và các hạn 1 giờ |
| 4 | [Phase 8 — Gói Owner](phase-8-owner-subscriptions.md) | Gói 30 ngày, hạn mức, gia hạn/hết hạn, thanh toán và hoàn từ Admin |
| 5 | [Phase 5 — Admin và hoàn thiện](phase-5-admin-polish.md) | Dashboard, quản lý tài khoản/profile, đối soát và E2E cuối |

Phase 0–3 và **P4.1 đã hoàn thành** được giữ nguyên lịch sử. P4.2 trở đi, Phase 5 và Phase 6 được lập lại theo yêu cầu mới. Không chạy lại P4.1 và không đánh dấu bất kỳ task mới nào hoàn thành chỉ vì có plan.

**Tiến độ hiện tại:** **57/86 task hoàn thành, còn 29**. P6.1–P6.2 đã đóng theo [checkpoint P6.2](phase-6-inspection.md#checkpoint-p62--2026-10-31); tiếp theo P6.3. P4.2-A tạm hoãn; không suy live/human review từ test. [Handoff tài chính](phase-4-handoff.md).

### Phân công ba thành viên

**Phase 4 chia 7 đợt theo cập nhật được duyệt 2026-10-30:** P4.2 → P4.3 → (P4.4 + P4.5 + P4.6) → P4.7 → P4.8 → (P4.9 + P4.10) → P4.11. Xem [checkpoint từng đợt](phase-4-payment.md#7-đợt-triển-khai--cập-nhật-được-duyệt-2026-10-30) và SDD §14.1; từng task vẫn kiểm chứng riêng, gate live không được suy ra từ test offline.

**Checkpoint 2026-10-29:** đợt 1 đạt đầu ra kỹ thuật/offline, verify lại 86 test không cần DB đạt và JAR đóng gói thành công; lần clean đầu có một timeout test queue, ghi cả hai lần ở [checkpoint đợt 1](phase-4-payment.md#checkpoint-đợt-1--p42-kỹ-thuậtoffline-2026-10-29). P4.2 còn gate provider/live, giữ 45/85; tiếp theo đợt 2/P4.3.

**Quyết định lịch sử sau checkpoint P4.2:** chuyển nguyên gate tài khoản/provider/live sang P4.2-A; tiến độ khi tách là 46/86, còn 40. Kế hoạch 8 đợt lúc đó đã được thay bằng 7 đợt được duyệt ở trên. P4.2-A chạy theo điều kiện tài khoản và các phần code cần kiểm chứng; P4.11 tổng hợp trạng thái, chưa đạt thì không bật live.

Đã kiểm tra ba contributor trực tiếp trên GitHub và phân công tại [team-assignment.md](team-assignment.md):

| Thành viên | Task mới phụ trách | Số task |
|---|---|---:|
| `thanhmit` | P7.1–P7.4; P4.2–P4.11; P4.2-A | 15 |
| `huy69332` | P6.1–P6.13; P5.5–P5.6 | 15 |
| `ducbinhnguyen07ars` | P8.1–P8.8; P5.1–P5.4; P5.7–P5.9 | 15 |

Mỗi task mới có trường **Phụ trách** và **Review**. File phân công cũng ghi bằng chứng phần đã làm và trách nhiệm bảo trì được bàn giao từ nay; không đổi tác giả lịch sử. Thứ tự 7 → 4 → 6 → 8 → 5 là thứ tự tích hợp/đạt gate; có thể chuẩn bị task độc lập hoặc nhánh/UI/test song song theo các đợt ở file phân công, chưa bật luồng live trước gate.

## 2. Phạm vi và luồng tiền đã chốt

- Ứng dụng local, một tiến trình Spring Boot. Kiểm chứng tiền thật với **một Owner + Admin trước**; cấu hình gắn đúng chủ thể, không hardcode mọi Owner dùng chung một tài khoản nhận.
- Customer → Owner: tiền thuê qua **SePay VietQR**, xác nhận bằng webhook. Customer không cần tài khoản SePay.
- Owner → Customer: tự động hoàn qua **API payOS**, dùng nguồn tiền ví Bảo Kim/kênh chi của chính Owner. Customer nhập tài khoản nhận khi phát sinh hoàn; có thể chọn lưu lại. Admin dùng bộ payOS/ví riêng khi hoàn tiền gói.
- payOS cung cấp API tạo/query payout; ví Bảo Kim là tiền nguồn theo kênh chi đang được tài liệu mô tả, nạp riêng với credit dịch vụ payOS. Tiền nhận qua SePay không tự được CarRent chuyển vào ví. P4.2-A xác minh kênh thực tế; không đổi nhà cung cấp hoặc tự thêm tích hợp API Bảo Kim.
- Owner → Admin: phí gói qua **SePay VietQR** vào ngân hàng Admin.
- Admin → Owner: tự động hoàn khoản mua gói bị trả trùng hoặc thực sự đến sau hạn, dùng ví/kênh chi của Admin.
- Backend đối soát payout bằng API; trình duyệt polling CarRent. Không giả định webhook thu tiền SePay là webhook kết quả chi tiền; không bắt người nhận xác nhận thủ công hoặc upload biên lai.
- Chỉ báo hoàn thành hoàn tiền khi nhà cung cấp xác nhận chuyển thành công. HTTP chấp nhận yêu cầu hoặc trạng thái đang xử lý chưa phải kết quả cuối.

**Gate nhà cung cấp:** phải kiểm chứng kênh nhận/chi thật, quyền gọi API, IP/callback, ngân hàng hỗ trợ, hạn mức/phí/số tiền tối thiểu, cơ chế idempotency và trạng thái cuối trước khi bật tiền thật. Tài liệu payOS có đích nhận ngân hàng/số tài khoản và truy vấn payout; quyền CarRent gọi thay Owner, kích hoạt và cấp vốn vẫn cần xác nhận thực tế. Sandbox thành công không chứng minh live được phép. Không thêm SePay refund API làm phương án mặc định: tính năng đó có điều kiện VietinBank doanh nghiệp riêng.

## 3. Quy tắc thuê xe đã chốt

### 3.1. Giá và lịch

- Chỉ có **ngày nhận/ngày trả**. Hai bên tự hẹn giờ ngoài ứng dụng.
- `days = DAYS.between(startDate, endDate)`, tối thiểu 1 ngày; tổng tiền là giá/ngày snapshot × số ngày. Không đổi sang đơn vị 24 giờ, không tính thêm phụ phí/bồi thường trong bản này.
- Trả trước **30%**, sau trả xe **70%** của tổng đã chốt. Đây là tiền thuê trả trước, không phải tiền bảo đảm hư hỏng.
- Mặc định kỹ thuật: làm tròn xuống phần 30% tới VND; phần còn lại = tổng − phần trả trước để tổng không lệch. Chặn tạo yêu cầu tiền thật nếu một phần nhỏ hơn 1 VND hoặc dưới mức tối thiểu đã xác minh của nhà cung cấp; dữ liệu legacy không bị sửa theo quy tắc này.
- Thời điểm hệ thống dùng UTC/Instant; hiển thị và chuyển ngày lịch bằng `Asia/Ho_Chi_Minh`. Dùng `Clock` để kiểm thử biên, không phụ thuộc múi giờ của máy chạy.

### 3.2. Trước và trong chuyến đi

1. Customer tạo yêu cầu; Owner chấp nhận và gửi ảnh/text BEFORE.
2. Customer đồng ý BEFORE; hệ thống tạo yêu cầu trả trước 30%, giữ xe **15 phút** từ lúc backend tạo yêu cầu. Reload không gia hạn.
3. Nhận đúng khoản hợp lệ trong hạn → xác nhận đặt xe.
4. Trong khoảng ngày thuê, Owner tích **“Tôi xác nhận đã ký hợp đồng thuê xe với khách hàng”** rồi bấm **“Xác nhận đã giao xe”** → hiển thị **Đang thuê**. Không cần Customer xác nhận nhận xe lần nữa.
5. Owner phải là chủ xe, BEFORE đã được Customer đồng ý, đã nhận đủ 30%, xe không còn ở booking khác và ngày hiện tại thuộc `[startDate, endDate)`. Không tự suy ra giao xe từ lịch.
6. Từ lần Owner xác nhận giao xe: chặn hủy cho cả hai bên. Lưu actor và thời điểm server một lần; không bỏ tích để đảo ngược bàn giao. Checkbox là xác nhận của Owner về hợp đồng ký ngoài ứng dụng, chưa phải chữ ký điện tử hoặc bằng chứng nội dung hợp đồng.

### 3.3. Trả xe và 70%

- Customer gửi ảnh/text AFTER. Việc gửi báo cáo chưa có nghĩa Owner đã nhận xe.
- Sau **1 giờ từ lần gửi AFTER** mà Owner chưa xác nhận: nhắc Owner, thông báo Admin; không tự xác nhận nhận xe và không tự phát sinh hạn nợ 70% chỉ vì hết giờ chờ.
- Owner xác nhận nhận lại xe thực tế, đồng thời chọn đồng ý hoặc có bất đồng về tình trạng. Bất đồng cần lý do và được lưu; không tự đổi giá thuê.
- Khi Owner xác nhận nhận lại xe: tạo nghĩa vụ 70%, hạn **1 giờ**. Nhắc Customer và hiển thị khoản phải trả.
- QR 70% có hạn hiển thị **15 phút**. Hủy/để hết hạn QR chỉ kết thúc lần thử; khoản nợ và hạn 1 giờ giữ nguyên. Được tạo QR mới. Tiền đến qua QR cũ vẫn đối soát với khoản còn nợ; khoản trả trùng/vượt số còn nợ được hoàn phần dư.
- Quá hạn 70%: **chỉ cảnh báo** Customer, Owner, Admin; không khóa tài khoản hoặc chặn tạo đơn mới. Không tự phạt, tự hủy hay hoàn lại 30%.
- Đã trả xe và nhận đủ tiền → tự chuyển COMPLETED; bất đồng AFTER còn lại được giữ làm ghi chú/bằng chứng, không chặn hoàn thành.
- Xe đã trả không còn bị giữ về vật lý bởi khoản nợ. Tìm kiếm/đặt xe vẫn kiểm tra khoảng ngày trùng theo quy tắc hiện hành, không bỏ qua overlap chỉ vì có timestamp trả sớm.

### 3.4. Hết hạn, hủy và thứ tự nhận webhook

- Hết 15 phút chưa xác nhận khoản trả trước: EXPIRED, giải phóng lịch, ẩn QR và hiện thông báo hết hạn.
- Customer bấm **Hủy đặt xe** ở trang trả trước: hủy yêu cầu và booking, giải phóng lịch ngay. Nếu tiền đến sau đó thì hoàn 100%.
- Tiền trả trước thực sự đến sau hạn: không khôi phục đơn; hoàn 100%, không thu phí hủy.
- Ngân hàng nhận đúng hạn nhưng webhook đến chậm: phân biệt bằng thời điểm ngân hàng đã xác minh; không gọi đó là Customer trả muộn. Nếu lịch đã được giữ cho B, giữ B và hoàn đủ cho A, ghi lý do hệ thống xác nhận chậm. Không áp dụng “ai thanh toán trước giành xe”.
- Mặc định triển khai bảo thủ: khi EXPIRED đã commit thì không tự phục hồi đơn, kể cả chưa có B; nhận khoản đúng hạn sau đó tạo hoàn 100% với lý do xác nhận chậm. Điểm mặc định này phải được đọc khi review plan, không diễn giải thành ưu tiên giờ chuyển tiền.
- Trước khi QR trả trước được tạo, giữ hành vi PENDING chặn lịch hiện có; chưa thêm timeout mới cho thời gian Owner chuẩn bị BEFORE/Customer xem báo cáo. Đây là giới hạn v1, khác thời hạn giữ chỗ 15 phút khi đã tạo QR.

### 3.5. Phí hủy trước bàn giao

Áp dụng sau khi đã ghi nhận khoản trả trước hợp lệ; snapshot tiền và chính sách vào booking để sửa cấu hình sau này không đổi lịch sử:

| Bên hủy / thời điểm | Phí trên khoản trả trước |
|---|---:|
| Customer hủy trong 1 giờ sau thanh toán giữ chỗ | 0% |
| Sau 1 giờ; ngày nhận cách ngày hủy hơn 3 ngày | 10% |
| Sau 1 giờ; cách 0–3 ngày, hoặc đã qua ngày nhận nhưng chưa bàn giao | 50% |
| Owner hủy trước bàn giao | 0% |

Ưu tiên cửa sổ miễn phí. Mặc định biên: đúng 1 giờ vẫn miễn phí; đúng 3 ngày thuộc mức 50%. Mốc thanh toán lấy thời điểm ngân hàng ghi nhận khoản hợp lệ, không lấy thời điểm trình duyệt thấy thành công. Phí làm tròn xuống VND; tiền hoàn = tiền trả trước − phí. Không tính mốc 72 giờ theo giờ hẹn ngoài ứng dụng.

### 3.6. Hoàn tiền

- Nghĩa vụ hoàn được tạo tự động khi điều kiện đã xác định, không chờ Owner bấm nút hoàn.
- Chưa có tài khoản nhận → WAITING_RECIPIENT; người nhận nhập ngân hàng/số tài khoản, hệ thống kiểm tra và hiển thị tên đích mà nhà cung cấp xác minh được. Không coi tên tự nhập là chứng minh sở hữu tài khoản.
- Khi đã xác định số tiền và có tài khoản hợp lệ: ghi `readyAt` một lần, hạn hoàn `readyAt + 1 giờ`. Chờ người nhận cung cấp tài khoản chưa tính vào hạn này.
- Thiếu số dư kênh chi → WAITING_FUNDS, thông báo bên phải cấp vốn, tự kiểm tra/thử lại. Sau 1 giờ chưa thành công: đánh dấu quá hạn, thông báo các bên/Admin; **chỉ cảnh báo**, không chặn Owner nhận đơn mới.
- Không reset hạn khi retry, reload, app restart hoặc sửa dữ liệu hiển thị. Dùng cùng cơ chế trạng thái và đối soát cho khoản Admin hoàn Owner; đây là mặc định tái sử dụng hạ tầng của plan.
- Không gọi lại lệnh chi mới chỉ vì timeout. Mỗi lần chi có provider ID/idempotency key; kết quả chưa rõ phải truy vấn lần đó. Chỉ tạo lần thử mới sau khi có kết quả chắc chắn chưa chuyển và lỗi được phép thử lại.

## 4. Gói Owner đã chốt

| Gói | Hạn mức | Giá tạm thời / 30 ngày |
|---|---:|---:|
| SMALL | 4 xe | 5.000 VND |
| STANDARD | 8 xe | 10.000 VND |
| BUSINESS | 16 xe | 15.000 VND |

- Snapshot giá/hạn mức vào yêu cầu mua. Thay bảng giá không đổi yêu cầu đã tạo.
- Một lần mua hợp lệ được kích hoạt sau xác nhận tiền. Còn hạn và mua cùng gói: cộng **30 ngày vào hạn hiện tại**; hết hạn: từ lúc kích hoạt lại + 30 ngày. Dùng cùng một transaction để ghi đã áp dụng và cộng hạn.
- Chỉ đổi loại gói sau khi gói hiện tại hết hạn; không nâng/hạ giữa kỳ, không quy đổi tiền/ngày còn lại.
- Hạn mức = xe **đang đăng + đang chờ duyệt**; xe chủ động ẩn hoặc bị từ chối không chiếm suất.
- Hết gói: tạm ẩn khỏi public, chặn đơn thuê mới và đăng thêm xe; Owner vẫn xem/sửa, đơn đã xác nhận vẫn thực hiện và thanh toán/hoàn bình thường. Chưa thêm quy tắc tự hủy các yêu cầu thuê tồn tại chỉ vì gói hết hạn; chặn tạo mới tại thời điểm submit.
- Gia hạn cùng gói: phục hồi xe đủ điều kiện từng được đăng, không bật lại xe Owner đã chủ động ẩn. Mua gói thấp hơn sau hết hạn: giữ dữ liệu, tất cả xe tiếp tục ẩn; Owner tự chọn bật/gửi duyệt trong hạn mức mới. Gói cao hơn có thể phục hồi lựa chọn cũ trong hạn mức mới.
- Phí gói đã kích hoạt: **không hoàn theo ngày/suất chưa dùng**, không tự trừ tiền kỳ sau.
- QR mua/gia hạn: **15 phút**, hết hạn thì ẩn và cho tạo mới; gói cũ còn hiệu lực không bị ảnh hưởng.
- Hai lần chuyển tiền thật cho cùng yêu cầu: lần hợp lệ đầu chỉ cấp quyền lợi một lần, khoản trả trùng hoàn tự động 100% từ Admin. Webhook gửi lại một giao dịch chỉ dedupe, không tạo khoản hoàn mới.
- Tiền gói thực sự đến sau hạn: không kích hoạt yêu cầu hết hạn, hoàn tự động 100% từ Admin. Đúng hạn nhưng webhook chậm đối soát riêng; chỉ áp dụng nếu không vi phạm quy tắc gói hiện tại.

## 5. Các mặc định kỹ thuật để người triển khai không tự suy đoán

Các giá trị dưới đây là lựa chọn triển khai có thể review, không phải yêu cầu kinh doanh mới:

- Queue: 4 worker dùng chung; tối đa 50 tác vụ chờ/car, 500 tác vụ chờ toàn app; thời gian chờ trước bắt đầu 10 giây. Chỉ hủy tác vụ còn QUEUED. Tác vụ đã RUNNING phải chờ kết quả commit/rollback; không báo đã thất bại rồi để nó âm thầm commit. Đặt timeout DB để transaction không treo vô hạn.
- Polling UI: 2 giây khi tab đang mở, dừng ở kết quả cuối và khi trang bị đóng; GET chỉ đọc. Backend đối soát chi tiền độc lập với trình duyệt.
- Job DB: quét hết hạn/nhắc nợ mỗi 30 giây, lưu mốc xử lý để mỗi loại nhắc chỉ phát một lần. Sau restart quét bù. Payout đang xử lý truy vấn mỗi 30 giây; lỗi hạ tầng dùng backoff tối đa 5 phút, không gửi lệnh chi lặp.
- Yêu cầu mua gói bị hủy trước kích hoạt: kết thúc intent; tiền nhận sau đó tạo hoàn 100% từ Admin, không cấp gói. Đây là mặc định tương ứng với yêu cầu hết hạn, cần review cùng vòng đời mua gói.
- Nếu tiền gói đúng hạn nhưng xử lý sau khi một gói khác đã được kích hoạt và loại gói không còn hợp lệ, tạo hoàn 100% thay vì ghi đè quyền lợi hiện tại. Chặn tạo nhiều yêu cầu mua gói ACTIVE của cùng Owner từ đầu.
- Giao dịch không xác định được mã hoặc có lệch tiền thuộc phần hoãn: lưu receipt/inbox ở REVIEW_REQUIRED, hiển thị cho đúng chủ thể; không tự đánh dấu đủ tiền, không làm mất tiền đã nhận. Chưa xây màn hình đối soát thủ công để giả lập “đã thanh toán”.

## 6. Hợp đồng kỹ thuật xuyên phase

### 6.1. Biên module và giao diện

**Handoff nghiệm thu P4.11:** [interface thực tế, handler registration, money states, sequence và cutover](phase-4-handoff.md); dùng trước khi nối Phase 6/8.

Bảng dưới gồm các module đã triển khai và các đích dự kiến ở task/phase tiếp theo; trạng thái cụ thể theo checkpoint từng task. Package gốc `com.carrent`.

**Cập nhật contract đợt 3 — 2026-10-30:** P4.4–P4.6 đã được kiểm chứng theo các seam dưới đây. Core tạo/allocation receipt và cập nhật amountSettled/status tài chính; handler `apply(receiptId)` đọc kết quả đã flush để áp dụng quyền lợi domain và yêu cầu refund cho phần chưa phân bổ. Handler không cộng tiền lần nữa, không mở transaction/queue lồng và không gọi mạng provider. P4.7 đã bổ sung RefundService/RefundRecipientService, recipient MVC và saved-account prefill; P4.8 đã bổ sung PayoutWorker/PayoutTransactions/PayoutChannelResolver: durable attempt/lease trước HTTP, đối soát query-only khi UNKNOWN và retry có phân loại; nguồn kênh bất biến theo vận hành, không repoint credential reference có lịch sử. P4.9 đã thêm scheduler quét DB 30 giây, bounded executors và cảnh báo atomic, mặc định tắt; endpoint webhook chỉ lưu inbox trước ACK, không khởi chạy worker trong request. P4.10 thêm scoped financial views và polling; không cấp quyền sửa đích cho collector/Admin.

| Module | Trách nhiệm / giao diện chính |
|---|---|
| `concurrency/CarCommandExecutor` | `<T> T execute(Long carId, Supplier<T> action)`; xếp tác vụ trước transaction, trả kết quả sau commit/rollback |
| `payment/PaymentObligationService` | `PaymentIntentView openAttempt(Long obligationId, CustomUserPrincipal actor)`; `void cancelAttempt(Long intentId, CustomUserPrincipal actor)`; không quyết định hủy booking |
| `payment/SePayWebhookService` | `void receive(Long channelId, HttpHeaders headers, byte[] rawBody)`; xác thực rồi ghi inbox bền vững trước ACK |
| `payment/ReceiptProcessor` | `void process(Long inboxId)`; idempotent, route theo đối tượng và gọi adapter nghiệp vụ dưới cùng transaction |
| `payment/ReceiptApplicationService` | `void apply(Long inboxId, String leaseOwner)`; `void expire(Long intentId)` trên Spring proxy transactional riêng; khóa aggregate trước tiền rồi gọi handler; lease token bắt buộc để chặn worker cũ |
| `payment/RefundService` | `Long request(String businessKey, Long receiptId, long amount, RefundReason reason)`; tạo nghĩa vụ một lần, chưa đồng nghĩa chuyển tiền |
| `payment/RefundRecipientService` | `void submit(Long refundId, RefundRecipientForm form, CustomUserPrincipal actor)`; chỉ người nhận đúng khoản, snapshot đích trước dispatch |
| `payment/PayoutTransactions` | transaction claim/beginSend/finish độc lập, READ_COMMITTED, khóa Receipt → Refund → Attempt và lease fencing; persist snapshot/key trước HTTP |
| `payment/PayoutChannelResolver` | đọc ID kênh → credential reference phía server, chỉ kênh PAYOS/PAYOUT hợp lệ; đổi nguồn phải tạo kênh mới |
| `payment/PaymentMaintenanceJob` | `scan()` quét ID bounded/keyset rồi giao cho executor domain/payout riêng; DB work giữ nguyên khi queue đầy, thiếu handler hoặc restart; schedule 30 giây opt-in |
| `payment/PaymentMaintenanceWork` | discovery ngắn và `notifyOverdue(refundId)` khóa Receipt → Refund, thông báo parties/Admin cùng marker trong một transaction |
| `payment/FinancialViews` | scoped page/detail theo ACTIVE user/role; refund status chỉ id/status/dueAt/overdue, read-only và không trả bank/credential/raw payload |
| `payment/PayoutWorker` | `void dispatch(Long refundId)`; `void reconcile(Long attemptId)`; không giữ transaction/Car queue lúc gọi API ngoài |
| `booking/BookingService` | giữ create/Owner accept/cancel; bổ sung `acceptBefore`, `confirmHandover`, `confirmReturn`, `applyAdvance`, `applyBalance` theo Phase 6 |
| `inspection/InspectionService` | nháp, ảnh private, gửi BEFORE/AFTER và ghi nhận bất đồng; không tự sửa tổng tiền |
| `subscription/SubscriptionService` | tạo yêu cầu mua, áp dụng payment một lần, gia hạn và policy quota/hiển thị |

`PaymentIntentView` là record chứa `id`, `code`, `amount`, `expiresAt`, `status`, `qrUrl`; chỉ dữ liệu hiển thị, không chứa secret. `status` là trạng thái hiển thị, có thêm REFUND_PENDING/REFUNDED/UNAVAILABLE, không sửa enum DB để biểu diễn các trạng thái tổng hợp này. `PaymentDomainHandler` bắt buộc có `subject`, `lockSubject`, `apply`, `expire`, `cancel`: cancel chỉ đóng attempt trong core, handler quyết định tác động domain theo loại nghĩa vụ. Handler phải khóa/đọc current aggregate, xác thực điều kiện nghiệp vụ và không lấy thêm khóa aggregate trong apply/expire/cancel. `RefundRecipientForm` gồm `bankBin`, `accountNumber`, `saveForLater`, `confirmedAccountName`; tên cuối chỉ đối chiếu với verifier vừa gọi lại, không dùng làm tên lưu. Preview và submit xác minh ngoài transaction/queue, rồi commit ngắn dưới khóa. Production verifier mặc định từ chối cho tới P4.2-A; fixture không chứng minh lookup thật. `RefundReason` gồm CUSTOMER_CANCEL, OWNER_CANCEL, LATE_ADVANCE, SYSTEM_CONFIRMATION_DELAY, DUPLICATE_PAYMENT, EXCESS_BALANCE, LATE_SUBSCRIPTION, CANCELLED_SUBSCRIPTION, INCOMPATIBLE_SUBSCRIPTION. Actor, amount, ID kênh chi không lấy từ form của người nhận.

### 6.2. Dữ liệu và migration

Giữ bảng `payments`/seed hiện có làm dữ liệu legacy; không đổi chúng thành hai đợt thanh toán mới hoặc giả định có gói lịch sử. P4.3 đã tạo migration/bootstrap và mapping cho 7 bảng core đầu tiên, kiểm chứng trên DB riêng; DB ứng dụng chưa migration. P4.7 bổ sung migration `2026-10-30-01-refund-accounts.sql` và bảng prefill riêng; cả hai migration chỉ kiểm chứng trên DB tạm. Các bảng domain Phase 6/8 vẫn là kế hoạch. Luồng thật dùng các bảng sau:

| Bảng theo phase | Khóa/thuộc tính bắt buộc |
|---|---|
| `payment_channels` | chủ thể OWNER/ADMIN, userId, provider, purpose COLLECT/PAYOUT, ngân hàng/đích nhận, credential reference, enabled; không trả credential ra UI |
| `payment_obligations` | business_key UNIQUE; purpose ADVANCE/BALANCE/SUBSCRIPTION; payer/collector userId, subjectId, amountDue/amountSettled, dueAt, trạng thái; tiền snapshot |
| `payment_intents` | obligationId FK, code UNIQUE, channelId, amount snapshot, createdAt/expiresAt/cancelledAt, trạng thái; mỗi nghĩa vụ tối đa một attempt ACTIVE qua khóa obligation |
| `webhook_inbox` | UNIQUE(channelId, providerTransactionId), raw payload, receivedAt, trạng thái/lease/retry; lưu trước ACK |
| `payment_receipts` | UNIQUE(channelId, providerTransactionId), inboxId, intentId nullable, amount, creditedAt, allocation, trạng thái APPLIED/REFUND_REQUIRED/REVIEW_REQUIRED |
| `refunds` | business_key UNIQUE, receiptId, payout_channel_id thuộc đúng collector của receipt, recipient user, amount/reason, beneficiary snapshot, readyAt/dueAt/status; không giảm số đã thu để giả lập hoàn |
| `payout_attempts` | UNIQUE(refundId, attemptNo), idempotencyKey UNIQUE, provider ID, trạng thái/kết quả/lỗi; một lần chi chưa rõ kết quả chặn lần chi mới |
| `refund_recipient_accounts` | user PK/FK RESTRICT, bank BIN/account/name đã xác minh và verified_at UTC; opt-in, chỉ prefill, không sửa snapshot refund/attempt cũ |
| `subscription_orders` | Owner, mã gói, quota/giá snapshot, obligationId UNIQUE, expiresAt, appliedAt; tạo tại Phase 8 |
| `owner_subscriptions` | ownerId UNIQUE, mã gói, quota, validFrom/validUntil, lastAppliedOrderId; tạo tại Phase 8 |
| `booking_inspections`, `inspection_images`, `inspection_events` | report BEFORE/AFTER UNIQUE theo booking/phase; tác giả, ảnh private, event append-only; tạo tại Phase 6 |

Booking mới có `workflow_version=VIETQR_V1`; bản ghi cũ mặc định LEGACY. Phase 6 thêm Owner accepted, BEFORE accepted, handover/contract actor + timestamp, return timestamp, hold expiry và snapshot advance/balance. Giữ các status cũ, thêm EXPIRED; “Đang thuê”, “Đã trả — chờ 70%” là tiến độ hiển thị từ timestamp + nghĩa vụ. Owner accept chưa phải đã thanh toán: PENDING → CONFIRMED sau khoản trả trước hợp lệ. COMPLETED tự động khi đã trả và nghĩa vụ còn lại được thanh toán đủ.

P6.1 chỉ thêm nền schema/model; createBooking hiện vẫn LEGACY/false. Server cấp VIETQR_V1 khi đủ gate provider/domain ở các task rental sau, không phải side effect của migration.

Migration chỉ chạy có kiểm soát, backup và kiểm tra schema nguồn; không DDL lúc boot, không reset seed. Mỗi phase cập nhật migration và `scriptDB.sql` đồng nhất. Không áp dụng policy mới cho booking legacy.

### 6.3. Khóa, queue và giao dịch ngoài hệ thống

- Reads không vào queue. Các entry point HTTP, xử lý receipt thuê xe và job thay đổi booking/custody cùng xe đi qua CarCommandExecutor; không lồng queue, không enqueue khi đang giữ transaction DB.
- Queue chỉ điều phối một tiến trình, không thay khóa DB hoặc ngăn mọi deadlock. Webhook/job có nguồn việc bền vững trong DB; queue mất khi app dừng không được làm mất giao dịch đã ACK.
- Contract tích hợp worker sau gate P7.4 được ghi tại [Phase 7](phase-7-car-queue.md#contract-worker-tài-chính-cho-phase-46): claim/route xong transaction mới enqueue, apply/processed cùng commit, admission fail giữ việc để retry; API payout ngoài transaction/Car queue.
- Thứ tự khóa mở rộng: **Owner User → OwnerSubscription → SubscriptionOrder → Car → Booking (ID tăng) → Obligation → Intent → Receipt → Refund → PayoutAttempt → Inspection → Image**. Chỉ lấy các hàng cần thiết theo thứ tự, không lấy khóa ngược chiều. Nghiệp vụ không cần Owner/quota có thể bắt đầu từ Car nhưng sau đó không quay lại khóa Owner.
- Quota phối hợp theo Owner bằng khóa hàng Owner, vì hai carId khác nhau vẫn có thể cùng lấy suất cuối. Subscription mutation không đi vào Car queue rồi chờ Owner lock; tier change/hide nhiều xe khóa Owner trước rồi Car theo ID tăng.
- Xác thực webhook và ghi inbox là transaction riêng ngắn. Worker đọc route rồi vào queue nếu cần; transaction xử lý nghiệp vụ bắt đầu khi đến lượt. ACK thành công chỉ sau inbox commit; DB lỗi trả lỗi để nhà cung cấp retry.
- Trong apply/expiry transaction, handler phải khóa aggregate trước các hàng tài chính: rental khóa Car/Booking; subscription khóa Owner/entitlement/order và các Car sẽ đổi khi hạ gói. Sau đó core mới khóa Obligation/Intent/Receipt. Không khóa tiền trước rồi mới gọi handler đi lấy Owner hoặc Car.
- Payout: claim/ghi attempt và idempotency key rồi commit → gọi API ngoài transaction → lưu kết quả trong transaction ngắn. Không giữ Car queue/DB lock trong khi đợi mạng. Khôi phục các attempt chưa rõ sau restart bằng truy vấn/retry cùng key theo hợp đồng nhà cung cấp.
- Session/role/ownership phải đọc lại khi tác vụ thực thi. POST người dùng có CSRF; chỉ endpoint webhook cụ thể dùng xác thực provider thay CSRF. GET polling không tạo intent, không đánh dấu paid và không phát sinh chuyển tiền.
- Webhook đã xác thực vẫn phải được ghi nhận/đối soát khi người dùng đã bị vô hiệu hóa; không bỏ tiền đã nhận chỉ vì không còn một session ACTIVE. Quyền thao tác của người dùng và việc ghi nhận giao dịch ngân hàng là hai kiểm tra riêng.

### 6.4. Hạch toán và giao diện

- Tiền thu và tiền hoàn là các bản ghi riêng; refund một phần không biến toàn bộ receipt thành REFUNDED. Net = phần thu được phân bổ − khoản hoàn đã SUCCEEDED.
- Doanh thu Admin = khoản phí gói hợp lệ đã áp dụng; không cộng tiền thuê Owner hoặc khoản chưa phân bổ/đang chờ hoàn. Theo dõi phí nhà cung cấp riêng nếu cần đối soát, không tự khấu trừ từ khoản hoàn đã chốt.
- Doanh thu Owner chỉ thuộc xe của Owner; tách tiền đã thu, đã hoàn thành công, còn nợ và hoàn đang chờ.
- Checkout phân biệt cancelled/expired/received/refund-pending/refunded. Không coi đóng tab hoặc nút hủy là đảo giao dịch ngân hàng. Thành công trên UI chỉ từ trạng thái backend đã commit.

## 7. Phần hoãn và giới hạn v1

- **Chuyển thiếu/thừa trả trước 30%:** lưu tại [SDD mục 1.4](../SDD.md). Chưa chọn cộng dồn hay hoàn toàn bộ mỗi giao dịch lệch; không thêm hai tính năng đó vào task hiện tại. Ghi nhận lệch ở REVIEW_REQUIRED và giữ nguyên hạn giữ chỗ; không tự xác nhận đủ tiền. Khoản trả trùng đúng số tiền và các khoản 70% vượt số còn nợ vẫn thuộc xử lý đã chốt.
- Chưa tính bồi thường/phụ phí, e-sign, upload bắt buộc hợp đồng, tự động thu nợ, chặn tài khoản do nợ hoặc hoàn quá hạn, nâng/hạ gói giữa kỳ, realtime WebSocket, queue phân tán.
- Chưa xây self-service onboarding nhiều Owner thật; cấu hình chủ thể thử nghiệm do người vận hành local thiết lập, secret chỉ phía server.
- Mặc định kỹ thuật tại §3–5 cần được đọc khi review. Không tự mô tả chúng là lựa chọn kinh doanh đã được hỏi riêng. Nếu nhà cung cấp không hỗ trợ kênh của Owner, dừng gate live và ghi rõ bằng chứng; không chuyển tiền thuê qua Admin để lách điều kiện.

## 8. Verification và bàn giao

Mọi phase: fixture riêng, giữ seed/schema/data cũ theo baseline; không in `.env`, API key, số dư hoặc tài khoản đầy đủ ra log công khai. Kịch bản tiền, bảo mật, concurrency cần kiểm chứng có ý nghĩa; không chỉ mock controller trả 200.

```powershell
.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
git diff --check
```

Trước khi thực thi plan: xác nhận baseline hiện tại, migration/backup và trạng thái từng gate. Cuối mỗi task ghi lệnh, kết quả thật và giới hạn; checkbox mới chỉ được tick sau khi thực thi. Không stage/commit/push tự động. Đợt lập roadmap chỉ tạo kế hoạch; các lần thực thi sau được ghi riêng tại checkpoint từng phase, chưa chuyển tiền thật.

**Gate bật demo mới:** Phase 7, 4, 6 và 8 đạt kiểm chứng tích hợp; **P4.2-A đạt gate tài khoản/provider/live** của một Owner và Admin; workflow flags chưa bật cho user khác. P4.2 offline hoàn thành không bỏ gate này. Phase 5 chốt dashboard và E2E toàn bộ. Tiền thật chỉ thử khi người dùng chủ động thực hiện giao dịch kiểm chứng đã xác định; không tự chuyển tiền thử lúc boot.
