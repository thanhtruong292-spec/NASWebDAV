# UI/UX Domain Research — Phase 2 Gate

> Ngày: 2026-08-05 | Đối tượng: NASWebDAV Android Jetpack Compose | Chỉ nghiên cứu, chưa triển khai Phase 2

## 1. Nguồn và phạm vi

Đã chạy `ui-ux-pro-max` search trên các domain:

1. `ux`: animation, accessibility, loading, touch targets, dark mobile
2. `product`: professional NAS/file manager/storage operations/dashboard
3. `typography`: modern professional utility dashboard mobile sans serif
4. `style`: flat dark mode professional file manager, no gradient
5. `jetpack-compose`: Material3 tokens, dark theme, typography, semantics — đã chạy ở Phase 1

---

## 2. Kết quả theo domain

### 2.1 Product: File Manager & Transfer

Kết quả phù hợp nhất là:

- **Primary style:** Flat Design + Minimalism
- **Secondary:** Accessible & Ethical + Dark Mode (OLED)
- **Information model:** File-tree focused, không phải marketing dashboard
- **Color strategy:** Functional neutral + file-type color coding
  - Folder: blue
  - PDF/document: amber/blue
  - Image/media: purple/pink
  - Status: green/orange/red

**Quyết định:** Giữ file-type colors nhưng giới hạn trong icon/badge; không dùng làm toàn bộ card background. Điều này giữ hierarchy rõ và tránh màn hình bị nhiều màu.

### 2.2 Style: Flat Design Mobile

Các nguyên tắc áp dụng cho Phase 2:

- Không gradient trên card, tile, CTA hoặc status container
- Không shadow/elevation decoration
- Solid color blocks tạo hierarchy
- Radius nhỏ/vừa: 6–12dp
- Spacing rhythm: 4/8/16/24/32dp
- Touch target tối thiểu 48dp cho Android
- Press feedback: đổi alpha/color hoặc scale nhẹ, không làm layout shift
- Palette giới hạn khoảng 4–6 màu functional chính
- Animation 150–200ms, chỉ dùng cho state transition/loading

**Codebase check:** `MainMenuScreen.kt` không còn `Modifier.shadow`; `BrowserScreen.kt:573` vẫn có `CardDefaults.cardElevation(defaultElevation = 8.dp)`. Cần xử lý trong Phase 2 để đúng flat mode.

### 2.3 UX: Touch, loading, motion

Các rule ưu tiên cao:

1. Touch target ≥44–48dp
2. Khoảng cách giữa target kế bên ≥8dp
3. Async action phải có loading/disabled feedback
4. Không dùng hover-only interaction — Compose app đã dùng click/tap
5. Tránh gesture ngang cạnh tranh với system back gesture
6. Infinite animation chỉ dành cho loading/status hoạt động, không dùng decorative
7. Lazy-load danh sách/media dưới fold
8. Haptic chỉ cho confirmation quan trọng, không rung mỗi tap

**Áp dụng vào codebase:**

- Audit `IconButton`/`clickable` có visual size 16–20dp nhưng hit area chưa chắc 48dp
- Audit `rememberInfiniteTransition` trong dashboard: giữ nếu biểu thị trạng thái đang chạy; bỏ nếu chỉ decoration
- `LazyColumn`/`LazyVerticalGrid` đã là hướng đúng cho danh sách lớn

### 2.4 Typography: Mobile utility/dashboard

Search trả về 3 hướng khả thi:

| Hướng | Đánh giá |
|---|---|
| Inter single-family | Professional, technical, premium; gần với design system hiện tại |
| Plus Jakarta Sans | Enterprise/mobile-friendly, nhưng cần bundle font |
| Fira Sans + Fira Code | Tốt cho dashboard data/code, nhưng phức tạp hơn |

**Quyết định Phase 2:** tiếp tục dùng system Roboto, không thêm custom font. Áp dụng role tokens:

