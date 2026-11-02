# CarRent — Handoff lịch sử: Phase6 / đợt2 — P6.10

> P6.10 đã hoàn thành. Handoff hiện hành: [P6.11 / đợt3](phase-6-batch-3-handoff.md). Các chỉ dẫn và số liệu dưới đây là snapshot trước triển khai P6.10.

**Ngày:** 2026-11-02. **Task tiếp theo:** triển khai riêng P6.10 — QR/thanh toán70%, nhắc quá hạn và tự hoàn tất; chưa gộp P6.11. Đọc checklist đầy đủ tại [Phase6](phase-6-inspection.md#task-p610--thanh-toán-70-quá-hạn-và-hoàn-thành), [roadmap](README.md), [SDD](../SDD.md) §14.34–14.35 và [handoff tài chính](phase-4-handoff.md).

## 1. Workspace và bảo toàn

- CWD `C:\Users\Admin\Documents\hoc\Car`, PowerShell; nhánh `feat/phase6-after-return`, HEAD `a27b120dc061ddf439ec4bbce611780a40e8e7fd` từ main đã tích hợp P6.4–P6.7. **P6.8/P6.9 đang chưa commit**, không bắt đầu lại từ main hoặc reset/clean để làm sạch. Đọc `git status --short`, diff và HEAD thực tế; người dùng có thể tích hợp Git sau handoff này.
- Giữ nguyên dirty change riêng `docs/plans/phase-0-init.md`; không stage file đó. Không thay seed/ảnh riêng hoặc chỉnh dữ liệu đã gửi để khớp test.
- P6.8+P6.9 thuộc `huy69332`, reviewer được giao `thanhmit`. Có cross-review agent theo file độc lập, chưa có human contributor review; không ghi agent là tác giả Git/contributor.
- Được chủ động dùng subagent cho công việc độc lập/review; primary chịu trách nhiệm tránh sửa chồng, tích hợp và kiểm chứng cuối.

## 2. Nền đã hoàn thành

- **64/86 task hoàn thành, còn22**; 45task mới đã xong23. Phase6 P6.1–P6.9 đóng; còn P6.10–P6.13, Phase8 tám task, Phase5 chín task, P4.2-A một gate live.
- `InspectionService.submitAfter`: Customer current ACTIVE/role, CONFIRMED/đã handover/chưa returned, đủ5trường/4–10ảnh; nội dung/ảnh immutable, event/Owner notice atomic. Gửi sớm/đúng ngày/trễ được phép; không tự trả xe/tạo BALANCE.
- `BookingService.confirmReturn(id, ReturnReceiptForm, actor)`: Owner đúng xe/current ACTIVE; ba decision. NOT_RECEIVED không tạo nghĩa vụ, lặp note giống nhau dedup; note trống dùng câu xác nhận máy chủ theo ràng buộc event SQL. RECEIVED_ACCEPTED/DISPUTED ghi returnedAt/status/event/Customer notice và BALANCE cùng transaction, disputed note bắt buộc.
- **Khóa current Car → Booking → actor shared read → tạo/khóa BALANCE → AFTER → images/event.** Actor shared locking refresh sau aggregate tránh stale RR và tránh đảo thứ tự User→Car. Native scalar `InspectionEventRepository.findLatestForUpdate` dùng `SELECT … FOR UPDATE` vì entity event có `@Immutable`; không thêm ORM @Lock vào immutable entity.
- BALANCE key `BOOKING:<id>:BALANCE`, purposeBALANCE/subjectBOOKING, payerCustomer/collectorOwner, amountDue=balanceAmount snapshot, OPEN/settled0, dueAt=vehicleReturnedAt+3600s. Retry không reset timestamp/deadline/notice; obligation sai hoặc report sai bị từ chối/rollback. Booking vẫn CONFIRMED sau trả xe, không complete.
- `RentalDeadlineJob` quét DB keyset50, worker `paymentDomainExecutor` → Car queue → `BookingService.remindAfterReturn`; submittedAt+1giờ đủ thì marker+Owner/Admin notices atomic. `carrent.rental.deadlines.enabled` mặc định tắt, fixed30s. Current job chỉ nhắc Owner nhận lại xe; chưa có balance-overdue work.
- UI BEFORE/AFTER comparison, waiting/NOT_RECEIVED/returned/disputed; BALANCE số tiền/hạn/trạng thái chỉ đọc. Không có checkout BALANCE, handler/applyBalance hoặc auto-COMPLETED. Manual complete VIETQR_V1 và paid cancellation vẫn bị chặn có chủ đích.

## 3. Bằng chứng cuối đợt trước

- Full isolated clean verify **746/746 JUnit**,0failure/error/skip, JAR; gồm683hồi quy + RentalAfterReturnTest49 + AfterReturnUiTest9 + RentalAfterReminderJobTest5.
- Chromium **58/58**,16PNG tại320/390/1440/no-JS/hai tab,14invariant DB; business HTTP thật, cache CSS/fonts, incoming processor offline thật. Không live provider.
- Evidence `.scratch/phase6/p68-p69/`: `full-clean-verify.log`, `targeted-green.log`, `verified-source-hashes.json`, `browser-evidence.json`, `browser-fixture.log`, `browser-source-manifest.json`, `progress.md`. Không dùng số này làm kiểm chứng source mới của P6.10.
- `finish-docs.py` đã chạy, không idempotent; không chạy lại hoặc ghi đè evidence. Manifest source là checkpoint trước thay đổi P6.10, không phải gate tự đạt cho đợt mới.
- Đã sửa từ RED: NOT_RECEIVED ORM lock trên immutable event; account ban/role change sau snapshot cũ; fixture UTC/contract reset; concurrency receipt dùng claim ngoài transaction rồi `ReceiptApplicationService.apply` trong transaction (giữ processor NEVER).
- DB ứng dụng vẫn7bảng, fingerprint schema/toàn bộ rows và phase0/ảnh/seed không đổi; fixture DB/private files/randomport đã dọn. Không restart8080/migrate/bật workflow/provider/scheduler ứng dụng/stage/commit/push.

## 4. P6.10 cần hoàn thành đủ6checklist

1. QR BALANCE15phút/cancel chỉ kết thúc attempt; không hủy booking/xóa nợ/resetdueAt. Có POST mở attempt mới, GET/poll không mutate.
2. Receipt QR cũ áp dụng đúng obligation còn nợ; dư/đã đủ hoàn đúng nguồn Owner (EXCESS_BALANCE/trùng theo contract Phase4). Không nhầm advance refund hoặc collectorAdmin.
3. Receipt thấp hơn phần còn nợ lưu review, không tự cộng dồn/complete. Giữ policy advance không đổi.
4. Đã physicalreturned + đủ balance thì COMPLETED atomic trong apply; AFTER DISPUTED không chặn, bằng chứng giữ nguyên; complete manual vẫn không mở.
5. Sau dueAt còn nợ, `balance_overdue_notified_at` + Customer/Owner/Admin notices một lần atomic/restart-safe; chỉ cảnh báo, không ban/chặn đặt/phạt/refundadvance.
6. Test đủ tiền đúng/sau hạn, QR cũ/trùng/dư/thiếu, tab đóng/restart/dispute+paid/legacy và concurrency/rollback/currentreads; full clean verify/browser/SDD/checkpoint mới.

**Đọc đúng seam trước khi sửa:** `booking/RentalPaymentHandler.java` hiện chỉ BOOKING_ADVANCE và chỉ đăng ký khi `carrent.rental.advance.enabled=true`; không làm biến mất handler advance khi thêm balance. `PaymentSubject.of`, `PaymentRoutes`, `PaymentIntentTransactions`, `ReceiptProcessor`, `ReceiptApplicationService`, `PaymentLocks`, `PaymentMaintenanceJob/Work`, `FinancialViews/Controller`, refund routes, RentalDeadlineJob và templates chứa các caller tích hợp. Reuse core15phút/lease/idempotency, không tạo payment pipeline thứ hai.

**Kiểm chứng:** test hai connection MySQL thật cho return-vs-balance receipt, duplicate/late attempts và reminder-vs-payment, cả stale managed snapshots; rollback obligation/booking/notification/refund cùng commit. JDBC fixture timestamps mới dùng LocalDateTime UTC; entity Instant round-trip qua Hibernate. Browser actual fixture HTTP, so snapshot/hash DB/privatefiles trước/sau và dọn riêng fixture. Tạo evidence folder mới `.scratch/phase6/p610/`, không ghi đè đợt trước; harness p68-p69 chỉ tham khảo.

## 5. Giới hạn

Không migrate DB ứng dụng; DB-first `scriptDB.sql`, `ddl-auto=none`. Không bật workflow/provider/scheduler trên ứng dụng, restart8080, tiền thật hoặc tự stage/commit/push. Scheduler/domain flags chỉ bật fixture riêng. P4.2-A vẫn chưa đạt, offline pass không phải live readiness. Dừng trước P6.11; không triển khai paid cancellation/hoàn hủy ở đợt P6.10.
