# Phase5 đợt5 — P5.7 Dashboard Owner và trang chủ

**Checkpoint P5.7 — 2026-11-04:** hoàn thành **4/4 checklist local** dashboard Owner và trang chủ: gói/hạn/quota, xe/booking scoped principal, tiền thuê đã phân bổ và hoàn/nợ riêng; hoàn một phần giữ receipt, pending chưa tính đã hoàn, deadline theo Clock. Home tối đa8 xe mới nhất dùng public policy Phase8 hiện có; quyền preview riêng được giữ. Current ACTIVE OWNER/stale session/IDOR/XSS và GET chỉ đọc đã kiểm chứng. Focused **33/33**, full isolated **clean verify 1016/1016 JUnit, 0 failure/error/skip**, JAR; Chrome **152/152**, 36PNG ở1440/390/320; SQL invariants/provider0/cleanup đã xác nhận. **82/86 task**, còn **4** (`ducbinhnguyen07ars`3, `thanhmit`1; `huy69332`0). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. **P8.8/P4.2-A live vẫn pending**. Tiếp theo **P5.8 → P5.9**, không đóng P5.9 khi gate thật chưa đạt. Bảo toàn P5.5/P5.6 chưa commit, phase0/config/seed/uploads/DB ứng dụng/refs/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge.

## Baseline và bảo toàn

- Branch `feat/phase5-profile-password`, HEAD/main/origin-main `b829b059a2f1af3c9ba79629759b608036470620`; không Git publication. P5.5/P5.6 đã triển khai nhưng chưa commit, giữ byte toàn bộ source/test/profile.
- `docs/plans/phase-0-init.md` dirty từ trước, giữ nguyên; config/seed/uploads, DB ứng dụng7bảng, refs và index không đổi. Chỉ bootstrap/migrate fixture trên schema tự sinh, random port, provider offline.
- Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; review agent không thay human contributor review.

## Nghiệm thu local4/4

1. Dashboard gói/hạn/quota, xe public/pending/chủ động ẩn, booking, tiền thuê đã phân bổ, hoàn thành công/đang chờ/quá hạn, nợ70%/quá hạn. Tái dùng read model/policy, legacy thiếu schema hiển thị unavailable rõ ràng.
2. Principal scope/current ACTIVE OWNER; không nhận ownerId để đổi scope. Không cộng phí gói hoặc legacy PAID vào tiền thuê; GET không mutation/enqueue/provider.
3. OwnerRevenueTest kiểm tra cách ly Owner, partial/multiple refunds, pending payout, liên kết sai, stale role/status, IDOR/XSS và biên deadline theo Clock. SQL/browser xác nhận độc lập.
4. Home tối đa8xe mới nhất, public predicate Phase8 chung search/detail; hết gói ở exact boundary mất public, private preview vẫn đúng quyền.

## Evidence

- `.scratch/phase5/p57/verification-summary.json`, `implementation-report.md`, `acceptance.md`, `review.md`, `standards-review.md`, `root-visual-review.md`, `focused-reports`, `full-clean-verify.log`, `full-run-result.json`, `build/carrent-0.0.1-SNAPSHOT.jar`.
- Source/protected/change manifests, app DB fingerprints, schema inventories; `browser/report.md`, Chrome PNG, `browser-evidence.json`, SQL invariants/provider calls và cleanup. Các lượt lỗi trung gian được giữ riêng; xem implementation report và browser report, không coi là pass.
- Chưa có bằng chứng migration/human/provider/live mới; không bật flags/provider/scheduler ứng dụng, restart8080 hay chuyển tiền thật.

## Tiếp theo

**82/86**, còn4: `ducbinhnguyen07ars`3(P8.8/P5.8/P5.9), `thanhmit`1(P4.2-A), `huy69332`0. Tiếp theo **P5.8 → P5.9**; P5.9 chỉ đóng khi gate thật đủ bằng chứng. P8.8 giữ2/5local và3gatepending; P4.2-A live pending. Dừng sau P5.7.
