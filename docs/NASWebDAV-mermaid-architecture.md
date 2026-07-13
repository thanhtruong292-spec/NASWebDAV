# NASWebDAV Mermaid Architecture Map

> Auto-generated architecture notes for later reuse.
> 
> Scope: `app/src/main/java/com/nas/naswebdav/**` and related UI / DB / worker / helper layers.

---

## 1) High-level project architecture

```mermaid
flowchart TD
    UI[UI Layer\nMainActivity / BrowserScreen / MainMenuScreen / Dialogs]
    VM[WebDavViewModel\nBusiness orchestration + UI state]
    WM[WebDavManager\nWebDAV transport + path helpers]
    DB[(Room Database\nfiles_cache / trash_meta / sync_action / logs)]
    BW[BatchOperationWorker\nBulk COPY / MOVE / DELETE / RESTORE]
    OS[OfflineSyncWorker\nReplay offline actions]
    DS[DuplicateScanWorker\nDuplicate scan + move trash]
    DP[NasDocumentProvider\nAndroid document bridge]
    APP[NasApplication\nGlobal clients / scopes / init]
    PREF[SecurePrefsHelper\nCredentials / URL storage]
    NET[SmartNetworkManager\nLAN / Tailscale active URL]
    API[Local API / Fast API\n/api/ping / thumb / system / jobs]
    UTIL[utils\nFormatUtils / HashUtils / fingerprints]

    APP --> UI
    UI --> VM
    VM --> WM
    VM --> DB
    VM --> PREF
    VM --> NET
    VM --> API
    VM --> UTIL

    BW --> WM
    BW --> DB
    BW --> PREF
    BW --> NET
    BW --> API

    OS --> WM
    OS --> DB
    OS --> PREF
    OS --> NET

    DS --> WM
    DS --> DB
    DS --> PREF
    DS --> API

    DP --> WM
    DP --> DB
    DP --> PREF

    WM --> APP
```

---

## 2) Core file map

- `NasApplication.kt` - app-wide init, shared OkHttp clients, application scope, cleanup hooks.
- `MainActivity.kt` - entry Activity, nav host, login/logout, PiP, intent routing.
- `WebDavViewModel.kt` - orchestration, UI state, browse/delete/restore/batch/offline/metrics flows.
- `WebDavManager.kt` - WebDAV transport, auth/session, path helpers, upload/download/list/hash.
- `BatchOperationWorker.kt` - foreground worker for bulk copy/move/delete/restore.
- `OfflineSyncWorker.kt` - replay queued actions when the network returns.
- `DuplicateScanWorker.kt` - scan duplicate candidates, hash them, move them to trash.
- `NasDocumentProvider.kt` - Android document bridge for file-provider style access.
- `Database.kt` - Room entities/DAO for cache, trash metadata, offline queue, and logs.
- `SmartNetworkManager.kt` - chooses the active LAN/Tailscale base URL.
- `SecurePrefsHelper.kt` - credential and URL storage.
- `ui/screens/*` - Compose screens for browser, menus, media, monitoring, and admin flows.
- `ui/dialogs/*` - confirmation dialogs, progress dialogs, and admin prompts.
- `utils/*` - formatting, hashing, and helper utilities.

---

## 3) Main login / connect / browse flow

```mermaid
flowchart TD
    A[MainActivity.kt\nconnect/login action]
    B[WebDavViewModel.connect(urlList, user, pass)]
    C[SecurePrefsHelper.getUrlList/getUser/getPass]
    D[SecurePrefsHelper.saveCredentials]
    E[SmartNetworkManager.getActiveBaseUrl(context)]
    F[isTailscaleUrl(url)]
    G[adaptiveTimeoutMs(safeUrl)]
    H[HTTP HEAD /api/ping]
    I[recordLatency(url, ms)]
    J[buildLoginFailureMessage(urlList, errorDetails)]
    K[WebDavManager.connect(successUrl, user, pass)]
    L[repository.addSystemLog]
    M[refresh()]
    N[loadCurrentUrl()]
    O[BrowserScreen.kt / UI recomposition]

    A --> B
    B --> C
    B --> D
    B --> E
    B --> F
    B --> G
    G --> H
    H -->|success| I
    H -->|fail| J
    B -->|first valid URL| K
    K --> L
    K --> M
    M --> N
    N --> O
```

---

## 4) Browse / delete / restore / rename flow

