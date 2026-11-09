# UI-05C — Bàn giao booking Admin và smoke cuối 15 trang

Ngày 2026-11-08, root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. **UI-05C đạt triển khai và kiểm chứng cục bộ; đủ 15/15 trang trong phạm vi gốc.** Không tự triển khai UI-06/UI-07.

## 1. Kết quả

- `admin/bookings.html`: workspace Admin active Đơn thuê, header/notice chỉ đọc, toolbar status/attention, tổng số kết quả, card desktop/mobile cùng DOM, badge tiến trình hiện có, ghi chú legacy/30–70 và paging giữ đủ status/attention/page. Có empty/reset, focus rõ và control tối thiểu 44px.
- `admin/booking-detail.html`: thông tin xe/người thuê/chủ xe/ngày/tổng tiền snapshot → payment summary và timeline → biên bản BEFORE/AFTER → obligations/receipts/refunds. Chỉ hiện link bằng chứng đã gửi; thiếu biên bản/giao dịch có thông điệp riêng. Legacy không dựng tiến trình hoặc tiền giả. Font của trang dùng token tồn tại; typography của timeline áp dụng trong wrapper tại trang.
- `AdminBookingTest`: thêm regression navigation Admin active qua GET list/detail, đồng thời bảo toàn tính chỉ đọc. RED 1/1 đúng thiếu shell trước sửa; GREEN trong suite cuối.

Giữ nguyên `booking/fragments.html`, `admin/finance-row.html`, mọi caller và JS polling. Không sửa Java main, controller/service/query, model, root POM, schema/seed ứng dụng, `.env`, cấu hình local/provider hoặc CSS/JS production. Không thêm dependency, thao tác handover/return/resolve/refund, endpoint, filter hoặc quyền mới. Không Git mutation/commit/push.

## 2. Kiểm chứng

| Hạng mục | Evidence mới |
|---|---|
| Baseline focused | **18/18**, 0 fail/error/skip |
| RED navigation | **1/1 fail** đúng thiếu navigation Admin |
| Focused + integration cuối | **89/89**, 0 fail/error/skip |
| Node polling | **8/8** |
| Chrome cuối | **482 kiểm tra**, 0 JavaScript error |
| Ảnh chính | **20 trước / 109 sau**; 5 viewport preview bổ sung đã xem ở lượt final5 |
| Đọc DB | **10 đoạn × 20 bảng** không đổi, snapshot toàn phiên cuối bằng baseline |
| Provider | Fake payout create/get/find **0**; không dùng provider thật |
| Cleanup | **12 schema** gồm cả lượt setup/browser lỗi đã truy vấn absent; port fixture đóng, credentials/ready dọn |
| Build/source | **63 resource** khớp source cuối; HEAD/index/config/protected files giữ nguyên |

Suite cuối: `AdminBookingTest,AdminPermissionsTest,WorkspaceUiTest,RentalTimelineTest,AdminUserPermissionsTest,OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest,SubscriptionOwnerUiTest,SubscriptionUiTest`. `RentalTimelineTest` vẫn chạy như integration dù shared fragment không sửa.

Chrome 1440×1000, 1024×768, 390×844, 320×740 kiểm tra list/detail/legacy/missing evidence/empty/page cuối: một h1, wrap tên dài và escaped, không tràn page, control ≥44px, không booking POST hoặc private credential/account projection. Filter GET/paging/reset thật, ID page cuối đối chiếu SQL, keyboard, CSS720 reflow, no-JS. Cả ba finance kind và subject deep link đã click; BEFORE/AFTER đã click, kiểm tra private image theo Admin/Owner/Customer, outsider/anonymous bị chặn. Thời gian Việt Nam, overdue inclusive theo clock; pending/processing/refund status giữ riêng. Role thường/stale principal bị chặn, input lỗi 400, missing booking 404 và Admin mutation route không tồn tại.

## 3. Smoke cuối đủ 15 trang và callers

Source cuối render 15 template ở cả bốn viewport: Owner dashboard; finances; checkout; result; refund; refund summary; subscription plans/detail; Admin dashboard/finance/detail/users/detail/bookings/detail. GET đều 200, một h1, không tràn ngang hoặc chạy XSS. Checkout vẫn poll status thành công. QR trong ảnh là SVG fixture có nhãn không dùng thanh toán; external network bị chặn, Tailwind/font dùng cache local.

Kiểm tra thêm 11 route caller: Owner cars/form/bookings/detail; Customer bookings/detail; Admin cars/detail; home/cars/register, tại desktop/mobile. BEFORE/AFTER là hai caller bổ sung đã mở và kiểm tra ở phần booking. Coverage bao gồm workspace/header, overview trên dashboard/plans/cars, refund-links trên result/subscription detail, finance-row trên finance detail/booking và timeline trên Admin/Owner/Customer.

**Lỗi baseline ngoài scope:** `/bookings` (Customer list) với tên model xe liền 50 ký tự có documentWidth **613px** ở viewport **390px**. Lượt `browser-final6` dừng tại assertion này sau khi smoke đủ 15 trang đạt. Đã tái hiện bằng cùng fixture trên template build UI-05B tại `caller-baseline/`: 613px ở 390px, 1440px ở 1440px; bản cuối có đúng số đo đó. Hash Customer list và head/header/workspace/pagination/footer/messages/booking fragments trùng build trước. Ghi nhận để xử lý UI-06; không coi caller này đạt reflow và không sửa ngoài phạm vi hai trang Admin. Lượt cuối kiểm tra baseline limitation riêng, không tính là một check reflow pass.

