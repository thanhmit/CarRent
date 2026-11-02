# CarRent — Handoff đợt3 — P6.11 (đã hoàn thành)

**Tích hợp P6.11 — 2026-11-02:** feature `6de7526`, merge main `7aeb8a8`, author `huy69332`; đã push và giữ nhánh `feat/phase6-paid-cancellation`. Source merge khớp source đã clean verify816/816/JAR và browser79/79; sau merge chạy lại32/32 cancellation/UI test trên schema riêng. Phase0 riêng nằm ngoài commit và giữ nguyên. Tiến độ66/86, còn20; tiếp theo P6.12→P6.13, phụ trách `huy69332`, reviewer `thanhmit`. Không migrate DB ứng dụng/bật workflow/provider/scheduler/restart8080 hoặc chuyển tiền thật.

**Checkpoint P6.11 — 2026-11-02:** đủ **5/5 checklist**: Customer/Owner hủy trước handover, snapshot thời gian/phí/lý do và nghĩa vụ hoàn atomic; receipt gốc giữ tiền đã nhận. Full isolated clean verify **816/816 JUnit, 0 failure/error/skip**, JAR; Chromium **79/79**,25PNG,7/7SQL invariant. **66/86 task**, còn20 (45task mới xong25). Tiếp theo **P6.12 → P6.13** cùng đợt. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. P6.11 đã tích hợp main; xem checkpoint Git phía trên. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên; không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. P4.2-A live vẫn chờ.

**Handoff hiện hành:** [P6.12→P6.13](phase-6-batch-4-handoff.md). Phần bên dưới là brief lịch sử trước P6.11, không phải trạng thái hiện tại.


**Tích hợp Git 2026-11-02:** P6.8–P6.10 dùng author `huy69332`, commit tính năng `983e7c9`, merge main `0a1d9e6`; giữ nhánh tính năng. Clean verify mới783/783/JAR; evidence `.scratch/phase6/p610/integration/full-clean-verify.log`. Thay đổi riêng phase-0 nằm ngoài commit và giữ nguyên; không migrate/bật DB ứng dụng/provider/scheduler hoặc chuyển tiền thật. Có agent review, chưa có human contributor review. Tiếp theo P6.11 riêng.

