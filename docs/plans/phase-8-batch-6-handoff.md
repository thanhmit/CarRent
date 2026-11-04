# P8.8 — bàn giao đợt6, local hoàn tất / gate thật pending

**Checkpoint P8.8 local — 2026-11-04:** hoàn tất phần triển khai/kiểm chứng local: cặp Owner/Admin pilot mặc định từ chối, chặn tạo/reuse và ẩn QR ngoài phạm vi; receipt/refund/cancel/đối soát giữ nguyên. Full isolated **clean verify957/957 JUnit, 0 failure/error/skip**, JAR; Chrome **110/110**, 45PNG. E2E Small/quota/pending/expiry/gia hạn/Business→Small/manual selection và duplicate/late refund dùng provider offline. **P8.8 chưa đóng**: chờ migration/human review, P4.2-A, giao dịch/hoàn tiền thật và cho phép kích hoạt. Tiến độ giữ **75/86 task**, còn11. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.8 local chưa commit trên feature `705f320`/main `6948664`; DB ứng dụng/phase0/config/seed/uploads giữ nguyên. Không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push.

## 1. Baseline và quyền thao tác

- Repo `C:\Users\Admin\Documents\hoc\Car`; branch `feat/phase6-rental-gate`.
- HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`; index giữ nguyên. P8.1–P8.8 local chưa commit; phase0 riêng phải bảo toàn, không reset/checkout làm mất dirty changes.
- Phụ trách `ducbinhnguyen07ars`; reviewer `thanhmit`, `huy69332` review Car/Booking khi liên quan. Agent review không thay human review.
- [P8.8 checklist](phase-8-owner-subscriptions.md#task-p88--gate-tích-hợp-gói-và-kích-hoạt-demo-một-owner), [SDD2.49 §14.44](../SDD.md#1444-p88--tích-hợp-local-và-giới-hạn-demo-2026-11-04), [runbook](phase-8-pilot-runbook.md).

## 2. Đã triển khai/kiểm chứng local

- PaymentPilot default0/0 từ chối, chỉ cho một Owner/Admin đúng ID. Gate trước active reuse và shared ACTIVE QR view; không sửa lifecycle hoặc thêm schema/dependency. Fixture opt-in2/1, browser3/1 trên schema dùng riêng. Không thay cấu hình ứng dụng.
- Không bắt Owner payer liên kết incoming/payout provider để mua gói. Channel/provider và current role/ownership/CSRF vẫn kiểm tra theo Phase4.
- Gỡ approval không sửa intent/nợ/receipt/refund; QR cũ đã sao chép vẫn có thể nhận tiền. Test xác nhận receipt đầu apply, duplicate hoàn đúng Admin, valid_until không cộng hai lần, cancel/reconcile không bị pilot chặn.
- First full run:957 tests/2 fixture errors; reproduced and corrected Owner3 historical paid entitlement setup without relaxing pilot admission. Focused rerun31/31. Targeted17/17; final full isolated clean verify957/957,0failure/error/skip,JAR; Chrome110/110,45PNG320/390/1440 và no-JS native forms. Không khẳng định responsive styling no-JS vì Tailwind CDN cần JavaScript.

- E2E Small/quota/pending/5th fail/expiry/renewal/Business16→Small/manual4/duplicate/late/Admin WAITING_FUNDS; booking/rental history/evidence giữ nguyên. Provider signed/fake offline, không mock business endpoints.
- Evidence `.scratch/phase8/p88`: `verification-summary.json`, full/targeted/RED/intermediate logs, `pilot-audit.md`, `review.md`, tested/review/browser manifests, browser SQL invariants/PNG/visual review. Runner `java '@.scratch/phase8/p88/full-suite.args'` tạo/dọn schema MySQL ngẫu nhiên và gọi Maven Wrapper clean verify bằng POM/output/datasource riêng. DB ứng dụng7bảng,14protected files,seed/config/uploads/phase0,HEAD/main/index giữ nguyên.

## 3. Checklist nào còn chờ

| P8.8 | Local | Điều kiện còn thiếu |
| --- | --- | --- |
| Clean verify + baseline theo migration được duyệt | Full suite/JAR và migration/baseline fixture đạt | Human review migration vận hành/backup/restore trên môi trường đích |
| E2E Small/quota/expiry/renewal/downgrade | Đạt, tick local | Không thay bằng chứng live ở mục3 |
| Hai lần chuyển thật và late refund | Fake/signed fixture đạt | Người dùng chủ động chuyển thật; receipt/activation/refund đối soát được |
| Một Owner/Admin đủ kênh và kích hoạt | Gate identity/QR đã có, mặc định từ chối | P4.2-A, kênh/lookup/phí/nguồn tiền được duyệt, human review và cho phép kích hoạt |
| SDD/decisions/public visibility trước entitlement gate | Đạt local, tick | Chưa bật entitlement gate ở DB ứng dụng |

P8.8 chưa đóng, **75/86 task**, còn11: `ducbinhnguyen07ars`8, `huy69332`2, `thanhmit`1. Không gọi Phase8 hoàn tất hoặc tăng tiến độ vì fixture pass.

## 4. Lần tiếp theo

- Tiếp tục gate P8.8 theo runbook; P4.2-A phụ trách `thanhmit`, reviewer `huy69332`. Không yêu cầu gửi secrets vào chat, không tự gọi provider/lookup/payout thật khi chưa có phạm vi được cho phép.
- Giữ mặc định từ chối cho tới khi đủ điều kiện và có cho phép cụ thể. Không tự migrate/reset/seed DB ứng dụng, bật flags/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push.
- Khi gate thật đủ mới đóng P8.8. Đợt code sau theo lịch là P5.1 + P5.2, chưa triển khai ở đây; không âm thầm đổi thứ tự để vượt gate.
