# CarRent — Handoff hiện hành: Phase6 / đợt4 — P6.12 → P6.13

**Tích hợp P6.11 — 2026-11-02:** feature `6de7526`, merge main `7aeb8a8`, author `huy69332`; đã push và giữ nhánh `feat/phase6-paid-cancellation`. Source merge khớp source đã clean verify816/816/JAR và browser79/79; sau merge chạy lại32/32 cancellation/UI test trên schema riêng. Phase0 riêng nằm ngoài commit và giữ nguyên. Tiến độ66/86, còn20; tiếp theo P6.12→P6.13, phụ trách `huy69332`, reviewer `thanhmit`. Không migrate DB ứng dụng/bật workflow/provider/scheduler/restart8080 hoặc chuyển tiền thật.

**Phạm vi lần tới:** P6.12 tích hợp UI/job/queue, sau đó P6.13 gate rental và đồng bộ SDD. Đây là hai task trong một đợt đã duyệt, có checklist và trạng thái riêng; không suy ra hoàn thành từ evidence P6.11. Phụ trách `huy69332`, reviewer được giao `thanhmit`; agent review không thay human contributor review.

## Workspace và baseline

- CWD `C:/Users/Admin/Documents/hoc/Car`, PowerShell. Main đã tích hợp P6.11 tại `7aeb8a8` (feature `6de7526`), nhánh tính năng được giữ. Kiểm tra HEAD/remote/status thực tế, tạo nhánh đợt4 từ main; không reset/clean làm mất phase0 riêng.
- `docs/plans/phase-0-init.md` là thay đổi riêng, phải giữ nguyên, không stage/sửa. Bảo toàn seed, uploads, private images, cấu hình và DB ứng dụng. Ghi fingerprint trước/sau mỗi đợt.
- P6.8–P6.10 đã tích hợp bằng feature `983e7c9`, merge `0a1d9e6`; nhánh cũ giữ nguyên. P6.11 đã commit/push/merge theo checkpoint Git phía trên.

## Nền P6.11

