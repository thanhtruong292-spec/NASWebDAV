# Icon & Theme Research — NASWebDAV

> Ngày: 2026-08-05 | Stack: Android Kotlin + Jetpack Compose + Material3 | Status: Nghiên cứu, chưa migration

## 1. Kết luận ngắn

### Bộ icon được khuyến nghị

**Giữ Material Icons hiện tại làm nền tảng, nhưng chuẩn hóa sang explicit `Icons.Filled` / `Icons.Outlined` theo hierarchy. Không migrate toàn bộ sang Phosphor.**

Lý do:

- `Phosphor` là recommendation chính của database cho web/React Native, nhưng không phải dependency native Compose hiện tại.
- App đã có 543 references / 124 unique `Icons.Default.*`; đổi icon library toàn bộ sẽ tạo diff lớn, tăng dependency/asset risk và không đem lại lợi ích đủ lớn cho file-manager workflow.
- Material Icons phù hợp Android/Material3, có sẵn semantics, sizing, theming và không cần thêm dependency.
- `Icons.Default` hiện tương đương Filled style nhưng không diễn đạt rõ hierarchy. Đổi dần sang explicit variants giúp code dễ review và nhất quán hơn.

### Theme được khuyến nghị

**Dark Slate Flat / Material3-compatible**:

- Background: `#0F172A`
- Surface/Card: `#1E293B`
- Surface elevated: `#273449`
- Primary: `#2563EB`
- Secondary: `#8B5CF6`
- Success: `#22C55E`
- Warning: `#D97706`
- Error: `#DC2626`
- Text primary: `#F8FAFC`
- Text secondary: `#CBD5E1`
- Text tertiary: `#94A3B8`
- Border/outline: `#475569`

Giữ slate dark thay vì pure black OLED vì app có nhiều card, file rows và dashboard data cần surface hierarchy rõ. Không dùng gradient, glow hoặc shadow trang trí.

---

## 2. Nghiên cứu icon domain

UI/UX Pro Max icon search đề xuất Phosphor cho các semantic sau:

| Semantic | Phosphor recommendation | Compose equivalent nên dùng |
|---|---|---|
| Home/dashboard | `House` | `Icons.Filled.Home` |
| File | `File` | `Icons.Outlined.InsertDriveFile` hoặc `Icons.Filled.Description` |
| Folder | `Folder` | `Icons.Outlined.Folder` |
| Open folder | `FolderOpen` | `Icons.Outlined.FolderOpen` |
| Upload | `UploadSimple` | `Icons.Filled.UploadFile` / `CloudUpload` |
| Download | `DownloadSimple` | `Icons.Filled.Download` / `CloudDownload` |
| Database/storage | `Database` | `Icons.Filled.Storage` / `Dns` |
| Settings | `Gear` | `Icons.Filled.Settings` |
| Refresh/sync | `ArrowsClockwise` | `Icons.Filled.Refresh` / `Sync` |
| List/menu | `List` | `Icons.Filled.ViewList` / `Menu` |
| Back | `ArrowLeft` | `Icons.AutoMirrored.Filled.ArrowBack` |
| Close | `X` | `Icons.Filled.Close` |
| Expand | `CaretUp/Down` | `Icons.Filled.ExpandLess/ExpandMore` |
| External link | `ArrowSquareOut` | `Icons.Filled.OpenInNew` |

**Không nên literal-port icon names** từ Phosphor vì Compose app không dùng React icon packages. Bảng trên là semantic mapping, không phải dependency migration.

---

## 3. Audit icon hiện tại

| Metric | Kết quả |
|---|---:|
| Total `Icon(...)` calls | ~380 |
| Material icon references | 543 |
| Unique icons | 124 |
| `Icons.Default.*` | 100% |
| Explicit Filled/Outlined/Rounded/Sharp/TwoTone | 0 |
| `contentDescription = null` | 253 |
| Distinct icon sizes | 32 |
| Common icon sizes | 18, 20, 24dp |
| String-resource labels | ~20 interactive controls |
| Hardcoded Vietnamese labels | 5+ |

### Icon style problem

`Icons.Default.*` mặc định là Filled-style, nên app hiện có một visual language duy nhất nhưng không có hierarchy:

- Navigation/action/status/file icons đều dùng cùng weight.
- Secondary actions không được phân biệt bằng Outlined style.
- Có nhiều size ngoài scale: 6, 8, 10, 11, 13, 17, 25, 26, 34, 38, 46, 65, 120, 140, 180dp.
- Raw icon tint rải rác: amber, blue, indigo, teal, orange và grey gần trùng semantic tokens.

### File type behavior

Hiện gần như không có file-extension → icon dispatch:

- Folder: `Folder`/`FolderOpen`
- File: `InsertDriveFile`
- Extension checks chủ yếu quyết định preview/action, không đổi icon.

Đây là behavior ổn cho Phase hiện tại. Không cần tạo 20 icon file-type ngay; chỉ nên thêm semantic category icon sau khi token system ổn định.

