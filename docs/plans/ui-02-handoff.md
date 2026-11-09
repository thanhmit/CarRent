# UI-02 — Bàn giao dashboard Owner và tài chính

Ngày 2026-11-08. Triển khai trên root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`, giữ nguyên dirty baseline, index và các thay đổi ngoài phạm vi. **UI-02 đạt kiểm chứng cục bộ; dừng trước UI-03.** Human visual review còn chờ; chưa nghiệm thu CDN/provider/pilot thật.

## 1. Nội dung bàn giao

- `owner/dashboard.html`: workspace Owner, heading/CTA, bốn KPI xe/đơn, overview gói, tiền đã ghi nhận và cảnh báo hoàn/nợ; giữ 13 metric và các ID. Public null/unavailable khác zero; tiền phân bổ, đã hoàn, đang chờ và quá hạn giữ độc lập.
- `payment/finances.html`: Owner/Admin dùng workspace, Customer dùng public header; một bản nội dung cho 9 role/view hợp lệ. Active scope rõ; bảng desktop và card có nhãn trên màn nhỏ giữ table/row/cell roles. Nghĩa vụ và lần thanh toán gần nhất có nhãn riêng; tài khoản refund tiếp tục được che, có nhãn khi chưa cung cấp tài khoản/hạn. Ba Page giữ chung index và OR `hasNext()`, chuyển scope reset page, reload giữ view/page.
- `subscription/overview.html`: giữ fragment `overview` không tham số, null guard, IDs và ba CTA; no-plan/active/expired, quota/pending/tạm ẩn. Hiển thị reserved thực khi vượt quota, không chia quota0. Quy định hạ gói dùng native details; ba caller dashboard/cars/plans đã render lại.
- `fragments/workspace.html`: chỉ bổ sung predicates cho hai link tài chính Admin. `finances + null/managed` chọn `/admin/finance`; `finances + paid/collected/refunds` chọn `/payment/finances`. Chữ ký sidebar/topbar/navigation giữ nguyên.
- `WorkspaceUiTest`: thêm ba test cho dashboard, ba scope Owner, bốn scope Admin và đủ 13 metric. `FinancialViewsTest`: review21 với obligation rỗng; thêm refund21/page1, scope/reload và empty riêng. Giữ assertions quyền/masking/status hiện có.

Không đổi Java main/controller/query, schema/seed ứng dụng, provider/config, `.env`, `application-local.properties`, root POM, CSS/JS hoặc dependency. Không restart8080, stage/commit/push hoặc gọi provider thật. Các lần restart trong evidence chỉ là fixture dùng schema mới/port ngẫu nhiên để đổi flag gói.

## 2. Kết quả kiểm chứng

| Bước | Kết quả |
|---|---|
| Baseline focused suite | **43/43**, 0 failure/error/skip |
| RED navigation | 3 test mới fail đúng kỳ vọng trên UI cũ; 5 test UI-01 vẫn pass |
| Lượt GREEN đầu | 46/47; assertion chữ `tạm ẩn` cần giữ tương thích, log được giữ |
| Focused suite cuối | **47/47**, 0 failure/error/skip |
| Chrome bốn viewport | **1233 kiểm tra**, 0 JavaScript error |
| Ảnh | **28 trước / 139 sau**, cùng các ảnh/log lỗi riêng |
| GET chỉ đọc | **20 bảng** không đổi trước/sau; 27 đoạn GET được đối chiếu quanh thao tác seed/clock/schema có chủ đích của harness |
| Provider | **0 calls**, kể cả trước các lần restart fixture |
| Cleanup | Schema fixture đã xóa và kiểm tra lại, port đã đóng, ready/credential tạm đã dọn; 8080 giữ nguyên |

Suite cuối: `WorkspaceUiTest`8, `OwnerRevenueTest`11, `FinancialViewsTest`11, `PaymentAccessAuditTest`9, `SubscriptionOwnerUiTest`8. POM/output cách ly tại `.scratch/ui-02/`; root target không bị dùng làm output.

Browser 1440×1000, 1024×768, 390×844, 320×740 kiểm tra role/view, active workspace và scope, target mới ≥44px, keyboard menu, semantic table/card, tiền không tràn cell, escaping và no-store. Native no-JS menu → finances → reload → logout có CSRF hoạt động.

Matrix gồm Owner A/B, Customer A/B, Owner zero/no-plan/expired, reserved bằng/vượt quota; flag off, thiếu subscription/refunds/payout tables với gate độc lập; refund đủ sáu trạng thái, obligation OPEN/SETTLED/CANCELLED/EXPIRED, intent thiếu/hết hạn và link chỉ payer. Có review-only21 và refund-only21, page0/1, empty riêng, tiền lớn `9.000.000.000.000.000 VNĐ`, thời gian Việt Nam. Anonymous, view lạ, bad page, page âm, forbidden role/view, foreign refund, stale banned/demoted đều giữ HTTP behavior.

Fixture đích dashboard A: public7/pending1/hidden1/bookings3; allocated150.000, refund succeeded20.000, pending2/40.000, overdue1/30.000; outstanding150.000 và overdue1/150.000. Kiểm tra trước deadline1giây, đúng deadline, sau1giây; QR hết hạn không xóa nợ, gói hết hạn không xóa tiền lịch sử. Giá trị kỳ vọng là literal, không lấy kết quả production làm expected.

## 3. Evidence và xem ảnh

- `.scratch/ui-02/baseline.json`, `port8080-before.json`: manifest tracked/untracked, file xóa sẵn, hash config bảo vệ, HEAD/branch/index và port.
- `baseline-tests.log`, `red-tests.log`, `green-tests.log`, `final-tests.log`: giữ riêng baseline, RED, lỗi đầu và suite cuối.
- `browser-before-final/`: 28 PNG/capture, SQL/provider/cleanup của fixture trước trên resources baseline đã đối chiếu.
- `browser-complete/`: `report.json`, PNG, accessibility desktop/mobile, SQL trước/sau, segments, provider-calls, metadata, ports và cleanup cuối.
- `schema-cleanup-audit.json`, `validation.json`: schema đã xóa, hash allowlist, resource build, index/config/HEAD và cleanup/port.

Ảnh đại diện:

- [Dashboard desktop](../../.scratch/ui-02/browser-complete/dashboard-owner-1440.png), [320px](../../.scratch/ui-02/browser-complete/dashboard-owner-320.png).
- [Owner finances desktop](../../.scratch/ui-02/browser-complete/finance-owner-collected-1440.png), [320px](../../.scratch/ui-02/browser-complete/finance-owner-collected-320.png).
- [Admin managed](../../.scratch/ui-02/browser-complete/finance-admin-managed-1440.png), [Customer refund](../../.scratch/ui-02/browser-complete/finance-customer-refunds-390.png).
- [Overview trên cars](../../.scratch/ui-02/browser-complete/overview-active-cars-390.png), [trên plans](../../.scratch/ui-02/browser-complete/overview-active-subscriptions-390.png).
- [Review page1](../../.scratch/ui-02/browser-complete/review-page-one-320.png), [refund page1](../../.scratch/ui-02/browser-complete/refund-page-one-390.png).

Agent đã xem ảnh đại diện hai trang chính và overview callers; đây là tự kiểm, **human visual review chưa thực hiện**.

## 4. Lỗi ban đầu và giới hạn

- Seed fixture đầu thiếu bank BIN đi kèm account và tạo expiresAt trước createdAt; đã sửa seed đúng constraint. Seed obligation bổ sung lúc đầu dùng business key không đúng định dạng; đã đổi về key booking/purpose hiện hành, giữ log lỗi và chạy lại. Một lần biên dịch harness trùng lúc Maven đang thay test-classes; đã dùng classpath ổn định. Không sửa constraint hay source backend.
- Capture trước gặp dấu `]` thừa và tên PNG chứa `?` không hợp lệ trên Windows; đã sửa harness, chụp mới đủ28. Các log lỗi vẫn được giữ.
- Browser lượt đầu dừng do assertion đọc DOM ngay sau click next; ảnh lỗi cho thấy page2 đúng một receipt. Đã chờ network idle sau navigation và chạy lại matrix. Lượt bị ngắt đang ở seed review21 nên SQL cuối khác hai bảng fixture do harness chưa restore; không tính là GET mutation hoặc bằng chứng pass. Final verified restore đầy đủ,20 bảng khớp và từng đoạn GET giữ nguyên.
- Một lượt browser đã pass1233 kiểm tra nhưng audit tổng còn lệch `users.updated_at`: MySQL tự cập nhật timestamp khi harness thử BANNED/đổi role, restore trước đó mới khôi phục role/status. Cả27 đoạn GET trong lượt đó không đổi dữ liệu. Harness cuối khôi phục timestamp gốc rõ ràng rồi chạy lại đầy đủ; không bỏ cột khỏi snapshot hoặc nới assertion.
- Query port8080 đầu phiên không trả listener; pipeline PowerShell rỗng nên chưa tạo file. `port8080-before.json` được ghi bổ sung từ quan sát đó và có provenance/`recordedAfterRun`, không dùng query cuối làm baseline. Query cuối cũng không có listener; các port fixture đã đóng được kiểm tra riêng.
- Native browser zoom200% chưa thao tác; chỉ kiểm tra reflow tương đương tại720 CSSpx. Không thay kết quả này bằng tuyên bố đã kiểm tra zoom thật.
- Không chạy full suite/JAR hoặc polling suite: thay đổi giới hạn ở template; focused47 và browser là kiểm chứng lần này. Tailwind/font dùng cache cục bộ, request ngoài bị chặn. Không dùng fixture để kết luận CDN/provider/tiền thật hoạt động.
- `uploads/seed-car-5.png` đã bị xóa từ baseline; giữ nguyên, không khôi phục ảnh seed ngoài phạm vi. File JavaScript/CSS và các trang UI-03 trở đi không được thay đổi.

## 5. Điểm dừng và đợt tiếp theo

UI-02 đóng các checklist triển khai/kiểm chứng cục bộ. UI-03 trở đi giữ mở; tiến độ nghiệp vụ83/86 và gate live/provider/human không đổi. Khi được giao UI-03, đọc kế hoạch thanh toán/refund và handoff này, dùng baseline/fixture mới, giữ nguyên selectors/polling/CSRF/quyền; không mở rộng UI-02 sang checkout/order/Admin dashboard.
