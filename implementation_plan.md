# Implementation Plan: Phase 7 - UI Migration (The Final Phase)

Quá trình "Tháo dỡ dàn giáo" Facade và di chuyển 27 files UI sang sử dụng trực tiếp 7 Domain VMs là bước cuối cùng để hoàn thiện **Strangler Fig Pattern**. Đây là một quá trình Refactor theo chiều ngang (chạm tới hầu hết mọi file giao diện) nên cần chiến thuật cuốn chiếu an toàn.

## Tóm tắt Mục tiêu
- Xóa bỏ kiểu truyền tham số `viewModel: WebDavViewModel` đang phủ khắp 27 file UI.
- Thay thế bằng việc inject/truyền chính xác Domain VM cần thiết (VD: `LoginScreen` chỉ cần `AuthSessionViewModel`).
- Cắt bỏ hoàn toàn 7 block Facade (`val deviceManagement`, `val smartTools`,...) ra khỏi `WebDavViewModel`.

## ⚠️ QUAN TRỌNG: Phase 7a — Move State TRƯỚC khi Migrate UI

**Vấn đề**: Facade hiện vẫn giữ state thực sự (`currentUrl`, `fileList`, `urlStack`, `systemStatus`, etc.). Domain VMs chỉ wired API calls + update VM state riêng. UI đọc `viewModel.currentUrl` từ facade, không từ domain VM.

**Nếu chỉ đổi UI call sites mà không move state**: UI sẽ break vì domain VM state ≠ facade state.

**Phase 7a (3 commits, ~1.5 ngày): Move state to domain VMs**

| Sub | Commit | Nội dung |
|---|---|---|
| 7a.1 | Move FileBrowser state | `currentUrl`, `urlStack`, `fileList`, `isLoading`, `pendingDeletes`, `loadGeneration` → FileBrowserVM. Facade giữ mirror state sync 2 chiều. |
| 7a.2 | Move SystemMonitor state | `systemStatus`, `temperatureHistory`, `metricsHistory`, `metricsHours`, `metricsChartTab`, `dailyReport`, `networkPingMs`, `apiLatencyMs`, `apiFailureCount`, `diskHealthCurrent`, `diskHealthHistory` → SystemMonitorVM. |
| 7a.3 | Move remaining state | AutoBackupVM, LivestreamVM, DeviceManagementVM, SmartToolsVM — move batch operation state, image counter, text preview. |

Sau Phase 7a: facade là pure forward, mọi state đã ở domain VMs.

---

## Mức độ Ưu tiên và Rủi ro

> [!WARNING]
> Việc sửa 27 file cùng lúc sẽ gây ra một "Vụ nổ lớn" (Big Bang refactor). Nếu có lỗi xảy ra, việc dò tìm nguyên nhân sẽ cực kỳ tốn thời gian.
> Do đó, bắt buộc phải chia nhỏ quá trình Migration thành nhiều nhóm (Commit riêng lẻ).

## Chiến thuật Migration (Chia để trị)

### Nhóm 1: Các màn hình Đơn nhiệm (Dễ, Rủi ro thấp)
Các màn hình này chỉ phụ thuộc vào 1 hoặc 2 Domain ViewModel cụ thể.
- **`LoginScreen.kt`** & **`GuestPassScreen.kt`**: Chuyển sang dùng `AuthSessionViewModel`.
- **`SmartOrganizerScreen.kt`** & **`DuplicateDialogs.kt`**: Chuyển sang dùng `SmartToolsViewModel`.
- **`VideoPlayerScreen.kt`** & **`MediaScreens.kt`**: Phụ thuộc `FileBrowserViewModel` (cho danh sách/URL) và `AuthSessionViewModel`.
- **`SocialExtractorScreen.kt`** & **`LivestreamWatchDialogs.kt`**: Chuyển sang dùng `LivestreamViewModel`.

### Nhóm 2: Các màn hình Quản trị (Trung bình)
Các màn hình này tương tác với phần cứng hoặc hệ thống NAS.
- **`DashboardCards.kt`** & **`DashboardWidgets.kt`**: Chuyển sang dùng `SystemMonitorViewModel` và `DeviceManagementViewModel`.
- **`DiskProfileScreen.kt`** & **`SystemStatusCards.kt`**: Chuyển sang dùng `SystemMonitorViewModel`.
- **`ToolboxDialogs.kt`** & **`MiscDialogs.kt`**: Chia nhỏ theo `SmartToolsViewModel` và `DeviceManagementViewModel`.

### Nhóm 3: Các màn hình Xương sống (Khó, Rủi ro cao)
Là màn hình điều hướng chính, nơi tụ hội của nhiều tính năng.
- **`MainMenuScreen.kt`** & **`MainMenuSections.kt`**: Do đây là menu chính, nó sẽ phải nhận vào hầu hết các Domain VMs để truyền xuống cho các Section bên dưới. **Dùng CompositionLocal** để tránh truyền tham số qua nhiều lớp Composable.
- **`BrowserScreen.kt`** & **`BrowserComponents.kt`**: Trung tâm điều khiển. Chuyển sang dùng `FileBrowserViewModel` làm chủ đạo, kết hợp `AutoBackupViewModel` (nếu có thanh trạng thái upload).

---

## Mức độ Ưu tiên và Rủi ro