**Ngày:** 2026-11-02. **Task tiếp theo:** P6.11 riêng — Customer/Owner hủy trước handover và tạo hoàn. Đọc [checklist](phase-6-inspection.md#task-p611--customerowner-hủy-trước-handover-và-tạo-hoàn), [roadmap](README.md), SDD §14.36 và [handoff tài chính](phase-4-handoff.md).

## Workspace và bảo toàn

- CWD `C:/Users/Admin/Documents/hoc/Car`, PowerShell. Baseline main merge `0a1d9e6` / feature `983e7c9` (`feat/phase6-after-return`, giữ nhánh). Kiểm tra HEAD/remote/status thực tế trước P6.11. P6.8–P6.10 đã tích hợp; chỉ phase-0 riêng còn dirty. Giữ nguyên, không reset/clean.
- Giữ nguyên thay đổi riêng `docs/plans/phase-0-init.md`; không stage hoặc sửa. Giữ seed/ảnh/private files. Không thay dữ liệu đã gửi để khớp test.
- Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. Được giao subagent phần độc lập, primary tránh sửa chồng/tích hợp/kiểm chứng cuối.

## Nền đã hoàn thành

**Checkpoint P6.10 — 2026-11-02:** đủ **6/6 checklist**: QR BALANCE15phút độc lập hạn nợ1giờ, receipt QR cũ/dư/trùng/thiếu, auto-COMPLETED atomic sau physical return, dispute giữ evidence và nhắc nợ một lần. Full isolated clean verify **783/783 JUnit, 0 failure/error/skip**, JAR; Chromium **63/63**, 19 ảnh, 12 SQL invariant. **65/86 task**, còn21 (45task mới xong24). Tiếp theo **P6.11 riêng**. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. P6.8–P6.10 đã tích hợp Git; xem checkpoint phía trên; DB ứng dụng vẫn7bảng, phase0/seed/ảnh giữ nguyên. Không migrate/bật workflow/provider/scheduler ứng dụng, restart8080, chuyển tiền thật hoặc stage/commit/push. P4.2-A live vẫn chờ.

- P6.8 submit AFTER immutable; P6.9 Owner nhận lại xe và BALANCE `BOOKING:<id>:BALANCE`, collectorOwner/payerCustomer, dueAt=returnedAt+3600s. Không nhận xe thì không tạo BALANCE.
- P6.10 handler BALANCE riêng/defaultoff. QR15phút, cancel/expire chỉ attempt; quá hạn nợ vẫn thanh toán. OldQR settle outstanding, underpayment review không cộng, overpay EXCESS_BALANCE/duplicate Owner refund. Đã trả xe+đủ tiền tự COMPLETED cùng transaction apply; AFTER dispute giữ nguyên.
- Current Car→Booking→financial locks; notification/refund failure rollback toàn bộ. Marker balance_overdue_notified_at + notices Customer/Owner/Admin cùng commit; job cursor riêng qua executor/Car queue/defaultoff.
- UI/poll tách attempt/refund/debt/booking; receipt REVIEW_REQUIRED có nút hủy attempt riêng trước khi tạo lại. GET read-only, POST tạo lại; completed không hiển thị QR cũ.
- Paid cancellation vẫn bị chặn, thuộc P6.11; không mở manual complete VIETQR_V1.

## P6.11 cần hoàn thành đủ5checklist

1. PENDING chưa nhận advance: cancel intent/booking, không refund giả; receipt về sau hoàn đủ.
2. CONFIRMED chưa giao: Customer dùng quote P6.2, Owner hoàn đủ; canceledAt/fee/reason/nghĩa vụ hoàn cùng commit, payout ngoài transaction qua core Phase4.
3. Sau handover chặn cả hai bên, không dùng cancel để xóa nợ; phải return.
4. UI hiển thị phí/hoàn dự kiến, backend tính lại khi submit, không tin quote client.
5. Test biên1giờ/3ngày/date đã qua, payout chờ recipient/funds; payment gốc vẫn ghi nhận tiền đã nhận, không bulk REFUNDED trước provider success.

**Seam:** BookingService.cancelBooking/cancelByOwner, RentalPolicy/CancellationQuote, RefundService, receipt ADVANCE/expiry/cancel callbacks và UI. Reuse core inbox/idempotency/payOS; không pipeline thứ hai. Khóa Car trước Booking/financial; recheck current actor và paid/handover sau snapshot cũ. Race cancel-vs-receipt/cancel-vs-handover/Customer-vs-Owner, rollback notification/refund, stale RR và duplicate request phải được kiểm chứng bằng hai connection MySQL.

## Kiểm chứng đợt trước và gate đợt mới

**Kiểm chứng source cuối:** isolated clean verify **783/783 JUnit**, 0 failure/error/skip, JAR; Chromium **63/63**, 19 PNG320/390/1440/no-JS và 12 SQL invariant. Targeted mới37/37 đạt trước full gate. Lệnh:

```powershell
java '@.scratch/phase6/p610/full-suite.args'
# .\mvnw.cmd -f .scratch/phase6/p610/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=*Test' clean verify
```

POM/output/schema fixture riêng; source/resources thật. Evidence `.scratch/phase6/p610/`: `full-clean-verify.log`, `targeted-green.log`, `verified-source-hashes.json`, `browser-evidence.json`, `browser-source-manifest.json`, `browser-sql-invariants.txt`, `browser-preservation.json`, `protected-checks.json`. Browser dùng HTTP/inbox/domain thật trong fixture offline, không mock endpoint nghiệp vụ. Provider/live chưa được kiểm chứng.

**Bảo toàn và giới hạn:** DB ứng dụng vẫn7bảng, fingerprint schema/rows và phase0/seed/ảnh không đổi; schema/private files/port fixture được dọn. Flags ADVANCE/BALANCE/deadlines chỉ bật fixture, cấu hình mặc định ứng dụng không đổi. Không migrate DB ứng dụng, restart8080, tiền thật hoặc stage/commit/push. Có phân công agent độc lập và review; chưa có human reviewer `thanhmit`. P4.2-A giữ pending.


Không dùng số test/browser P6.10 để chứng minh source P6.11. Tạo evidence mới `.scratch/phase6/p611/`; không ghi đè p610. `update-docs.py` P6.10 đã chạy một lần, không chạy lại. Full clean verify/browser source cuối, bảo toàn DB/private files, cập nhật SDD/checklist/handoff rồi dừng trước P6.12.

## Phân tích triển khai P6.11 sau tích hợp

**Nền có sẵn:** `endUnpaidAdvance` hủy PENDING chưa nhận tiền và đóng intent/obligation; `applyAdvance` tạo hoàn đủ cho receipt về sau. `RentalPolicy.cancellation` và `RefundService` đã có quy tắc phí, lý do CUSTOMER_CANCEL/OWNER_CANCEL, idempotency và nguồn hoàn Owner. Paid cancellation vẫn bị chặn; Owner chưa có endpoint cancel.

**Quy tắc tái sử dụng:** Owner hoàn đủ advance. Customer hủy từ paidAt đến đúng một giờ: phí0; sau đó ngày nhận cách ngày hủy hơn3ngày lịch Việt Nam: phí10% advance; còn đúng3ngày hoặc ít hơn, kể cả ngày nhận đã qua nhưng chưa giao xe: phí50%. Làm tròn xuống VNĐ; refund=advance-fee. Không tính grace từ lúc đặt hoặc Owner accept.

**Khoảng thiếu:** nối Customer paid cancel và `cancelByOwner(id,reason,actor)` vào receipt ADVANCE đã phân bổ; thêm quote chỉ đọc và UI cả hai bên. Booking hiện có cancelledAt/cancellationFee nhưng thiếu trường lý do hủy riêng: thiết kế snapshot lý do trước khi sửa, tránh ghi đè ownerNote. Nếu cần thêm cột, chuẩn bị DB-first/fixture, không chạy migration ứng dụng. Recheck actor ACTIVE/role sau Car→Booking; request không được truyền fee/refund/actor/time có hiệu lực.

**Thứ tự:** (1) test paid cancel và các race; (2) transaction domain ghi cancellation snapshot + nghĩa vụ hoàn + notices; (3) Owner endpoint qua Car queue; (4) quote/UI; (5) full clean/browser/SDD mới. Tái sử dụng core refund/payout, giữ nhánh legacy; không gọi provider trong transaction.

**Điểm nghiệm thu:** cancel thắng trước receipt thì receipt đến sau hoàn đủ; receipt thắng trước thì hủy áp policy paid. Cancel thắng trước handover thì không giao; handover thắng trước thì không hủy/hoàn. Customer/Owner hủy đồng thời không đổi snapshot của bên thắng hoặc tạo hoàn lần hai. Refund/notification lỗi rollback toàn bộ; tiền gốc vẫn ghi nhận đã nhận, refund pending không phải refunded. Cập nhật test cũ đang kỳ vọng receipt thắng thì paid cancel bị từ chối. Kiểm chứng biên1giờ/3ngày, UTC/ngày Việt Nam, stale RR, hai connection, CSRF/quyền và no-JS/mobile.

## Giới hạn cho lần triển khai tiếp theo

Không migrate DB ứng dụng; DB-first scriptDB.sql/ddl-auto=none. Không bật workflow/provider/scheduler ứng dụng, restart8080, chuyển tiền thật hoặc tự stage/commit/push. Flags chỉ fixture riêng; P4.2-A live chưa đạt. Không đóng P6.12/P6.13 từ kết quả P6.11.