- Customer/Owner hủy trước handover; Customer tính phí từ advancePaidAt bằng chính sách P6.2, Owner hoàn đủ. Snapshot cancelledAt/fee/reason và nghĩa vụ hoàn cùng transaction; retry giữ lần thắng đầu tiên.
- Không có tiền thì không tạo refund; receipt đến sau tạo hoàn đủ. Sau handover/return chặn cả hai phía, giữ nguyên nợ. Tiền gốc vẫn APPLIED/RECEIVED/SETTLED; pending refund không phải SUCCEEDED.
- Car→Booking→financial locks, recheck actor ACTIVE/role sau khóa; kiểm chứng hai connection/stale RR cho receipt/cancel, handover/cancel, Customer/Owner và đổi quyền/status. POST tính lại số tiền, bỏ qua quote/actor/time từ client.
- DB-first thêm `bookings.cancellation_reason VARCHAR(500) NULL`. Bootstrap đã có; DB đã có Phase6 mà thiếu cột cần migration riêng `2026-11-02-01-cancellation-reason.sql` sau backup/dừng ứng dụng. Migration rental cũ giữ nguyên. **Chưa migrate DB ứng dụng**; `ddl-auto=none` giữ nguyên.
- UI hiển thị quote/snapshot/refund pending và tài chính hai phía, reason được escape. Browser source cuối79/79,25PNG,7SQL invariant. No-JS dùng HTML thường vì Tailwind CDN hiện chạy bằng JavaScript. Full gate và số test cuối xem [checkpoint P6.11](phase-6-inspection.md#checkpoint-p611--2026-11-02), SDD §14.37.
- Evidence `.scratch/phase6/p611/`: `full-clean-verify.log`, `verified-source-hashes.json`, `verification-summary.json`, `protected-checks.json`, `browser/browser-evidence.json`, `browser/browser-preservation.json`, `browser/browser-sql-invariants.txt`. Các log RED/lỗi fixture/schema-normalization và GREEN được giữ riêng, không ghi kết quả fail thành full green.

## Thứ tự triển khai đợt4

1. Đọc [P6.12/P6.13](phase-6-inspection.md#task-p612--ui-quyền-và-job-deadline-tích-hợp), SDD §14.37 và constraints tại [roadmap](README.md). Lập ma trận checklist với bằng chứng còn thiếu; phân biệt phần đã có trong P6.1–P6.11 và khoảng trống mới.
2. P6.12: timeline Customer/Owner/Admin phân biệt accept, BEFORE agreed, advance, contract/handover, AFTER, returned, balance, refunds. Checkout/poll read-only; ảnh đúng membership/ACTIVE/draft/submitted; escaped text, giữ lỗi/input, 320/390/1440.
3. Audit mọi mutation entry point đi qua CarCommandExecutor và current DB locks. Job30s dùng durable work, claim/route/queue trước domain transaction, notified marker cùng notices; queue reject không đánh dấu đã làm. Chỉ bật job/flags trong fixture riêng.
4. P6.13: sau khi tích hợp source cuối, full clean verify và RentalConcurrencyTest hai connection thật, stale snapshot/rollback/notify failure; E2E report→30%→handover→AFTER dispute→Owner received→70%→COMPLETED cùng cancellation/expiry/late receipt/refund. Dùng tiền/provider giả lập và HTTP/inbox/domain thật.
5. Đối chiếu appDB/phase0/config/seed/private files; đồng bộ các phần SDD/decisions liên quan hợp đồng Owner, mốc vật lý/tiền, policy ngày lịch và deadline. Tick từng task chỉ khi đủ checklist. Dừng trước Phase8.

## Kiểm chứng và giới hạn

- Tạo evidence riêng `.scratch/phase6/p612-p613/`, không ghi đè p611/p610 và không chạy lại script cập nhật docs của đợt cũ. Có thể tái sử dụng runner `FullSuite.java`, POM/args rồi thay toàn bộ đường dẫn output sang thư mục mới.
- Maven Wrapper chạy source thật trên schema disposable; không dùng DB ứng dụng làm datasource test. Không chạy `spring-boot:run` mặc định hoặc restart8080. Fixture dùng port ngẫu nhiên và dọn schema/private files sau test.
- Được dùng subagent cho phần độc lập/review; phân file rõ, build/log/output riêng, primary tích hợp và kiểm chứng source cuối.
- Không tự stage/commit/push, migrate DB ứng dụng, bật workflow/provider/scheduler ứng dụng hoặc chuyển tiền thật. P4.2-A live vẫn chờ; kết quả offline không mở gate live hoặc chứng minh Phase8/P5.9 đã đạt.

## Phân tích khoảng trống sau tích hợp P6.11

**Phân công:** P6.12 và P6.13 đều thuộc `huy69332`, reviewer `thanhmit`; mỗi task5checklist. Tiến độ hiện66/86, nếu đủ cả hai sẽ68/86, còn18. Chưa implement đợt4 từ phân tích này.

| Phần | Đã có | Cần bổ sung/kiểm chứng |
|---|---|---|
| Timeline | Customer/Owner có custody/return/balance/cancellation summary | Timeline đủ9mốc cho cả Customer/Owner/Admin; hiện Admin chưa có booking timeline. Giữ Admin chỉ đọc trong phạm vi này. |
| Trạng thái tiền | Checkout/poll và refund core Phase4 | `templates/booking/fragments.html` thiếu EXPIRED; summary còn ghi còn70% cả khi balance đã trả. Phân biệt tiền nhận/nợ/hoàn theo dữ liệu thật. |
| Quyền/ảnh/input | InspectionPrivacyTest có draft/member/ACTIVE/escape/giữ input | Tái sử dụng và bổ sung kiểm tra theo các view/entry point mới; browser320/390/1440/no-JS. |
| Job/queue | Job30s defaultoff; reminder/debt có durable marker, restart/queue reject tests | Audit toàn entry point qua executor→CarCommandExecutor→domain/current locks; kiểm chứng scheduler/reject/restart trong fixture riêng, không viết lại job đang có. |
| Gate | Test race theo từng lifecycle đã có | Thêm RentalConcurrencyTest tích hợp hai connection thật + E2E xuyên BEFORE/30%/handover/AFTER dispute/received/70%/COMPLETED và cancel/expiry/late refund. |
| SDD/decisions | Checkpoint mới đã phản ánh30/70 | Đồng bộ rule cũ còn ở SDD §nghiệp vụ và decisions D-25: Customer final pickup, unpaid pickup, cash giả lập, Admin resolve/AFTER accepted mới được complete. Giữ legacy và ghi rõ lịch sử/superseded. |

**Cách triển khai:** lập ma trận checklist→source→evidence; làm P6.12 timeline/trạng thái/Admin và audit quyền/job/queue trước; sau đó P6.13 gate liên tục và tài liệu trên source cuối. Có thể giao agent độc lập phần view/Admin, test gate và review, tránh sửa chồng file/build output. Full clean verify/JAR và browser cuối là bằng chứng riêng của đợt4; không dùng số816/79 của P6.11 để tick task mới. Dừng trước Phase8 và giữ gate P4.2-A live pending.
