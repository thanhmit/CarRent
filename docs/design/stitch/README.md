# CarRent — Prototype Stitch: tham chiếu UI

**Nguồn:** Google Stitch project "Giao diện Website CarRent" (id `8644489408448168146`).
**Hiện có local:** 13 HTML + 13 screenshot (2026-10-10), sau khi xóa các prototype cũ theo lựa chọn của chủ dự án. File local là nguồn chân lý khi implement — link Stitch sẽ hết hạn.
**Quyền truy cập MCP:** đã cấu hình server `stitch` (HTTP + API key) ở user scope trong `~/.claude.json`.

> Khi code bất kỳ template Thymeleaf nào: mở file HTML tương ứng trong thư mục này, **port markup giữ nguyên class**, thay phần tĩnh bằng Thymeleaf (th:each/th:if/th:href/th:action). Đặc tả đầy đủ ở SDD §8.

---

## 1. Design system (trích từ tailwind.config trong các file HTML)

Mọi trang phải khai báo đúng bộ assets + config này trong `<head>` (copy nguyên từ bất kỳ file nào ở đây):

```html
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@100..900&display=swap" rel="stylesheet"/>
<link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=block" rel="stylesheet"/>
<script src="https://cdn.tailwindcss.com"></script>
<script>/* dán block tailwind.config = ... nguyên văn từ prototype */</script>
<style>
  body { font-family: 'Inter', sans-serif; }
  .material-symbols-outlined { font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24; }
</style>
```

### Màu (Material Design 3 tokens — bộ chính)

| Token | Hex | Dùng cho |
|---|---|---|
| `primary` | `#001632` | text thương hiệu, text quan trọng, nền avatar |
| `secondary` | `#0051d5` | nút hành động chính (CTA), icon nhấn mạnh |
| `secondary-container` | `#316bf3` | hover của CTA |
| `primary-container` | `#142b4a` | nền đậm section đặc biệt |
| `background` / `surface` | `#f9f9ff` | nền trang |
| `surface-container-lowest` | `#ffffff` | nền card, input |
| `surface-container` / `-low` / `-high` / `-highest` | `#e8eeff` / `#f0f3ff` / `#dfe8ff` / `#d9e3fb` | nền phụ, hover nav |
| `on-surface` | `#111c2d` | chữ chính |
| `on-surface-variant` | `#44474e` | chữ phụ |
| `on-secondary` | `#ffffff` | chữ trên CTA |
| `outline` / `outline-variant` | `#74777e` / `#c4c6ce` | viền, chữ mờ |
| `error` / `error-container` | `#ba1a1a` / `#ffdad6` | lỗi, trạng thái từ chối |
| `tertiary` | `#001c06` | nhấn xanh lá (hiếm) |

### Typography (font Inter)

| Token | Cỡ/Line | Weight |
|---|---|---|
| `display-hero` | 40/48 | 700 (h1 hero; mobile 30/38) |
| `headline-lg` | 32/40 | 700 (tiêu đề trang; mobile 24/32) |
| `headline-md` | 24/32 | 600 |
| `headline-sm` | 20/28 | 600 |
| `title-md` | 16/24 | 600 (tiêu đề card, item) |
| `body-lg` / `body-md` | 16/24 / 14/20 | 400 |
| `label-md` / `label-sm` | 14/20 / 12/16 | 500 (label form, nút) |
| `caption` | 12/16 | 400 |

### Kích thước & bo góc

- Radius: `DEFAULT` 0.125rem · `lg` 0.25rem (input, nút) · `xl` 0.5rem (card, widget) · `full` 0.75rem (pill).
- Spacing tokens: `space-xs` .25rem, `space-sm` .5rem, `space-md` 1rem, `space-lg` 1.5rem, `space-xl` 2.5rem; `gutter` 1.5rem (padding trang), `margin` 2rem.
- Icon: Material Symbols Outlined, cỡ hay dùng 18/20px, màu theo ngữ cảnh (secondary cho icon label).

## 2. Hai layout chuẩn

**Layout A — Navbar top (trang public/customer):** header fixed `h-16`, `bg-surface-container-lowest/95 backdrop-blur`, container `max-w-7xl mx-auto px-gutter`; logo CarRent + nav (Trang chủ, Tìm xe, Đơn thuê của tôi, Dành cho chủ xe) + Tài khoản (avatar tròn bg-primary); `main` có `pt-16`. Xem `home-trang-chu.html`.

**Layout B — Sidebar trái (trang Owner/Admin):** sidebar `Cổng thông tin Điều hành & Quản trị` (owner: `owner-quan-ly-xe.html`) / `Cổng thông tin` (admin: `admin-quan-ly-nguoi-dung.html`, `admin-duyet-xe.html`) với nav dọc icon + nhãn (Tổng quan, Quản lý xe, Duyệt xe, Đơn thuê, Doanh thu, Quản lý người dùng...), nút "Xem trang khách", nội dung bên phải có heading + stat cards + table. SDD map sidebar item theo role thật của app (SDD §8.5).

