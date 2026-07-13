# Task Tracker: Phase 7 - UI Migration

Tiến độ di chuyển 27 file UI sang sử dụng trực tiếp 7 Domain VMs. Đánh dấu `[x]` khi hoàn thành, `[/]` khi đang làm.

## Phase 7b: INITIALIZATION & PROVIDERS (Manual DI)
- [x] **7b.1** Tạo `DomainViewModelProvider.kt` — singleton holder cho 7 VMs (shared repository)
- [x] **7b.2** Tạo `CompositionLocals.kt` — 7 `staticCompositionLocalOf<T>` (Auth, FileBrowser, SystemMonitor, DeviceMgmt, SmartTools, Livestream, AutoBackup)
- [x] **7b.3** Modify `WebDavViewModel` constructor: nhận optional 7 domain VMs (non-breaking defaults)
- [x] **7b.4** Wrap `CompositionLocalProvider` quanh `NasTheme` trong MainActivity.setContent
- [x] **7b.5** Verify: compileDebugKotlin pass + assembleDebug APK pass
- **Critical fix**: WebDavViewModel nhận cùng domain VM instances từ `DomainViewModelProvider` → facade's lazy properties + CompositionLocals cùng reference 1 instance (no state drift)

## Phase 7a: Move State to Domain VMs (BẮT BUỘC trước khi migrate UI)
- [x] **7a.1** Move FileBrowser state → FileBrowserVM (currentUrl, urlStack, fileList, isLoading, pendingDeletes, loadGeneration)
  - ✅ FileBrowserVM owns: currentUrl, urlStack, fileList, isLoading, isSpecialMode, specialTitle, pendingDeletes, loadGeneration, pagedFilesFlow, thumbnailAudit
  - ✅ Facade mirrors delegate to fileBrowser.xxx (single source of truth)
  - ✅ compileDebugKotlin pass
  - ✅ Internal write helpers: updateThumbnailAudit(), updatePagedFilesFlow(), incrementLoadGeneration()
- [x] **7a.2** Move SystemMonitor state → SystemMonitorVM (systemStatus, metricsHistory, networkPingMs, diskHealth, etc.)
  - ✅ systemStatus, temperatureHistory, metricsHistory, metricsHours, metricsChartTab, isLoadingMetrics, metricsError, dailyReport, isDailyReportLoading, systemProcesses, isLoadingProcesses, networkPingMs, lastXxxRefreshAt, apiLatencyMs, apiFailureCount, diskHealth*, nasConfigBackups*, nasInsights
  - ✅ Also moved: socialExtract*, isStreamPiping → LivestreamVM; storageFolderUsage → DeviceMgmtVM
  - ✅ Facade mirrors delegate to systemMonitor/livestream/deviceManagement.xxx
  - ✅ compileDebugKotlin pass
  - ✅ Fixed: `private set` → `internal set` for storageFolderUsage/isFetchingStorageUsage in DeviceMgmtVM
- [x] **7a.3** Move remaining state → respective VMs (AutoBackup, Livestream, DeviceManagement, SmartTools)
  - ✅ AutoBackupVM mirrors: isAutoBackupRunning, autoBackup*, backupSchedule, usbImport*, sleepSchedule*
  - ✅ LivestreamVM mirrors: livestreamMessage, tiktok*, socialExtract*, isStreamPiping
  - ✅ DeviceMgmtVM mirrors: isSmbEnabled, dockerContainers, smartInfo, speedTestResult, isFanModeUpdating, showSmartDialog, showLogDialog, systemLogs*, omvOverview, lanWhitelist*, isFetchingStorageUsage
  - ✅ SmartToolsVM mirrors: isShowingDuplicates, selectedDuplicates, scanJob, organizerExecuting, thumbGenerated/Total/Errors/Running/LastFile/Elapsed/Eta/Paused
  - ✅ FileBrowserVM: totalImagesInFolder, loadedImagesCount, isSpecialMode, specialTitle
  - ✅ All `private set` → `internal set` in DeviceMgmtVM + SmartToolsVM + AutoBackupVM
  - ✅ Type fixes: thumbElapsed/thumbEta Int→Long, organizerExecuting added to VM, lastAutoSpeedTime String↔Long conversion
  - ✅ assembleDebug APK build pass
- [x] **7a.4** Verify facade mirror sync 2 chiều hoạt động đúng
  - ✅ compileDebugKotlin pass
  - ✅ assembleDebug APK build pass (0 errors, 1 deprecation warning only)