- Screen title: `headlineSmall`/`titleLarge`
- Section title: `titleMedium`
- Primary body: `bodyLarge`/`bodyMedium`
- Secondary metadata: `bodySmall` hoặc `labelMedium`
- Numeric/technical values: có thể dùng `FontFamily.Monospace` cục bộ, không đổi toàn app
- Không dùng body text dưới 12sp

Lý do giữ Roboto: không tăng APK size, không thêm asset-loading complexity, hỗ trợ Vietnamese tốt và đã có sẵn trên Android.

### 2.5 Dark mode (OLED) vs flat slate

Search style đưa ra OLED palette pure black/#121212, nhưng design system hiện tại đã chọn slate `#0F172A`/`#1E293B` để tạo phân cấp surface.

**Quyết định:** không chuyển về pure black toàn app. Slate dark flat giữ hierarchy tốt hơn cho card/file tree và tránh black-on-black ambiguity. Vẫn dùng solid surfaces, không gradient/shadow.

---

## 3. Codebase gap matrix sau Phase 1

| Rule | Hiện trạng | Phase 2 action |
|---|---|---|
| Flat cards/tiles | MainMenu đã chuyển phần chính; Browser còn elevation 8dp | Remove elevation ở Browser |
| 48dp touch target | Chưa audit toàn bộ | Audit interactive Icon/Button/compact rows |
| 8dp target spacing | Nhiều inline 2/4/6dp còn lại | Chuẩn hóa nhóm action cạnh nhau |
| Loading feedback | Có progress/spinner rải rác | Chuẩn hóa `LoadingState`/disabled CTA |
| Infinite animation | Có 16 usages | Phân loại functional vs decorative |
| Semantic colors | Phase 1 đã migrate một phần MainMenu | Migrate screen/dialog files còn lại |
| Typography roles | MainMenu đã migrate common 10–14sp | Migrate Browser/dialog/dashboard files |
| Accessibility labels | Nhiều `null` trên icon | Ưu tiên interactive icon trước decorative icon |
| Motion duration | Chưa có token chung | Thêm `MotionTokens` 150/200/300ms |
| File-type colors | Rải raw hex | Tạo `FileTypeColors` semantic object |

---

## 4. Phase 2 implementation boundary

### Làm ngay

1. Tạo `FileTypeColors` semantic object trong `Color.kt`.
2. Migrate raw color values trong các file UI theo nhóm:
   - surface/background
   - semantic status
   - file/media type
   - chart-only colors
3. Remove `CardDefaults.cardElevation` trong BrowserScreen để đúng flat design.
4. Migrate `fontSize`/`fontWeight` ở BrowserScreen, dialogs, DashboardCards, DashboardWidgets.
5. Audit interactive icon hit areas, đảm bảo min 48dp.
6. Fix `contentDescription` cho icon-only buttons; giữ `null` chỉ cho decorative icons.
7. Thêm motion duration tokens và giới hạn animation 150–300ms.

### Chưa làm trong Phase 2

- Custom Inter/Plus Jakarta font
- Navigation architecture rewrite
- Figma token sync
- Thay toàn bộ icon set
- Redesign layout information architecture
- Thay flat mode bằng OLED pure black

---

## 5. Acceptance criteria trước khi converge

- [ ] Không còn `CardDefaults.cardElevation`/shadow trong các card flat chính
- [ ] Không còn raw `Color(0x...)` cho surface/status ở file đã migrate
- [ ] Body text ≥12sp
- [ ] Interactive targets ≥48dp hoặc có minimum interactive component size
- [ ] Icon-only action có accessible label
- [ ] Không dùng gradient cho card/tile/CTA/status container
- [ ] Animation functional, 150–300ms; infinite animation chỉ cho active/loading state
- [ ] Build `kspDebugKotlin` + `compileDebugKotlin` pass
- [ ] `git diff --check HEAD` pass

## Kết luận

Kết quả search củng cố hướng đã chọn: **Flat Design + Minimalism + Dark Mode + Accessible/Ethical** là đúng product-fit cho NAS/file manager. Phase 2 nên tập trung token migration và interaction quality, không mở rộng sang custom font hay navigation rewrite.
