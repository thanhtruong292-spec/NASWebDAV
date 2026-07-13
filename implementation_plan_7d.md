# Kế hoạch Thực thi Phase 7d (Cleanup & Deletion)

Kế hoạch này vạch ra chiến lược cuối cùng để gỡ bỏ hoàn toàn God Class `WebDavViewModel`, dọn dẹp các lời gọi biến cũ, và phân bổ nốt các trạng thái (state) còn sót lại.

## User Review Required

> [!WARNING]
> **Quyết định Kiến trúc Tối hậu:**
> Hiện tại `WebDavViewModel` vẫn đang chứa một số Logic chưa được phân loại vào 7 Domain VMs (Ví dụ: `GuestPass`, `Torrent`, `USB Import`, `TelegramConfig`).
> Có 2 hướng đi:
> 1. **Triệt để (Strict):** Xóa trắng `WebDavViewModel.kt`. Chuyển `GuestPass` vào `AuthSessionVM`, `USB Import/Torrent` vào `DeviceManagementVM`. Các file UI sẽ đổi hoàn toàn sang `LocalXxxVM.current`.
> 2. **Trung dung (Pragmatic):** Đổi tên `WebDavViewModel` thành `SharedHelperViewModel` (rất mỏng). Nó chỉ chứa các hàm chung như `downloadAndPlay`, quản lý `GuestPass` và giữ `WebDavManager`.

*(Đề xuất: Chọn Hướng 1 để hệ thống sạch sẽ tuyệt đối. Các helper methods có thể thành top-level functions hoặc dời vào `Utils`)*.

## Thống kê số lượng `viewModel.xxx` tại UI Files

Dựa trên công cụ quét tự động, đây là "chiến trường" Find & Replace của chúng ta:

| Nhóm | UI File | Số lượng `viewModel.` | Ưu tiên |
|---|---|---|---|
| **Nặng nhất** | `MainMenuScreen.kt` | 262 | Phase 7d.2 |
| **Nặng nhất** | `Dialogs.kt` | 160 | Phase 7d.2 |
| **Nặng** | `BrowserScreen.kt` | 84 | Phase 7d.1 |
| **Nặng** | `SystemStatusCards.kt` | 62 | Phase 7d.2 |
| **Trung bình** | `DashboardCards.kt` | 50 | Phase 7d.2 |
| **Trung bình** | `MainMenuBottomSheets.kt` | 47 | Phase 7d.2 |
| **Trung bình** | `StorageDialogs.kt` | 38 | Phase 7d.3 |
| **Trung bình** | `MediaScreens.kt` | 36 | Phase 7d.3 |

*(Tổng cộng ~900 references cần thay thế).*

## Phân tích các hàm (Methods) còn sót lại trong Facade

Các hàm sau đây vẫn đang "thường trú" tại `WebDavViewModel.kt` (chưa thuộc 7 Domain VMs):

1. **Nhóm Mạng & Lỗi (Network & Errors):**
   - `adaptiveTimeoutMs`, `recordLatency`, `isTailscaleUrl`, `friendlyError`
   - *Chiến lược:* Di chuyển vào `WebDavManager.kt` hoặc `NetworkUtils.kt`.
2. **Nhóm Tính năng Lẻ (Orphan Features):**
   - **Guest Pass** (`createGuestPass`, `revokeGuestPass`, `activeGuestPass`): *Chuyển sang `AuthSessionViewModel`*.
   - **USB Import** (`fetchUsbImportStatus`, `startUsbImportNow`, `resolveUsbImportConflicts`): *Chuyển sang `DeviceManagementViewModel`*.
   - **Torrent / Backup Schedule**: *Chuyển sang `AutoBackupViewModel` (đổi tên thành `TransferViewModel` nếu cần) hoặc `SystemMonitorVM`*.
   - **Telegram/Rules Config**: *Chuyển sang `DeviceManagementViewModel`*.
3. **Nhóm Utils:**
   - `downloadAndPlay`, `openLocalFile` → Đưa vào một file `IntentUtils.kt`.

## Chiến lược Thay thế (Find & Replace) An Toàn

Để không làm hỏng app khi thay đổi 900+ biến, chúng ta thực hiện theo nguyên tắc **"Thay từng File, Build từng File"**:

### Phase 7d.1 - Core Dọn dẹp (High Leverage)
- **Mục tiêu:** Thay thế tại `BrowserScreen.kt` (84 refs) và `LoginScreen.kt` (đã xong).
- **Thao tác:** 
  - Đổi `viewModel.` thành `fileBrowserVM.`
  - Xóa bỏ tham số `viewModel: WebDavViewModel` ở Header hàm Composable.
  - Compile và test.

### Phase 7d.2 - Khối Quản trị (Dashboard & MainMenu)
- **Mục tiêu:** `MainMenuScreen`, `Dialogs`, `SystemStatusCards`, `DashboardCards`.
- **Thao tác:**
  - Vì các file này dùng nhiều VM, sử dụng Find & Replace theo regex hoặc Regex Group.
  - Ví dụ: Thay `viewModel.metricsHistory` thành `sysMonitorVM.metricsHistory`. Thay `viewModel.diskHealth` thành `sysMonitorVM.diskHealth`.
  - Làm cẩn thận từng biến một, không Replace All mù quáng.

### Phase 7d.3 - Dọn dẹp & Xóa sổ Facade
- **Mục tiêu:** Di chuyển nốt các Orphan Features (GuestPass, USB Import).
- Xóa các Delegating Getters/Setters trong `WebDavViewModel.kt`.
- Cuối cùng, xóa file `WebDavViewModel.kt`.
- Xóa khai báo `WebDavViewModel` khỏi `MainActivity.kt`.

## Verification Plan
### Automated Tests
- Kiểm tra số lượng lỗi qua lệnh `./gradlew compileDebugKotlin`. Mục tiêu là 0 error.
### Manual Verification
- Chạy APK.
- Đăng nhập, mở thư mục, mở file video, bật SMB, test thử 1 biểu đồ. Mọi thứ hoạt động = Thành công!
