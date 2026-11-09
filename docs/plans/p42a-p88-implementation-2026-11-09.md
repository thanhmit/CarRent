# P4.2-A/P8.8 — Implementation Plan

> **For agentic workers:** dùng `superpowers:executing-plans`, TDD và verification-before-completion; tiếp tục tại root dirty đã có manifest, không Git mutation. Không đóng gate provider/live chỉ từ test local.

**Goal:** Hoàn thiện phần triển khai lookup người nhận và đường nghiệm thu P4.2-A/P8.8 theo phân tích đã được người dùng yêu cầu thực hiện; chỉ rõ đầu vào còn thiếu để chạy thật.

**Architecture:** Giữ RefundRecipientService/Transactions, RefundDestinationVerifier interface và PayoutWorker. Thêm adapter VietQR độc lập, cấu hình riêng mặc định tắt, không tái dùng khóa payout. Xác minh real qua profile chẩn đoán chỉ lookup, không tạo refund/attempt hoặc bật worker. Giữ UNKNOWN query-only khi chưa có guarantee NOT_SENT; runbook xử lý ca treo và evidence hai nguồn.

**Tech Stack:** Java21, Spring Boot4.1.1, RestClient/JDK HttpClient, Jackson3, JUnit/MockRestServiceServer, MySQL fixture UUID riêng. Không dependency hoặc schema mới.

**Spec:** [Phân tích 09/11](refund-completion-analysis-2026-11-09.md), [gate research](refund-gates-research-2026-11-09.md), [contract được GET lại](p42a-p88-implementation-contract-2026-11-09.md), checklist gốc `phase-4-payment.md` và `phase-8-owner-subscriptions.md`.

## Global Constraints

- Bảo toàn dirty baseline `.scratch/p42a-p88-implementation-20261109/baseline.json`; không sửa private config, `.env`, DB ứng dụng, root POM hoặc runtime8080 trong phần code/test.
- Không gửi provider support message, thử payout, bật kênh/job hoặc chuyển tiền khi chưa có khoản tiền/kênh/đích và phê duyệt cụ thể. Không hỏi secret/OTP trong chat.
- Dùng contract VietQR public; mặc định tắt, chỉ vận hành sau khi provider xác nhận entitlement áp dụng. Không bịa CAS endpoint hoặc dùng payout/estimate-credit/balance làm lookup tên.
- BIN6 chữ số, số tài khoản numeric6–19 theo bảng input VietQR hiện tại; số tài khoản luôn string. Form/domain1–50 giữ nguyên cho tương thích; adapter riêng từ chối phạm vi không hỗ trợ.
- HTTPS fixed host, không redirect, connect/read timeout, không log request/response/credential; response HTTP/code/name sai đều fail-closed. Request/response pairing giữ đúng BIN/STK đã gửi, do provider không echo.
- Mỗi khoản hoàn vẫn preview và submit xác minh lại; default account không tự cấp quyền chi. Hạn READY+1h, role/CSRF/locking/snapshot/idempotency không đổi.

## Task 1 — Gated VietQR recipient verifier

Files: tạo `payment/provider/RefundLookupProperties.java`, `payment/provider/HttpVietQrRefundDestinationVerifier.java`; sửa `config/PaymentProviderConfiguration.java`, `application.properties`, `.env.example`; thêm test adapter/config và HTTP recipient integration; bảo vệ fixture bằng override lookup disabled khi không có test-specific verifier.

Consumes `RefundDestinationVerifier.verify(Long,String,String)`; produces Destination gồm chính BIN/STK request và tên chuẩn provider. Cấu hình prefix `carrent.payment.recipient-lookup`, chỉ enabled/client-id/api-key; record redacts credentials.

- [x] RED: enabled thiếu key không khởi động; HTTP success giữ số0 đầu, lỗi/malformed/timeout/redirect không pass, input unsupported không network; no credential disclosure.
- [x] GREEN: implement property + adapter + conditional default fallback, cấu hình/env example không secret. Không network lúc startup thường.
- [x] HTTP recipient integration: preview không ghi tiền; confirm relookup và tên đổi phải chặn; xác nhận đúng mới READY/deadline, payout vẫn0; source/recipient rights giữ nguyên.

## Task 2 — Chẩn đoán lookup thật không chi tiền

Files: `payment/LocalRefundLookupCheck.java`, `application-refund-lookup-check.properties`, scheduler profile exclusions tương ứng, tests CLI isolation. Inputs private BIN/STK/tên kỳ vọng và lookup credentials; enabled phải được vận hành chấp thuận.

- [x] RED: refuse scheduler enabled, missing/invalid input; exact-name check lỗi không lộ PII, không ghi DML hoặc gọi payout; normal startup không chạy diagnostic.
- [x] GREEN: profile explicit, port0, financial/domain flags off; output chỉ VERIFIED/FAILED, không tên/STK/key. Diagnostics không cần seed refund hoặc enable payout.
- [x] README/operator instructions và profile isolation regression.

## Task 3 — Recovery và bộ đầu vào nghiệm thu

- [x] Runbook query-only UNKNOWN, cảnh báo/owner/SLA/thu evidence; không tự mark success hoặc resend; REVIEW_REQUIRED và nguồn bị tắt phải có xử lý vận hành.
- [x] Tạo template private để người dùng cung cấp lookup entitlement/config path, source chung/riêng, người nhận, khoản tiền/budget, provider capability, SePay v1 evidence và review. Blank không được xem như đạt.
- [x] P8.8: quy trình Small → hai bank transactions khác nhau cùng order → một entitlement và hoàn trùng; late order không entitlement và hoàn đủ; đối chiếu cả nguồn Owner/Admin cần dùng.
- [x] Full isolated `clean verify` trên production source cuối; test config bổ sung sau review được chạy focused riêng. Node + browser liên quan; source/index/private-config/runtime preservation audit; [bàn giao kết quả](p42a-p88-implementation-handoff-2026-11-09.md) tách riêng từng gate còn thiếu.

## Task 4 — Gate vận hành có phụ thuộc bên ngoài

- [ ] Lookup được cấp quyền và verified với người nhận cụ thể.
- [ ] Quyền chi/tự duyệt/min-max/phí/funding và phạm vi nguồn chung/riêng có bằng chứng.
- [ ] Source/JAR/schema + baseline/restore được reviewer duyệt; không replay one-shot migration khi schema đã tồn tại.
- [ ] Phê duyệt/kích hoạt scope cụ thể, người dùng thực hiện giao dịch và đối soát thực tế; evidence/checklist từng mục P4.2-A/P8.8 được reviewer xác nhận.

Không ghi Task4 đạt khi còn thiếu input/provider/human evidence. Hoàn thiện Task1–3 là phần kỹ thuật có thể triển khai độc lập trong lúc chờ thông tin.
