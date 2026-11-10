# Bộ tài liệu CarRent trạng thái đích

Phiên bản 3.0 — 10/11/2026.

Đặc tả hệ thống CarRent khi hoàn tất toàn bộ phạm vi đã chốt. Đây là chuẩn đầu ra và chuẩn nghiệm thu, không phải biên bản xác nhận đã chạy production hoặc đã chuyển tiền thật.

## Tài liệu chính

- [SRS](CarRent_Software_Requirement_Specification.docx): 40 use case, 20 nhóm FR, 30 BR, 10 NFR, luồng và điều kiện nghiệm thu.
- [SDS](CarRent_Software_Design_Specification.docx): kiến trúc, 20 bảng, khóa/transaction, storage, 78 route và vận hành.
- [AI Usage Report](CarRent_AI_Usage_Report.docx): vai trò AI, nguồn hoạt động, giới hạn đóng góp và câu hỏi kỹ thuật tham khảo.
- [Tracking](tracking.xlsx): CRUD, trách nhiệm, mã FR/UC, thiết kế, source và testcase liên quan.
- [Testcase](testcase.xlsx): 147 ca có điều kiện trước, bước chạy và Expected Result; thống kê bằng công thức.

## Cách đọc trạng thái

Tracking ghi rõ “Hoàn thành ở trạng thái đích”. Đây là mô tả khi toàn bộ phạm vi được hoàn tất, không phải kết luận nghiệm thu source đang chạy.
Các vòng kiểm thử khởi tạo Pending vì phiên này chưa chạy ứng dụng. Người chạy điền Actual Result, kết quả, ngày, tester và đường dẫn evidence. Chỉ ghi Passed sau khi có kết quả thực. Mục tiêu coverage là 100%; thực thi thực tế được công thức thống kê riêng.
Thông tin cá nhân, mức đóng góp A/B/C/D và ký nghiệm thu không được suy từ tên GitHub hoặc tự điền thay người nộp.

## Phạm vi đã đồng bộ

Workflow VIETQR_V1 30/70, BEFORE/AFTER, Owner xác nhận hợp đồng/giao/nhận, tự hoàn tất đủ điều kiện; SePay thu, payOS hoàn đúng nguồn Owner/Admin; lookup đích có credentials riêng; gói Owner/quota; dashboard và giám sát.
LEGACY được giữ riêng. Payment Settings S1/S2/S3 đã CANCELLED theo thiết kế hiện hành và không nằm trong trạng thái đích. Lệch số tiền advance giữ REVIEW_REQUIRED. Không tự thêm e-sign, phụ phí, tự phạt, WebSocket hoặc self-service provider onboarding.

## Nguồn phân tích

`pom.xml`, `scriptDB.sql`, `src/main/java`, `src/test`, `docs/plans/README.md`, `docs/decisions.md`, handoff lookup/refund ngày 09/11 và thông báo hủy Payment Settings. GitNexus chưa có index CarRent nên phân tích dựa trực tiếp trên file hiện tại. Working tree chưa commit; tài liệu không gắn mọi nội dung với riêng HEAD.

NFR-10 yêu cầu báo cáo đo tải có môi trường, dữ liệu, độ trễ, tỷ lệ lỗi và invariants. Tài liệu không tự đặt SLA thương mại hoặc tuyên bố số đo chưa thực hiện.

## Kiểm tra và bàn giao

Đã kiểm cấu trúc và truy vết 40/40 use case, 20/20 nhóm FR, 30 BR, 10 NFR và 147 testcase; không có ID trùng hoặc liên kết source thiếu. 20 bảng trong SDS được đối chiếu cột/kiểu với SQL, gồm DATETIME(6).

Word đã cập nhật mục lục và số trang, xuất/render để rà bố cục: SRS 22 trang, SDS 32 trang, AI report 7 trang. Ảnh/PDF QA giữ ngoài thư mục bàn giao.

Excel native mở được hai workbook và tính lại đúng 147 tổng, 147 Pending, 0 Passed. Đổi thử một ca sang Passed cập nhật tổng thành 1/146 rồi khôi phục; Round 2 độc lập Round 1. Cả 40 liên kết ở danh mục dẫn đến đúng testcase; tham chiếu mẫu lỗi và dropdown không liên quan đã được loại bỏ.

Đối chiếu hash xác nhận 455 file source/docs/config nền không đổi và 5 bản gốc còn nguyên. Không chạy Maven, browser ứng dụng, DB migration hoặc provider live trong tác vụ tài liệu này.
Bản gốc của 5 file được bảo toàn tại `.scratch/tailieu-20261110/originals` ở máy local; script và ảnh QA nằm ngoài thư mục bàn giao.
Để đạt nghiệm thu phần mềm 100%, phải thực hiện toàn bộ điều kiện SRS IV, gồm local regression, migration/restore, entitlement/funding/provider, giao dịch thật đối soát, human review và runbook. Không được lấy việc hoàn thiện bộ tài liệu thay các gate này.

Ma trận CRUD mô tả các bảng nghiệp vụ chính; không lặp mọi SELECT xác thực dùng chung trên từng dòng.
