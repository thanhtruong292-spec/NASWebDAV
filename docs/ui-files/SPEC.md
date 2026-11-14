# Files interface revision

## Objective
Replace the sparse icon boxes with a coherent native Android file browser. The user wants a modern, compact layout verified with actual populated NAS folders, rather than repeated cosmetic patches.

## Visual references
- Material Files official preview: https://github.com/zhanghai/MaterialFiles
- Files by Google browse UI: https://files.google.com/
- Material 3: https://m3.material.io/

Use their content hierarchy and readable folder rows as references, with NASWebDAV's existing navy/blue tokens. No external UI code or assets are copied.

## Scope and design
- A compact app bar, small connection indicator, clean breadcrumb and one controls row. Remove the oversized outlined breadcrumb capsule.
- In grid mode, folders use two compact horizontal tiles on phones; icon and a readable two-line name share one surface. File tiles retain preview, name and size. Unselected entries have no outline; selection has an explicit indicator.
- List mode uses readable 56+ dp rows. Details mode retains date/size columns and has at least 48 dp touch targets.
- Filters use restrained tonal fills, with 48 dp touch areas rather than large outlined pills.
- Keep navy surfaces, primary blue, semantic connection colors and shared typography/shape tokens. Do not add fonts, dependencies or illustrations.
- Use Material 3's native bottom navigation across the five main screens, removing the separate outlined floating capsule. Root Files needs no redundant NAS breadcrumb; show breadcrumbs in child folders.
- Preserve sorting, category filtering, root/child navigation, selection, context actions, thumbnails, preview, trash restoration and retry behavior.
- Do not reset a previously opened folder simply because the Files tab is selected again.

## Implementation plan
1. Extract entry visuals and density tokens from BrowserScreen; keep file operations in the existing handlers.
2. Simplify app bar, breadcrumb, controls and filters; use a grouped folder/file grid.
3. Build once, install using ADB USB, verify against real root folders, a child folder, list/grid, filter, selection and file names.
4. Review the changed source against this spec and documented standards; record evidence below.

## Commands and boundaries
- Build: `.\\gradlew.bat :app:assembleDebug --console=plain`
- Install: `.android-sdk\\platform-tools\\adb.exe -s R5CR61F6L3V install -r app\\build\\outputs\\apk\\debug\\app-debug.apk`
- Check: `git diff --check -- app/src/main/java/com/nas/naswebdav/ui/screens/BrowserScreen.kt app/src/main/java/com/nas/naswebdav/ui/screens/BrowserFileVisuals.kt app/src/main/java/com/nas/naswebdav/ui/navigation/NasNavGraph.kt`
- Preserve existing unrelated local work and account-scoped data. Do not mutate NAS content during visual verification.
- Source: `app/src/main/java/com/nas/naswebdav/ui/screens/`. Runtime screenshots go in the task's visualization directory.
- Validate layout on the physical phone; unit tests of static dp constants are not visual evidence.

## Acceptance
- [ ] Real root data visible; folder titles attached to icons; no large bordered icon boxes.
- [ ] All files, including non-media files, display names and size.
- [ ] Breadcrumb and controls remain readable without crowding each other.
- [ ] Grid/list/details, category filters, navigation and selection work.
- [ ] Build and installation pass; screenshots inspected.
- [ ] Limitations reported precisely; no claim that the full application UI is finished.