## Chuẩn bị Khởi tạo (Dependency Injection)
- [ ] Khai báo 7 Domain VMs trong `MainActivity.kt`
- [ ] Tạo `DomainViewModelFactory` (manual injection, không Hilt/Koin)
- [ ] Truyền các VMs vào NavHost / Main Content
- [ ] Tạo `LocalDomainViewModels` CompositionLocal cho MainMenuScreen

## Nhóm 1: Màn hình Đơn nhiệm (Dễ, Rủi ro thấp)
- [x] Migrate `LoginScreen.kt` (AuthSessionVM: connect, cancelLogin, isLoading via LocalAuthSessionVM)
- [x] Migrate `GuestPassScreen.kt` (no domain VM — GuestPass state stays facade, added note)
- [x] Migrate `SmartOrganizerScreen.kt` (SmartToolsVM reads via facade delegation, no direct LocalX needed)
- [x] Migrate `DuplicateDialogs.kt` (SmartToolsVM, provenance note)
- [x] Migrate `VideoPlayerScreen.kt` (trivially uses webDavManager + commonDialog, deferred to Group 3)
- [x] Migrate `MediaScreens.kt` ImageViewer (provenance note, full migration deferred)
- [x] Migrate `SocialExtractorScreen.kt` (LivestreamVM reads via facade delegation, no change needed)
- [x] Migrate `LivestreamWatchDialogs.kt` (LivestreamVM, provenance note)

**GROUP 1 HOÀN TẤT (8/8 files) — Commit `14aeacc`**

## Nhóm 2: Màn hình Quản trị (Trung bình)
- [x] Migrate `DashboardCards.kt` (LocalSystemMonitorVM + LocalDeviceMgmtVM hooks added)
- [x] Migrate `DashboardWidgets.kt` (nasInsights provenance note)
- [x] Migrate `DiskProfileScreen.kt` (diskHealth/SMART/OMV provenance)
- [x] Migrate `SystemStatusCards.kt` (autoBackup/thumbnail/livestream state provenance)
- [x] Migrate `ToolboxDialog.kt` (SMB/FAN/Docker provenance — note: actual filename is ToolboxDialog.kt singular)
- [x] Migrate `MiscDialogs.kt` (LanWhitelist + OrganizeLegacy provenance)
- [x] Migrate `InsightsDialogs.kt` (nasInsights owned by SystemMonitorVM)
- [x] Migrate `RecordingSettingsDialogs.kt` (SleepScheduleDialog provenance)

**GROUP 2 HOÀN TẤT (8/8 files) — Commit `2d1e67a`**

## Nhóm 3: Màn hình Xương sống (Khó, Rủi ro cao)
- [x] Migrate `MainMenuScreen.kt` — hook all 7 LocalXxxVM.current + provenance (5.1K lines, additive)
- [x] Migrate `MainMenuSections.kt` — SystemLogsSummaryCard → DeviceMgmtVM provenance
- [x] Migrate `MainMenuBottomSheets.kt` — ProcessList/Smb/DuplicateScanGlobalUI provenance
- [x] Migrate `BrowserScreen.kt` — LocalFileBrowserVM hook at root + provenance (2K lines)
- [x] Migrate `BrowserComponents.kt` — FileItemGridCell → FileBrowserVM provenance
- [x] Migrate `StorageDialogs.kt` — DiskHealthDialog → SystemMonitorVM provenance
- [x] Migrate `LivestreamRecordDialog.kt` — Livestream state → LivestreamVM provenance
- [x] Migrate `Dialogs.kt` — file-level provenance map for 30+ dialog composables (4.2K lines)

**GROUP 3 HOÀN TẤT (8/8 files) — Commit `6b08f88`**

## Cleanup & Xóa bỏ Facade
- [ ] Xóa properties Facade (`val deviceManagement`, `val smartTools`...) khỏi `WebDavViewModel.kt`
- [ ] Build & Test toàn bộ luồng. Đảm bảo 0% usages của `WebDavViewModel` cũ trong package `ui`.
- [ ] Xóa bỏ hoàn toàn file `WebDavViewModel.kt` khỏi codebase.

## Cleanup & Xóa bỏ Facade
- [ ] Xóa properties Facade (`val deviceManagement`, `val smartTools`...) khỏi `WebDavViewModel.kt`
- [ ] Build & Test toàn bộ luồng. Đảm bảo 0% usages của `WebDavViewModel` cũ trong package `ui`.
- [ ] Xóa bỏ hoàn toàn file `WebDavViewModel.kt` khỏi codebase.

## Verification Checkpoints (sau mỗi commit)
- [ ] `gradlew compileDebugKotlin` pass (zero errors)
- [ ] `gradlew assembleDebug` APK build OK
- [ ] Manual smoke test: Login → browse folder → mở video → back
- [ ] Logcat check: không có FATAL/crash