## 3. Component đối chiếu nhanh

- **Car card:** ảnh + tên + các dòng thông số icon (`airline_seat_recline_normal` chỗ, `settings` hộp số — bản prototype dùng `auto_transmission`/`settings`, `local_gas_station`/`electric_car` nhiên liệu) + giá "X VNĐ/ngày" + nút "Xem chi tiết". Xem `home-trang-chu.html`, `cars-tim-xe-2.html`.
- **Search widget (home):** card trắng `rounded-xl shadow-md`, grid 12 cột: select địa điểm + 2 input date + nút CTA "Tìm xe ngay" (`bg-secondary text-on-secondary`). Gợi ý pill bên dưới.
- **Filter sidebar (Tìm xe):** "Bộ lọc tìm kiếm" + radio/checkbox nhóm (Khoảng giá, Số chỗ, Hộp số, Loại nhiên liệu) + nút Áp dụng/Đặt lại. Xem `cars-tim-xe-2.html`.
- **Bảng quản trị (owner/admin):** table các cột + cột Thao tác (nút icon `visibility` Xem, `lock` Khóa...), chip trạng thái. Xem `owner-quan-ly-xe.html`, `admin-quan-ly-nguoi-dung.html`.
- **Form:** label `font-label-md` + icon secondary bên trái, input `h-11 bg-surface-container-lowest rounded-lg shadow-sm`, select có icon `expand_more` absolute phải.

## 4. Mapping màn hình → template → task

| File prototype | Route SDD | Template | Task plan |
|---|---|---|---|
| `home-trang-chu.html` | `/` | `home/index.html` | P0.3 |
| `cars-tim-xe-2.html` | `/cars` | `cars/list.html` | P2.12 |
| `cars-chi-tiet-xe-dat-thue.html` | `/cars/{id}` | `cars/detail.html` | P2.13, P3.6 |
| `booking-xac-nhan-dat-thue.html` | `/bookings/new` | `booking/create.html` | P3.6 |
| `booking-don-thue-cua-toi.html` | `/bookings` | `booking/list.html` | P3.7 |
| `booking-chi-tiet-don-thue.html` | `/bookings/{id}` | `booking/detail.html` | P3.7 |
| `payment-thanh-toan.html` | `/bookings/{id}/payment` | `payment/mock-payment.html` | P4.3 |
| `auth-dang-nhap.html` | `/login` | `auth/login.html` | P1.6 |
| `auth-dang-ky.html` | `/register` | `auth/register.html` | P1.6 |
| `owner-quan-ly-xe.html` | `/owner/cars`, `/owner/dashboard` | `owner/cars.html`, `owner/dashboard.html` | P2.6, P5.7 |
| `owner-them-xe-moi.html` | `/owner/cars/new` (và edit) | `owner/car-form.html` | P2.7, P2.8 |
| `admin-duyet-xe.html` | `/admin/cars` | `admin/cars.html` | P2.9, P2.10 |
| `admin-quan-ly-nguoi-dung.html` | `/admin/users` | `admin/users.html` | P5.3, P5.4 |

**Chưa có prototype** (dựng theo cùng design system, layout A hoặc B tương ứng): `/notifications`, `/profile*`, `/admin/dashboard`, `/admin/bookings`, `/owner/bookings`, trang lỗi 403/404/500, empty states.

## 5. Quy tắc port HTML → Thymeleaf

1. Giữ nguyên mọi class Tailwind + icon; bỏ attribute `data-path` (inert).
2. Thay link tĩnh `href="#"` → `th:href="@{/route}"` theo mapping; form POST `action="#"` → `th:action`. Thymeleaf tự chèn hidden CSRF token khi dùng `th:action`: chỉ giữ một token cho mỗi form. Nếu chèn thủ công, dùng `${_csrf.parameterName}` cho tên field, không dùng `${_csrf.headerName}`.
3. Danh sách lặp: khối card/row → `th:each="car : ${cars}"`, nội dung text → `th:text`; ảnh prototype (URL googleusercontent) → `th:src="${thumbnails[car.id]}"` hoặc placeholder `@{/images/...}`.
4. Điều kiện hiển thị: badge trạng thái/nút thao tác → `th:switch`/`th:if` theo enum.
5. Navbar/sidebar đúng theo role đăng nhập (sec:authorize) — cấu trúc giữ nguyên của prototype, thay/nav item theo SDD §8.5.
6. Chuỗi tiếng Việt hiển thị giữ nguyên văn bản prototype; cập nhật số liệu (số xe, giá...) bằng dữ liệu thật.
7. Không đổi giá trị token trong `tailwind.config` — copy nguyên văn.
8. `static/css/custom.css` chỉ cho tinh chỉnh nhỏ (font smoothing, scrollbar) — mọi styling bằng token Tailwind.