---

## 4. Icon system đề xuất

### 4.1 Hierarchy

| Layer | Style | Size | Ví dụ |
|---|---|---:|---|
| Primary navigation / active state | Filled | 24dp | Home, Folder, Storage |
| Secondary action | Outlined | 20–24dp | Search, Filter, Sort, Copy |
| Status/feedback | Filled | 20–24dp | Error, Warning, CheckCircle, CloudOff |
| File row leading icon | Outlined | 24dp | FolderOpen, Description, Image |
| Empty state | Outlined | 40–48dp | FolderOpen, SearchOff |
| Hero/illustration | Existing custom asset | 120dp+ | NAS server logo |

### 4.2 Icon size tokens

Nên thêm vào `Color.kt` hoặc file design token riêng `IconTokens.kt`:

```kotlin
internal object AppIconSize {
    val Small = 18.dp       // inline metadata; không dùng cho icon-only button
    val Medium = 24.dp      // default row/action icon
    val Large = 32.dp       // card/status icon
    val Empty = 48.dp       // empty/error state
    val Hero = 120.dp       // logo/hero only
}
```

Quy tắc: visual icon có thể 18–24dp nhưng interactive container phải đạt tối thiểu 48dp.

### 4.3 Semantic tint tokens

Không dùng raw `Color(0x...)` trong screen/dialog:

```kotlin
internal object FileTypeColors {
    val Folder = AccentBlue
    val Document = AccentOrange
    val Image = AccentPurple
    val Video = AccentPink
    val Archive = TextTertiary
    val Code = AccentCyan
}

internal object IconTint {
    val Primary = TextPrimary
    val Secondary = TextSecondary
    val Muted = TextTertiary
    val Info = AccentCyan
    val Success = AccentGreen
    val Warning = AccentOrange
    val Error = AccentRed
}
```

Màu phải đi kèm icon/text semantics; không dùng màu đơn độc để truyền trạng thái.

---

## 5. Migration plan khả thi

### Phase A — Foundation, low risk

1. Thêm `AppIconSize`, `FileTypeColors`, `IconTint`.
2. Migrate các icon sizes phổ biến trong `MainMenuScreen`, `BrowserScreen`, `BrowserComponents`.
3. Chuẩn hóa `Icons.Default` cho các semantic rõ ràng thành explicit imports:
   - `Icons.AutoMirrored.Filled.ArrowBack`
   - `Icons.Filled.*` cho primary/status
   - `Icons.Outlined.*` cho secondary/file-row
4. Không đổi icon geometry trong cùng một screen nếu chưa có screenshot comparison.

### Phase B — Accessibility, high impact

1. Giữ `contentDescription = null` cho decorative icons thật sự.
2. Bắt buộc string resource cho icon-only interactive controls.
3. Chuyển hardcoded Vietnamese descriptions sang `strings.xml`.
4. Đảm bảo `IconButton`/clickable parent đạt min 48dp.
5. Thêm semantics cho selected/expanded/disabled state.

### Phase C — Visual consistency

1. Migrate raw icon tint sang `IconTint`/`FileTypeColors`.
2. Chuẩn hóa file row icons: Folder, Image, Video, Document, Archive, Code.
3. Dùng Filled active + Outlined inactive cho toggle/tab nếu ngữ nghĩa phù hợp.
4. Remove any remaining decorative elevation/shadow/gradient.

### Phase D — Không khuyến nghị lúc này

- Không thêm Phosphor dependency chỉ để thay 124 icon hiện có.
- Không migrate 543 references trong một commit.
- Không tạo custom SVG cho các icon đã có Material equivalent.
- Không đổi toàn bộ Filled → Outlined tự động; cần phân biệt action/status/file context.

---

## 6. Acceptance criteria

- [ ] Không còn raw icon tint cho semantic states trong files đã migrate.
- [ ] Icon-only interactive controls có localized content description.
- [ ] Decorative icons duy trì `null` có chủ đích.
- [ ] Interactive container ≥48dp.
- [ ] Primary/status dùng Filled; secondary/file-row dùng Outlined khi có equivalent.
- [ ] Icon sizes trong scale `18/24/32/48/120dp`, ngoại lệ được ghi chú.
- [ ] Không thêm icon library dependency mới.
- [ ] Dark Slate Flat theme giữ nguyên: không gradient/shadow cho card/tile/action.
- [ ] Build và `git diff --check HEAD` pass.

## Quyết định cuối cùng

**Bộ icon:** Material Icons hiện tại, chuẩn hóa explicit Filled/Outlined theo hierarchy.

**Theme:** Dark Slate Flat trên Material3, semantic tokens, không pure-black bắt buộc, không gradient/shadow trang trí.

**Lý do:** Đây là phương án ít rủi ro nhất, native Android nhất, giữ ổn định UI hiện có và giải quyết đúng vấn đề thực tế: thiếu hierarchy, raw tint, icon sizing và accessibility — thay vì thay library một cách tốn kém.