```mermaid
flowchart TD
    A[BrowserScreen.kt\ncontext menu / gesture actions]
    B[MainMenuScreen.kt\ntrash / latest photos / recent videos]
    C[WebDavViewModel.openFolder(file)]
    D[WebDavViewModel.openSpecificUrl(url, title)]
    E[WebDavViewModel.refresh()]
    F[WebDavViewModel.deleteFile(context, file)]
    G[WebDavViewModel.restoreFile(context, file)]
    H[WebDavViewModel.renameFile(context, file, newName)]
    I[buildWebDavTrashParentUrl(baseUrl, sourcePath)]
    J[buildWebDavTrashTargetUrl(baseUrl, sourcePath, fileName, isDirectory)]
    K[buildWebDavRestoreTargetUrl(baseUrl, sourcePath, fileName, isDirectory)]
    L[webDavManager.ensureFolderHierarchy(trashFolderUrl)]
    M[webDavManager.renameFile(oldUrl, newUrl)]
    N[webDavManager.deleteFile(url, isDirectory)]
    O[trashMetaDao.findByTrashPath(path)]
    P[trashMetaDao.insert(TrashMeta)]
    Q[trashMetaDao.deleteByTrashPath(path)]
    R[repository.addSystemLog]
    S[friendlyError(e)]
    T[e.isTransientNetworkFailure()]
    U[enqueueOfflineAction(context, actionType, sourcePath, destPath)]
    V[loadCurrentUrl()]
    W[showLatestPhotos()]
    X[showRecentVideos()]

    A --> C
    A --> E
    A --> F
    A --> G
    A --> H
    B --> D
    B --> W
    B --> X

    C --> V
    D --> V
    E --> V

    F --> I
    F --> J
    F -->|if in trash| N
    F -->|if not in trash| L
    F -->|if not in trash| M
    F -->|if not in trash| P
    F --> R
    F --> S
    F --> T
    F --> U

    G --> O
    G --> K
    G --> M
    G --> Q
    G --> R
    G --> S
    G --> T
    G --> U

    H --> M
    H --> R
    H --> S
    H --> T
    H --> U
```

---

## 5) Batch operations flow

```mermaid
flowchart TD
    A[BrowserScreen.kt\nselection + batch action]
    B[WebDavViewModel.deleteMultipleFiles(context, files)]
    C[WebDavViewModel.batchCopyFiles(context, files, destUrl)]
    D[WebDavViewModel.batchMoveFiles(context, files, destUrl)]
    E[WebDavViewModel.restoreMultipleFiles(context, files)]
    F[enqueueBatchOperation(context, operation, files, destUrl)]
    G[create payloadFile JSON]
    H[WorkManager.enqueueUniqueWork(BatchOperation_* )]
    I[WorkInfo flow collect -> progress UI]
    J[BatchOperationWorker.doWork()]
    K[loadBatchFiles()]
    L[resolveBatchWebDavPath(rawPath, activeBaseUrl)]
    M[WebDavManager.connect(savedUrl, user, pass)]
    N[trashMetaDao]
    O[setForeground(ForegroundInfo)]
    P[setProgress(workDataOf(...))]
    Q[operation = COPY]
    R[operation = MOVE]
    S[operation = DELETE]
    T[operation = RESTORE]
    U[buildWebDavTrashParentUrl(baseUrl, sourcePath)]
    V[buildWebDavTrashTargetUrl(baseUrl, sourcePath, fileName, isDirectory)]
    W[buildWebDavRestoreTargetUrl(baseUrl, sourcePath, fileName, isDirectory)]
    X[webDavManager.ensureFolderHierarchy(folderUrl)]
    Y[webDavManager.copyFile(oldUrl, newUrl)]
    Z[webDavManager.renameFile(oldUrl, newUrl)]
    AA[webDavManager.deleteFile(url, isDirectory)]
    AB[trashMetaDao.insert/delete/findByTrashPath]
    AC[db.logDao().insertLog]
    AD[setProgress(successCount/failCount)]
    AE[done notification]

    A --> B
    A --> C
    A --> D
    A --> E

    B --> F
    C --> F
    D --> F
    E --> F

    F --> G
    F --> H
    F --> I
    H --> J

    J --> K
    J --> L
    J --> M
    J --> N
    J --> O
    J --> P

    J --> Q
    J --> R
    J --> S
    J --> T

    Q --> Y
    Q --> AB

    R --> Z
    R --> AB

    S --> U
    S --> V
    S --> X
    S --> Z
    S --> AB
    S -->|if already in trash| AA

    T --> AB
    T --> W
    T --> Z
    T --> AB

    J --> AD
    J --> AC
    J --> AE
```

---

## 6) Offline sync / duplicate scan / document provider flow