> [!WARNING]
> Việc sửa 27 file cùng lúc sẽ gây ra một "Vụ nổ lớn" (Big Bang refactor). Nếu có lỗi xảy ra, việc dò tìm nguyên nhân sẽ cực kỳ tốn thời gian.
> Do đó, bắt buộc phải chia nhỏ quá trình Migration thành nhiều nhóm (Commit riêng lẻ).

## Chiến thuật Migration (Chia để trị)

### Nhóm 1: Các màn hình Đơn nhiệm (Dễ, Rủi ro thấp)
Các màn hình này chỉ phụ thuộc vào 1 hoặc 2 Domain ViewModel cụ thể.
- **`LoginScreen.kt`** & **`GuestPassScreen.kt`**: Chuyển sang dùng `AuthSessionViewModel`.
- **`SmartOrganizerScreen.kt`** & **`DuplicateDialogs.kt`**: Chuyển sang dùng `SmartToolsViewModel`.
- **`VideoPlayerScreen.kt`** & **`MediaScreens.kt`**: Phụ thuộc `FileBrowserViewModel` (cho danh sách/URL) và `AuthSessionViewModel`.
- **`SocialExtractorScreen.kt`** & **`LivestreamWatchDialogs.kt`**: Chuyển sang dùng `LivestreamViewModel`.

### Nhóm 2: Các màn hình Quản trị (Trung bình)
Các màn hình này tương tác với phần cứng hoặc hệ thống NAS.
- **`DashboardCards.kt`** & **`DashboardWidgets.kt`**: Chuyển sang dùng `SystemMonitorViewModel` và `DeviceManagementViewModel`.
- **`DiskProfileScreen.kt`** & **`SystemStatusCards.kt`**: Chuyển sang dùng `SystemMonitorViewModel`.
- **`ToolboxDialogs.kt`** & **`MiscDialogs.kt`**: Chia nhỏ theo `SmartToolsViewModel` và `DeviceManagementViewModel`.

### Nhóm 3: Các màn hình Xương sống (Khó, Rủi ro cao)
Là màn hình điều hướng chính, nơi tụ hội của nhiều tính năng.
- **`MainMenuScreen.kt`** & **`MainMenuSections.kt`**: Do đây là menu chính, nó sẽ phải nhận vào hầu hết các Domain VMs để truyền xuống cho các Section bên dưới.
- **`BrowserScreen.kt`** & **`BrowserComponents.kt`**: Trung tâm điều khiển. Chuyển sang dùng `FileBrowserViewModel` làm chủ đạo, kết hợp `AutoBackupViewModel` (nếu có thanh trạng thái upload).

---

## Trình tự Triển khai (Step-by-Step)

### Bước 1: Khởi tạo ViewModels tại `MainActivity.kt`
- Tại `MainActivity.kt`, khai báo khởi tạo cả 7 Domain VMs thông qua `viewModels()` hoặc truyền `repository`.
- Truyền các VMs này vào hàm gốc của Compose (Navigation Graph / NavHost).
- **Manual injection qua ViewModelFactory**: Giữ cách hiện tại — không thêm Hilt/Koin. Tạo `DomainViewModelFactory` tạo cả 7 VMs cùng lúc, share với `WebDavViewModel` (vẫn cần trong giai đoạn transition).

### Bước 2: Migrate dần từng Nhóm UI
1. Bắt đầu từ **Nhóm 1**: Mở `LoginScreen.kt`, đổi signature từ `(viewModel: WebDavViewModel)` thành `(authVM: AuthSessionViewModel)`. Fix lỗi đỏ tại file đó. Mở app chạy test thử màn hình Login.
2. Commit.
3. Tiếp tục cuốn chiếu cho Nhóm 2, rồi cuối cùng là Nhóm 3. Luôn tuân thủ quy tắc: **Sửa 1 màn hình -> Chạy app thử -> Commit**.

### Bước 3: Dọn dẹp tàn dư (Cleanup)
- Sau khi toàn bộ 27 files không còn gọi `viewModel.` nào nữa.
- Quay trở lại `WebDavViewModel.kt`, xóa bỏ các properties Facade (`val deviceManagement`, `val smartTools`...).
- Xóa bỏ luôn `WebDavViewModel` nếu nó đã hoàn toàn trống rỗng! (Vinh quang lớn nhất của Refactor là xóa được God Class).

---

## Open Questions (ĐÃ TRẢ LỜI)

> [!IMPORTANT]
> 1. **DI strategy**: Manual injection qua `ViewModelFactory` (giữ cách hiện tại, không thêm Hilt/Koin). Codebase đã dùng pattern này, không cần dependency mới.
> 2. **CompositionLocal scope**: Có, dùng `LocalDomainViewModels` cho MainMenuScreen vì pass nhiều VMs xuống ~10 sub-screens. Tránh verbose tham số chain.

## Verification Plan

### Manual Verification
- Test chạy app cơ bản cho mỗi nhóm màn hình sau khi hoàn thành.
- Đặc biệt chú ý: Chế độ PiP của màn hình xem Video và tính năng cuộn (Scroll state) của FileBrowser có bị reset khi thay đổi ViewModel không.

### Static Verification
- Sau khi xong Bước 3, có thể chạy lệnh `grep -r "WebDavViewModel" app/src/main/java/com/nas/naswebdav/ui/` trên Terminal để bảo đảm không còn bất kỳ dòng code nào vương vấn God Class cũ.
