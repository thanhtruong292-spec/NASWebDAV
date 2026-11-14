# UI Redesign Plan — NASWebDAV

## Goal
Replace the mixed legacy AMOLED/Material styling with one modern dark operations-console system across all Compose screens, dialogs, sheets, cards, navigation, loading, empty and error states.

## Design decisions
- Deep navy canvas with elevated slate surfaces; no pure-black legacy panels.
- One semantic palette: cyan/blue primary, green healthy, amber warning, red error, purple media.
- One shape scale: 18dp cards, 14dp controls, 24dp dialogs, pill badges.
- One typography scale from `AppTypography`; section labels use sentence case where possible.
- Reusable surface, section-header, metric-card, input, button, loading and error components.
- Preserve all existing actions, data contracts, navigation routes and accessibility labels.

## Ordered phases
1. Foundation: tokens, Material 3 color/typography/shapes, shared components.
2. Shell: top status header, bottom navigation, screen backgrounds and safe insets.
3. Primary flows: login, dashboard, file browser, media and system status.
4. Secondary flows: Apps/More, organizer, performance and guest pass.
5. Overlays: dialogs, bottom sheets, snackbars, loading/error/empty states.
6. Verification: compile, unit tests, APK install, screenshots of login/dashboard/browser/system, logcat crash scan.

## Acceptance criteria
- Every Compose screen uses the same canvas/surface/shape tokens.
- No screen uses the old pure-black card/legacy 8–12dp card style for primary content.
- Primary actions, status colors, loading and errors have consistent semantic treatment.
- Touch targets remain at least 48dp and labels remain accessible.
- `assembleDebug` and `testDebugUnitTest` pass.
- APK runs on the connected USB device without app-specific fatal logs.