## 4. Các lượt chưa đạt được giữ riêng

- `seed-attempt1/2`: model thử vượt VARCHAR(50); lượt 2 còn lỗi BOM khi javac và chạy lại class cũ. `seed-attempt3`: reset BEFORE thiếu reset trường hợp đồng. Đã sửa fixture theo schema, không đổi schema/source ứng dụng; metadata lưu trước seed để audit cả cleanup lỗi.
- `browser-final` và `browser-final2`: kỳ vọng trang cuối một card không tính booking COMPLETED từ seed gốc; lần sửa đầu chưa khớp chuỗi Unicode nên vẫn thất bại. Sửa assertion đối chiếu ID SQL, không đổi UI.
- `browser-final3`: trạng thái REJECTED có dữ liệu seed; dùng kết hợp status/attention thực sự rỗng.
- `browser-final4`: selector giờ match cả timeline và finance; giới hạn vào timeline.
- `browser-final5`: thiếu QR fixture trong harness khi smoke checkout; đã bổ sung SVG từ harness UI-03, chủ động gửi stop và kiểm tra cleanup. 5 ảnh viewport desktop/mobile của lượt này đã được xem, nhưng không dùng lượt này làm pass aggregate.
- **`browser-final7` là lượt đạt toàn bộ 15 trang và kiểm tra booking**, kèm một baseline limitation của caller ngoài scope nêu trên, chạy schema/port mới. Fixture cuối cũng chỉnh ngày/giá/tổng booking nhất quán với snapshot 600.000/1.400.000; đây là dữ liệu thử, không phải chỉnh tiền ứng dụng. Bộ ảnh trước có view REJECTED với seed còn một đơn; empty thực sự nằm trong ảnh sau.

## 5. Artifacts và kiểm soát phạm vi

Xem `.scratch/ui-05C/`: `baseline.json`, `baseline-tests.log`, `red-tests.log`, `final-tests.log`, `node-tests.log`, `validation.json`, `schema-cleanup-audit.json`, `fixture-port-audit.json`; `browser-before/capture.json`; `browser-final7/report.json`, screenshot, `read-post-audit.json`, `sql-before.json`, `sql-after.json`, `provider-calls.json`, `cleanup.json`, `accessibility.txt`. Các log/lượt lỗi còn nguyên và được phân biệt với pass cuối.

Kế hoạch trước triển khai: [UI-05C analysis](ui-05C-analysis-and-plan-2026-11-08.md). Manifest kiểm tra dirty baseline, mọi file thêm/xóa/sửa, main/config/index/HEAD và contract Thymeleaf. Chỉ hai template, một test và tài liệu được phép thay đổi; không ghi đè thay đổi sẵn có. Checkpoint 8080 sau cleanup fixture giữ nguyên PID17824; cập nhật runtime theo yêu cầu người dùng ghi riêng phía dưới.

## 6. Giới hạn còn lại

**0 đợt / 0 trang còn lại trong scope 15 trang về implementation và kiểm chứng local.** Human visual review, native browser zoom 200%/screen reader thực tế và các gate provider/pilot/live vẫn riêng; không suy ra đã đối soát, chuyển tiền thật hoặc sẵn sàng production. UI-06A/B/C và UI-07 thuộc backlog rộng hơn, chưa được làm trong đợt này.

Customer booking list còn lỗi tên xe dài đã xác nhận từ baseline ngoài scope; xem §3. Human visual review có thể yêu cầu chỉnh bố cục tiếp, không được xem số automated checks là phê duyệt thiết kế.

## 7. Ứng dụng local đã mở theo yêu cầu

Sau cleanup và checkpoint 8080 giữ nguyên, xác minh PID17824 là đúng app preview UI-05B rồi dừng; port trống trước khi khởi động build UI-05C. Hiện **PID 480**, tool session **89128**, bind **127.0.0.1:8080**, profile `local`, classpath `.scratch/ui-05C/build/classes` + dependency classpath; không có test-classes/fixture configuration. Đây là runtime ứng dụng thật, độc lập với fixture.

Giữ override `ddl-auto=none`, `sql.init.mode=never`, payment maintenance/rental deadlines và cả bốn provider live/payout flags đều false. Không bootstrap/migrate DB ứng dụng, không kích hoạt kênh hoặc chuyển tiền. Root/config không đổi; refresh runtime được thực hiện theo yêu cầu người dùng chạy và mở dự án sau khi hoàn tất.

HTTP mới: `/` 200, `/login` 200, `/admin/bookings` chưa đăng nhập 302 về login. Đã mở `http://localhost:8080/admin/bookings` bằng Playwright MCP; trình duyệt đang ở `http://localhost:8080/login`, tiêu đề **Đăng nhập - CarRent**. Người dùng đăng nhập Admin để xem; không đăng nhập/mutation trên DB thật để nghiệm thu UI.

Runtime được **giữ chạy**. Xem `.scratch/ui-05C/app-runtime.json`, `app-runtime.log`, `runtime-refresh-before.json`; các runtime flags được xác nhận từ đúng process command line. Không dùng Start-Process (policy đã chặn ở đợt trước); chạy Java trực tiếp trong exec session và mở bằng browser tool.