```mermaid
flowchart TD
    A[WebDavViewModel.enqueueOfflineAction(context, actionType, sourcePath, destPath)]
    B[toOfflineQueuePath(baseUrl)]
    C[syncActionDao.insert(SyncAction)]
    D[WorkManager.enqueueUniqueWork(OfflineSyncWorker)]
    E[OfflineSyncWorker.doWork()]
    F[db.syncActionDao().getAllPendingActions()]
    G[webDavManager.connect(url, user, pass)]
    H[resolveQueuedWebDavPath(rawPath, activeBaseUrl)]
    I[webDavManager.deleteFile(url, isDirectory)]
    J[webDavManager.createFolder(url)]
    K[webDavManager.ensureFolderHierarchy(folderUrl)]
    L[webDavManager.renameFile(oldUrl, newUrl)]
    M[webDavManager.uploadFile(destUrl, file, mime)]
    N[trashMetaDao.findByTrashPath / insert / deleteByTrashPath]
    O[db.syncActionDao().deleteById(action.id)]

    P[DuplicateScanWorker.doWork()]
    Q[db.fileDao().getDuplicateSizes()]
    R[db.fileDao().getFilesBySize(size)]
    S[hash_batch API / fallback hash]
    T[moveFileToTrash(manager, sourceUrl, user, pass)]
    U[buildWebDavTrashParentUrl(rootUrl, sourceUrl)]
    V[buildWebDavTrashTargetUrl(rootUrl, sourceUrl, fileName, false)]
    W[manager.ensureFolderHierarchy(trashFolderUrl)]
    X[HTTP MOVE sourceUrl -> destUrl]
    Y[database.trashMetaDao().insert(TrashMeta)]
    Z[SystemLogger.log(...)]

    AA[NasDocumentProvider.deleteDocument(documentId)]
    AB[webDavManager.deleteFile(url, url.endsWith('/'))]
    AC[webDavManager.listFiles(url)]
    AD[webDavManager.downloadFile(url, destFile)]
    AE[webDavManager.createEmptyFile(url)]

    A --> B --> C --> D --> E
    E --> F
    E --> G
    E --> H
    E -->|DELETE| I
    E -->|CREATE_FOLDER| J
    E -->|RENAME/MOVE| K
    E -->|RENAME/MOVE| L
    E -->|UPLOAD| M
    E --> N
    E --> O

    P --> Q
    P --> R
    P --> S
    P --> T
    T --> U
    T --> V
    T --> W
    T --> X
    T --> Y
    P --> Z

    AA --> AB
    AD --> AD
    AE --> AE
```

---

## 7) WebDavManager internal call graph (project-wide core)

```mermaid
flowchart TD
    subgraph P["Path / URL helpers"]
        DS["decodeWebDavSegment(segment)"]
        ES["encodeWebDavSegment(segment)"]
        XP["extractWebDavPath(rawPath)"]
        NR["normalizeWebDavRelativePath(baseUrl, sourcePath)"]
        TR["buildWebDavTrashRootUrl(baseUrl)"]
        TP["buildWebDavTrashParentUrl(baseUrl, sourcePath)"]
        TT["buildWebDavTrashTargetUrl(baseUrl, sourcePath, fileName, isDirectory)"]
        RT["buildWebDavRestoreTargetUrl(baseUrl, sourcePath, fileName, isDirectory)"]
        EH["ensureFolderHierarchy(folderUrl)"]
    end

    subgraph A["Auth / client core"]
        STATE["AuthState"]
        CAS["currentAuthState()"]
        OC["optimizedClient"]
        SC["sardineClient"]
        CN["connect(url, user, pass)"]
        CC["cancelActiveCalls()"]
        TH["tagCurrentAuth / withAuth / withCurrentAuth"]
    end

    subgraph R["Read / ping / list / hash / text"]
        CHECK["checkPingServer()"]
        HEAD["headFileHeaders(url)"]
        LIST["listFiles(url)"]
        HASH["getPartialHashStream(url)"]
        READ["readFileText(url, maxLines)"]
    end

    subgraph W["Write / folder / file ops"]
        MKCOL["createFolder(url)"]
        DEL["deleteFile(url, isDirectory)"]
        REN["renameFile(oldUrl, newUrl)"]
        CPY["copyFile(oldUrl, newUrl)"]
        UP1["uploadStreamWithProgress(...)"]
        UP2["uploadCompressedStream(...)"]
        UP3["resumeUploadStreamWithProgress(...)"]
        UFILE["uploadFile(fileUrl, file, contentType)"]
        DOWN["downloadFile(url, destFile)"]
        EMPTY["createEmptyFile(url)"]
        INIT["initConnection()"]
    end

    STATE --> CAS
    CAS --> OC
    CAS --> SC
    CN --> STATE
    CC --> OC
    CC --> SC
    TH --> CAS

    ES --> DS
    NR --> XP
    NR --> ES
    TR --> DS
    TP --> TR
    TP --> NR
    TT --> TR
    TT --> NR
    TT --> ES
    RT --> NR
    RT --> ES
    EH --> MKCOL

    CHECK --> OC
    HEAD --> OC
    LIST --> SC
    HASH --> OC
    READ --> OC

    MKCOL --> OC
    DEL --> OC
    REN --> OC
    CPY --> OC
    UP1 --> OC
    UP2 --> OC
    UP3 --> OC
    UFILE --> UP1
    DOWN --> OC
    EMPTY --> OC
    INIT --> OC
```

---

## 8) How to reuse this file later

- Open this document first when you want to understand the app.
- Start from section **1** for project structure.
- Then follow **3?6** for the main runtime flows.
- Use section **7** when debugging path / trash / WebDAV transport issues.
