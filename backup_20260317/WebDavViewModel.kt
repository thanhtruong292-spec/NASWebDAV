package com.nas.naswebdav

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// THÊM DATA CLASS CHO TORRENT
data class TorrentInfo(
    val name: String,
    val progress: Float,
    val speed: String,
    val hash: String = ""
)

// DATA CLASS CHO SMART VÀ SPEED TEST
data class SmartInfo(val status: String, val temperature: String, val rawLog: String)
data class SpeedTestResult(val writeSpeed: String, val readSpeed: String)

// DATA CLASS CHO DOCKER
data class DockerContainer(val id: String, val name: String, val status: String)

// DATA CLASS CHO PHÂN TÍCH Ổ ĐĨA
data class DiskPart(
    val mount: String,
    val percent: Float,
    val total: String
)

// Data class lưu trữ trạng thái hệ thống qua Local API
data class NasSystemStatus(
    val temp: String = "--°C",
    val cpu: String = "--%",
    val cpuTemp: String = "--°C",
    val ram: String = "--",
    val disk: String = "--%",
    val netRx: String = "0 B/s",
    val netTx: String = "0 B/s",
    val uptime: String = "--:--",
    val status: String = "Chờ đồng bộ",
    val ramPercent: String = "0",
    val torrents: List<TorrentInfo> = emptyList(),
    val diskParts: List<DiskPart> = emptyList() // Chứa danh sách ổ đĩa
)
class WebDavViewModel(val webDavManager: WebDavManager, private val repository: WebDavRepository) : ViewModel() {

    // CHỐNG RÒ RỈ THREAD VÀ BỘ NHỚ: Dùng chung một OkHttpClient duy nhất cho toàn bộ các truy vấn Local API
    private val localApiClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
            // BẢN VÁ LỖI API TỪ CHỐI: Tự động đính kèm Header Authorization cho TẤT CẢ các request Local API
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                val user = webDavManager.currentUser
                val pass = webDavManager.currentPass
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    val credential = okhttp3.Credentials.basic(user, pass)
                    requestBuilder.header("Authorization", credential)
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
    }
    // Biến lưu trữ trạng thái giám sát hệ thống (Local API)
    var systemStatus by mutableStateOf(NasSystemStatus())
    private var statusJob: kotlinx.coroutines.Job? = null

    init {
        listenToLocalNasApi()
    }

    private fun listenToLocalNasApi() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val baseUrl = webDavManager.currentBaseUrl
                    if (baseUrl.isNotEmpty()) {
                        // Tự động trích xuất IP NAS từ URL của WebDAV (Ví dụ: 192.168.1.50)
                        val host = java.net.URL(baseUrl).host

                        // Gọi vào Port 5000 mà script Python đang mở
                        val request = okhttp3.Request.Builder()
                            .url("http://$host:5000/api/status")
                            .build()

                        localApiClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful && response.body != null) {
                                // Sử dụng thư viện JSON mặc định của Android để bóc tách dữ liệu
                                val jsonObject = org.json.JSONObject(response.body!!.string())
                                val temp = jsonObject.optString("temperature", "--°C")
                                val cpu = jsonObject.optString("cpu", "--%")
                                val cpuTemp = jsonObject.optString("cpu_temp", "--°C")
                                val ram = jsonObject.optString("ram", "--")
                                val disk = jsonObject.optString("disk", "--%")
                                val netRx = jsonObject.optString("net_rx", "0 B/s")
                                val netTx = jsonObject.optString("net_tx", "0 B/s")
                                val uptime = jsonObject.optString("uptime", "--")
                                val status = jsonObject.optString("status", "Online")
                                val ramPercent = jsonObject.optString("ram_percent", "0")

                                // Bóc tách danh sách Torrent đang tải theo thời gian thực
                                val torrentsArray = jsonObject.optJSONArray("torrents")
                                val torrentList = mutableListOf<TorrentInfo>()
                                if (torrentsArray != null) {
                                    for (i in 0 until torrentsArray.length()) {
                                        val tObj = torrentsArray.getJSONObject(i)
                                        torrentList.add(TorrentInfo(
                                            name = tObj.optString("name", "Đang tải..."),
                                            progress = tObj.optDouble("progress", 0.0).toFloat(),
                                            speed = tObj.optString("speed", "0 B/s"),
                                            hash = tObj.optString("hash", "")
                                        ))
                                    }
                                }

                                // Bóc tách danh sách phân vùng ổ đĩa chi tiết
                                val diskArray = jsonObject.optJSONArray("disk_parts")
                                val diskPartList = mutableListOf<DiskPart>()
                                if (diskArray != null) {
                                    for (i in 0 until diskArray.length()) {
                                        val dObj = diskArray.getJSONObject(i)
                                        diskPartList.add(DiskPart(
                                            mount = dObj.optString("mount", "/"),
                                            percent = dObj.optDouble("percent", 0.0).toFloat(),
                                            total = dObj.optString("total", "0GB")
                                        ))
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    systemStatus = NasSystemStatus(temp, cpu, cpuTemp, ram, disk, netRx, netTx, uptime, status, ramPercent, torrentList, diskPartList)
                                }
                            } else {
                                withContext(Dispatchers.Main) { systemStatus = systemStatus.copy(status = "API từ chối") }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { systemStatus = systemStatus.copy(status = "Mất kết nối API") }
                }

// Tự động làm mới dữ liệu mỗi 3 giây để tạo cảm giác Real-time
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    // LOẠI BỎ fileList GÂY OOM, THAY BẰNG PAGING DATA FLOW
    var fileList by mutableStateOf<List<NasFile>>(emptyList()) // Giữ lại dự phòng cho tính năng tìm kiếm/đặc biệt

    private val _pagedFilesFlow = MutableStateFlow<Flow<PagingData<NasFile>>>(emptyFlow())
    val pagedFilesFlow = _pagedFilesFlow.asStateFlow()

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var connectionStatus by mutableStateOf("Đang kết nối...")

    // Tiến trình tải thumbnail
    var totalImagesInFolder by mutableIntStateOf(0)
    var loadedImagesCount by mutableIntStateOf(0)
    val imageLoadProgress: Float get() = if (totalImagesInFolder > 0) loadedImagesCount.toFloat() / totalImagesInFolder else 0f
    // Biến trạng thái cho hộp thoại tải lên File
    var isUploading by mutableStateOf(false)
    var uploadFileName by mutableStateOf("")
    var uploadBytes by mutableLongStateOf(0L)
    var uploadTotalBytes by mutableLongStateOf(0L)
    // Biến trạng thái bổ sung cho tính năng Đồng bộ hàng loạt
    var isSyncing by mutableStateOf(false)
    var syncStatusText by mutableStateOf("")

    // Biến trạng thái cho tính năng Quét và Xóa file trùng lặp
    var isShowingDuplicates by mutableStateOf(false)
    var duplicateFilesList by mutableStateOf<List<NasFile>>(emptyList())
    var selectedDuplicates = androidx.compose.runtime.mutableStateListOf<NasFile>()
    var isScanningDuplicates by mutableStateOf(false)
    var isWorkerRunning by mutableStateOf(false)
    var scanDuplicatesCurrentFolderUrl by mutableStateOf("")
    var scanDuplicatesCurrentItemName by mutableStateOf("")
    var scanDuplicatesTotalScanned by mutableIntStateOf(0)
    var scanDuplicatesFound by mutableIntStateOf(0)
    var scanDuplicatesPercent by mutableFloatStateOf(0f)
    var scanDuplicatesIsFolder by mutableStateOf(false)
    private var scanJob: kotlinx.coroutines.Job? = null

    // --- QUẢN LÝ BẢO MẬT & PHÊ DUYỆT (DEVICE APPROVAL) ---
    var showApprovalDialog by mutableStateOf(false)
    var pendingIpAddress by mutableStateOf("")
    var approvalMessage by mutableStateOf("")
    var pendingCountryCode by mutableStateOf("VN")
    var weeklyReportText by mutableStateOf("Đang tải dữ liệu...")

    private var webSocket: okhttp3.WebSocket? = null

    // TRÍCH XUẤT HOST CHUẨN ĐỂ FIX LỖI CRASH PORT (8822:5000)
    fun startRealtimeAlerts(url: String) {
        if (url.isBlank()) return
        webSocket?.close(1000, "Restarting")
        try {
            val host = java.net.URL(url).host
            val wsUrl = "ws://$host:5000/ws/alerts"
            val wsRequest = okhttp3.Request.Builder().url(wsUrl).build()
            val client = okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()

            webSocket = client.newWebSocket(wsRequest, object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val json = org.json.JSONObject(text)
                    viewModelScope.launch(Dispatchers.Main) {
                        when (json.optString("type")) {
                            "SECURITY_BAN" -> {
                                commonDialogMessage = json.optString("message")
                                commonDialogIcon = Icons.Default.GppBad
                                commonDialogColor = Color.Red
                                showCommonDialog = true
                                loadSystemLogs()
                            }
                            "AUTH_REQ" -> {
                                pendingIpAddress = json.optString("ip")
                                approvalMessage = json.optString("message")
                                pendingCountryCode = json.optString("country_code", "UN")
                                showApprovalDialog = true
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) { }
    }

    fun approveDeviceIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(currentUrl).host
                val json = org.json.JSONObject().put("ip", ip).toString()
                val body = json.toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("http://$host:5000/api/auth/approve_ip").post(body).build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            showApprovalDialog = false
                            commonDialogMessage = "Đã cấp quyền cho IP: $ip"
                            showCommonDialog = true
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    fun fetchWeeklyReport() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(currentUrl).host
                val request = okhttp3.Request.Builder().url("http://$host:5000/api/system/weekly_report").build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "{}")
                        val banned = json.optInt("banned_count", 0)
                        val freed = json.optString("freed_space", "0 MB")
                        withContext(Dispatchers.Main) {
                            weeklyReportText = "Tuần qua: Chặn $banned IP tấn công. Dọn rác giải phóng $freed."
                        }
                    }
                }
            } catch (e: Exception) { weeklyReportText = "Chưa có báo cáo tuần này." }
        }
    }

    // --- QUẢN LÝ NHẬT KÝ HỆ THỐNG ---
    var showLogDialog by mutableStateOf(false)
    var systemLogsList by mutableStateOf<List<SystemLog>>(emptyList())

    fun loadSystemLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = repository.getSystemLogs()
            withContext(Dispatchers.Main) { systemLogsList = logs }
        }
    }

    fun clearSystemLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearSystemLogs()
            withContext(Dispatchers.Main) {
                systemLogsList = emptyList()
                commonDialogMessage = "Đã dọn sạch nhật ký hệ thống."
                showCommonDialog = true
            }
        }
    }
    // Trạng thái cho chế độ xem đặc biệt (Ảnh mới/Video gần đây)
    var isSpecialMode by mutableStateOf(false)
    var specialTitle by mutableStateOf("")

    // STATE CHO DIALOG THÔNG BÁO CHUNG TỪ VIEWMODEL
    var commonDialogMessage by mutableStateOf("")
    var commonDialogIcon by mutableStateOf(Icons.Default.Info)
    var commonDialogColor by mutableStateOf(Color.Gray)
    var showCommonDialog by mutableStateOf(false)

    // STATE CHO SMART DIALOG VÀ SPEED TEST
    var showSmartDialog by mutableStateOf(false)
    var smartInfo by mutableStateOf(SmartInfo("Đang tải...", "--", ""))
    var speedTestResult by mutableStateOf(SpeedTestResult("--", "--"))
    var isTestingSpeed by mutableStateOf(false)
    var lastAutoSpeedTime by mutableStateOf("")

    // STATE CHO TELEGRAM
    var showTelegramDialog by mutableStateOf(false)
    var tgToken by mutableStateOf("")
    var tgChatId by mutableStateOf("")
    var tgEnabled by mutableStateOf(false)

    // STATE CHO DOCKER MANAGER
    var showDockerDialog by mutableStateOf(false)
    var dockerContainers by mutableStateOf<List<DockerContainer>>(emptyList())
    // Quản lý Nhật ký hệ thống
    var systemLogs by mutableStateOf(listOf<SystemLog>())
    var isFetchingDocker by mutableStateOf(false)
    fun fetchDockerContainers() {
        if (isFetchingDocker) return
        isFetchingDocker = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
                val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/docker/containers").build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val array = org.json.JSONArray(body)
                        val list = mutableListOf<DockerContainer>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(DockerContainer(obj.optString("id"), obj.optString("name"), obj.optString("status")))
                        }
                        dockerContainers = list
                    }
                }
            } catch (e: Exception) {}
            finally { isFetchingDocker = false }
        }
    }

    fun controlDockerContainer(action: String, containerName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
                val json = org.json.JSONObject().apply {
                    put("action", action)
                    put("container", containerName)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/docker/control").post(body).build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // Cập nhật giao diện mượt mà: Đợi 1.5 giây cho Docker trên NAS xử lý xong rồi tải lại danh sách
                        kotlinx.coroutines.delay(1500)
                        isFetchingDocker = false // Reset cờ để ép tải lại
                        fetchDockerContainers()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun fetchTelegramConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // TỐI ƯU HÓA: Tự động trích xuất chuẩn IP từ java.net.URL
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val request = okhttp3.Request.Builder().url("http://$host:5000/api/telegram/config").build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "{}")
                        tgToken = json.optString("token", "")
                        tgChatId = json.optString("chat_id", "")
                        tgEnabled = json.optBoolean("enabled", false)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun saveTelegramConfig(token: String, chatId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val json = org.json.JSONObject().apply {
                    put("token", token)
                    put("chat_id", chatId)
                    put("enabled", enabled)
                }
                val body = json.toString().toRequestBody(jsonMediaType)
                val request = okhttp3.Request.Builder().url("http://$host:5000/api/telegram/config").post(body).build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        tgToken = token; tgChatId = chatId; tgEnabled = enabled
                        commonDialogIcon = Icons.Default.CheckCircle
                        commonDialogColor = androidx.compose.ui.graphics.Color(0xFF43A047)
                        commonDialogMessage = if (enabled) "Đã BẬT và lưu cấu hình Telegram thành công!" else "Đã TẮT cảnh báo Telegram!"
                        showCommonDialog = true
                    } else {
                        commonDialogIcon = Icons.Default.Error
                        commonDialogColor = androidx.compose.ui.graphics.Color.Red
                        commonDialogMessage = "Lỗi lưu cấu hình: ${response.code}"
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {
                commonDialogIcon = Icons.Default.Error
                commonDialogColor = androidx.compose.ui.graphics.Color.Red
                commonDialogMessage = "Lỗi kết nối API: ${e.message}"
                showCommonDialog = true
            }
        }
    }
    fun loadLastAutoSpeedTest(context: android.content.Context) {
        val prefs = context.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
        val w = prefs.getString("last_speed_write", "--") ?: "--"
        val r = prefs.getString("last_speed_read", "--") ?: "--"
        val t = prefs.getString("last_speed_time", "") ?: ""
        if (t.isNotEmpty() && !isTestingSpeed) {
            speedTestResult = SpeedTestResult(w, r)
            lastAutoSpeedTime = "Đo tự động ngầm lúc: $t"
        }
    }

    fun fetchSmartData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Tách port 5000 để gọi đúng Local API Python thay vì WebDAV
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
                val request = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/disk/smart")
                    .build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = org.json.JSONObject(body)
                        smartInfo = SmartInfo(
                            status = json.optString("status", "Unknown"),
                            temperature = json.optString("temperature", "--"),
                            rawLog = json.optString("raw_log", "")
                        )
                    } else {
                        smartInfo = SmartInfo("Lỗi kết nối", "--", "Mã lỗi: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                smartInfo = SmartInfo("Không thể kết nối", "--", e.message ?: "")
            }
        }
    }

    fun runSpeedTest() {
        if (isTestingSpeed) return
        isTestingSpeed = true
        speedTestResult = SpeedTestResult("Đang đo...", "Đang đo...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
                val request = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/disk/speedtest")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                // Tạo client riêng biệt (kế thừa) vì tiến trình này có thể tốn 60 giây đo phần cứng
                val speedTestClient = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                speedTestClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = org.json.JSONObject(body)
                        speedTestResult = SpeedTestResult(
                            writeSpeed = json.optString("write_speed", "Lỗi"),
                            readSpeed = json.optString("read_speed", "Lỗi")
                        )
                    } else {
                        speedTestResult = SpeedTestResult("Thất bại", "Thất bại")
                    }
                }
            } catch (e: Exception) {
                speedTestResult = SpeedTestResult("Lỗi", "Lỗi")
            } finally {
                isTestingSpeed = false
            }
        }
    }

    // ĐỊNH NGHĨA THƯ MỤC THÙNG RÁC (Dấu chấm ở đầu để ẩn thư mục trên NAS)
    private val TRASH_FOLDER_NAME = ".trash/"

    private val urlStack = Stack<String>()

    var currentUrl by mutableStateOf("")
    fun connectAndLoad(url: String, user: String, pass: String) {
        webDavManager.connect(url, user, pass)
        urlStack.clear()
        currentUrl = webDavManager.currentBaseUrl
        loadCurrentUrl()
    }

    fun openFolder(file: NasFile) {
        urlStack.push(currentUrl)
        currentUrl = if (file.path.endsWith("/")) file.path else "${file.path}/"

        // SỬA LỖI: Xóa trắng màn hình lập tức để dọn luồng mạng và tạo phản hồi UI siêu tốc
        fileList = emptyList()
        isLoading = true

        loadCurrentUrl()
    }
    fun openSpecificUrl(url: String, title: String) {
        // Fix cú pháp và đồng bộ tiêu đề Sub-menu
        viewModelScope.launch {
            urlStack.clear()
            isSpecialMode = true
            specialTitle = title
            val targetUrl = if (url.endsWith("/")) url else "$url/"
            currentUrl = targetUrl

            if (title == "Thùng rác") {
                try { withContext(Dispatchers.IO) { webDavManager.createFolder(targetUrl) } } catch(e: Exception) {}
            }

            fileList = emptyList()
            isLoading = true
            loadCurrentUrl()
        }
    }

    fun refresh() {
        // SỬA LỖI REFRESH: Phân loại để gọi đúng hàm truy vấn DB cho sub-menu
        if (isSpecialMode) {
            when (specialTitle) {
                "Ảnh mới nhất" -> showLatestPhotos()
                "Video gần đây" -> showRecentVideos()
                else -> loadCurrentUrl(forceRefresh = true) // Cho Thùng rác
            }
        } else {
            loadCurrentUrl(forceRefresh = true)
        }
    }
    fun resetToDefaultMode() {
        isSpecialMode = false
        specialTitle = ""
        urlStack.clear()
        currentUrl = webDavManager.currentBaseUrl
    }
    fun goBack(): Boolean {
        if (urlStack.isNotEmpty()) {
            currentUrl = urlStack.pop()

            // SỬA LỖI: Nhường toàn bộ băng thông cho lệnh lùi thư mục
            fileList = emptyList()
            isLoading = true

            loadCurrentUrl()
            return true
        }
        return false
    }
    fun showLatestPhotos() {
        viewModelScope.launch {
            isLoading = true
            isSpecialMode = true
            specialTitle = "Ảnh mới nhất"
            // TỰ ĐỘNG LÀM MỚI: Quét nhanh thư mục gốc để cập nhật ảnh mới trước khi hiện
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            fileList = repository.getLatestPhotos()
            isLoading = false
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch {
            isLoading = true
            isSpecialMode = true
            specialTitle = "Video gần đây"
            // TỰ ĐỘNG LÀM MỚI: Cập nhật danh sách từ mạng trước khi hiển thị
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            fileList = repository.getRecentVideos()
            isLoading = false
        }
    }
    // TÍNH NĂNG TÌM KIẾM TOÀN CẦU
    fun searchGlobal(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            isLoading = true
            isSpecialMode = true
            specialTitle = "Tìm kiếm: $keyword"
            urlStack.clear()
            fileList = try { repository.searchGlobal(keyword) } catch(e: Exception) { emptyList() }
            isLoading = false
        }
    }
    // TÍNH NĂNG ĐIỀU KHIỂN NGUỒN VÀ DỊCH VỤ
    fun sendCommandToNas(endpoint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host

                val request = okhttp3.Request.Builder()
                    .url("http://$host:5000/api/$endpoint")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                localApiClient.newCall(request).execute().close()
            } catch(e: Exception) {
                // Lỗi mạng tạm thời khi NAS sập nguồn, bỏ qua không làm crash app
            }
        }
    }

    // GỬI LINK TẢI XUỐNG TỪ XA CHO NAS (QBITTORRENT / WGET)
    fun controlTorrent(action: String, hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val json = org.json.JSONObject().apply {
                    put("action", action)
                    put("hash", hash)
                }
                val requestBody = json.toString().toRequestBody(jsonMediaType)
                val request = okhttp3.Request.Builder()
                    .url("http://$host:5000/api/torrent/control")
                    .post(requestBody)
                    .build()
                localApiClient.newCall(request).execute().close()
            } catch(e: Exception) {}
        }
    }

    fun unzipFile(filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                isUploading = true
                isSyncing = true
                syncStatusText = "Đang giải nén trực tiếp trên NAS..."
                uploadFileName = filePath.substringAfterLast("/")

                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                val uri = java.net.URI(filePath)
                val relativePath = uri.path.substringAfter("/webdav")

                val json = org.json.JSONObject().apply {
                    put("file_path", relativePath)
                }
                val requestBody = json.toString().toRequestBody(jsonMediaType)
                val request = okhttp3.Request.Builder()
                    .url("http://$host:5000/api/file/unzip")
                    .post(requestBody)
                    .build()

                // Kế thừa localApiClient nhưng nới lỏng Timeout để chờ NAS giải nén file ZIP nặng
                val unzipClient = localApiClient.newBuilder()
                    .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                    .build()

                unzipClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        refresh()
                        commonDialogIcon = Icons.Default.CheckCircle
                        commonDialogColor = androidx.compose.ui.graphics.Color(0xFF43A047)
                        commonDialogMessage = "Giải nén thành công!"
                        showCommonDialog = true
                    } else {
                        commonDialogIcon = Icons.Default.Error
                        commonDialogColor = androidx.compose.ui.graphics.Color.Red
                        commonDialogMessage = "Lỗi giải nén: Đảm bảo bạn đã cấu hình đúng webdav_root trong file Python trên NAS!"
                        showCommonDialog = true
                    }
                }
            } catch(e: Exception) {
                commonDialogIcon = Icons.Default.Error
                commonDialogColor = androidx.compose.ui.graphics.Color.Red
                commonDialogMessage = "Lỗi kết nối API: ${e.message}"
                showCommonDialog = true
            } finally {
                isUploading = false
                isSyncing = false
            }
        }
    }

    fun sendDownloadLink(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host

                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = "{\"url\":\"$url\"}".toRequestBody(jsonMediaType)

                val request = okhttp3.Request.Builder()
                    .url("http://$host:5000/api/download")
                    .post(requestBody)
                    .build()
                localApiClient.newCall(request).execute().close()
            } catch(e: Exception) {
                // Bỏ qua lỗi mạng nếu API chưa kịp phản hồi
            }
        }
    }
    // ĐÁNH THỨC NAS BẰNG WAKE-ON-LAN (MAGIC PACKET)
    fun sendWakeOnLan(macStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.nas.naswebdav.util.WolUtil.sendMagicPacket(macStr)
        }
    }
    private fun loadCurrentUrl(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            errorMessage = null
            // TỐI ƯU SMART REFRESH: Kiểm tra nhanh Header của thư mục trước khi quét sâu
            if (!forceRefresh) {
                // KIẾN TRÚC MỚI: Gắn luồng Paging vào UI ngay lập tức từ Cache Room DB
                _pagedFilesFlow.value = repository.getFilesStream(currentUrl).cachedIn(viewModelScope)

                val cached = repository.getCachedFiles(currentUrl)
                if (cached.isNotEmpty()) {
                    isLoading = false

                    // Chạy ngầm việc kiểm tra cập nhật mà không làm treo UI
                    launch(Dispatchers.IO) {
                        try {
                            repository.getRemoteFilesAndCache(currentUrl)
                        } catch (e: Exception) {}
                    }
                    return@launch
                }
            }
            isLoading = true

            try {
                // 3 & 4. Uỷ quyền cho Repository vừa tải mạng vừa tự động lưu Cache
                val remoteFilesFromNas = repository.getRemoteFilesAndCache(currentUrl)

                // BỘ LỌC THÔNG MINH: Ẩn tệp/thư mục ẩn (bắt đầu bằng dấu '.') ở chế độ duyệt thường
                val filteredFiles = if (!isSpecialMode) {
                    remoteFilesFromNas.filter { !it.name.startsWith(".") }
                } else {
                    remoteFilesFromNas
                }
                fileList = filteredFiles // Phục vụ cho các chế độ non-paging

                // Cập nhật lại Paging flow sau khi mạng đã nạp xong (để Pager tự load từ DB lên)
                _pagedFilesFlow.value = repository.getFilesStream(currentUrl).cachedIn(viewModelScope)

                connectionStatus = "Đã kết nối"
            } catch (e: Exception) {
                connectionStatus = "Lỗi kết nối" // Ép cập nhật trạng thái lỗi ngay lập tức dù có Cache hay không
                if (fileList.isEmpty()) {
                    errorMessage = "Chi tiết lỗi: ${e.message ?: e.toString()}"
                }
            } finally {
                isLoading = false
            }
        }
    }
    fun deleteFile(file: NasFile) {
        viewModelScope.launch {
            try {
                isLoading = true
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME

                // CHẶN XOÁ VĨNH VIỄN: Chỉ cho phép di chuyển vào thùng rác
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) {}
                    webDavManager.renameFile(file.path, trashUrl + file.name)
                }
                refresh()
            } catch (e: Exception) {
                errorMessage = "Lỗi bảo vệ tệp: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun restoreFile(file: NasFile) {
        viewModelScope.launch {
            try {
                isLoading = true
                // KHÔI PHỤC: Di chuyển file từ rác về thư mục gốc của NAS
                val targetUrl = webDavManager.currentBaseUrl + file.name
                webDavManager.renameFile(file.path, targetUrl)
                refresh()
            } catch (e: Exception) {
                errorMessage = "Lỗi khôi phục: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    fun renameFile(file: NasFile, newName: String) {
        viewModelScope.launch {
            try {
                isLoading = true
                val newUrl = currentUrl + newName
                webDavManager.renameFile(file.path, newUrl)
                refresh() // Tải lại danh sách sau khi đổi tên
            } catch (e: Exception) {
                errorMessage = "Lỗi khi đổi tên: ${e.message}"
                isLoading = false
            }
        }
    }
    fun createFolder(folderName: String) {
        viewModelScope.launch {
            try {
                isLoading = true
                // Đảm bảo URL thư mục mới kết thúc bằng dấu gạch chéo '/'
                val newFolderUrl = currentUrl + folderName + "/"
                webDavManager.createFolder(newFolderUrl)
                refresh() // Tải lại danh sách sau khi tạo thành công
            } catch (e: Exception) {
                errorMessage = "Lỗi khi tạo thư mục: ${e.message}"
                isLoading = false
            }
        }
    }
    fun uploadFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                // Hiển thị Dialog Upload thay vì vòng xoay tròn
                isUploading = true

                // 1. Trích xuất tên tệp, KÍCH THƯỚC và định dạng từ Uri
                var fileName = "uploaded_file_${System.currentTimeMillis()}"
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // QUAN TRỌNG: Xóa bỏ khoảng trắng và ký tự đặc biệt trong tên file để WebDAV không bị lỗi URL
                fileName = fileName.replace(" ", "_").replace("[^a-zA-Z0-9._-]".toRegex(), "")
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                withContext(Dispatchers.IO) {
                    val fileUrl = currentUrl + fileName

                    // 2. Trích xuất Thumbnail cục bộ (nếu là video) bằng FileDescriptor thay vì tạo tempFile
                    if (mimeType.startsWith("video/")) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                retriever.setDataSource(pfd.fileDescriptor)

                                val seed = fileUrl.hashCode().toLong()
                                val randomTimeUs = (java.util.Random(seed).nextInt(2000) + 1000) * 1000L
                                var bitmap = retriever.getFrameAtTime(randomTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                                if (bitmap == null) {
                                    bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                }

                                if (bitmap != null) {
                                    val thumbFile = java.io.File(context.cacheDir, "thumb_${fileUrl.hashCode()}.jpg")
                                    java.io.FileOutputStream(thumbFile).use { out ->
                                        bitmap!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    }
                                }
                            }
                            retriever.release()
                        } catch (e: Exception) {}
                    }

                    // 3. Đẩy luồng dữ liệu (Stream) trực tiếp lên NAS - KHÔNG tốn RAM/Disk
                    context.contentResolver.openInputStream(uri)?.let { inputStream ->
                        webDavManager.uploadStreamWithProgress(fileUrl, inputStream, fileSize, mimeType) { bytes, total ->
                            // Đẩy tiến trình về Main Thread để Compose cập nhật giao diện
                            viewModelScope.launch {
                                uploadBytes = bytes
                                uploadTotalBytes = total
                            }
                        }
                    } ?: throw Exception("Không thể đọc dữ liệu từ tệp gốc")
                }

                refresh() // Tải lại danh sách
                // Hiển thị thông báo thành công cho người dùng
                commonDialogIcon = Icons.Default.CheckCircle
                commonDialogColor = Color(0xFF43A047)
                commonDialogMessage = "Tải lên thành công: $fileName"
                showCommonDialog = true

            } catch (e: Exception) {
                errorMessage = "Lỗi tải lên: ${e.message}"
                // Hiển thị trực tiếp lỗi lên màn hình để dễ bắt bệnh nếu có sự cố
                commonDialogIcon = Icons.Default.Error
                commonDialogColor = Color(0xFFE53935)
                commonDialogMessage = "Tải lên thất bại: ${e.message}"
                showCommonDialog = true
            } finally {
                isUploading = false // Tắt hộp thoại tiến trình
                uploadBytes = 0L
                uploadTotalBytes = 0L
            }
        }
    }

    fun syncLocalFolder(context: android.content.Context, treeUri: android.net.Uri) {
        isSyncing = true
        isUploading = true
        syncStatusText = "Đang khởi tạo tiến trình nền..."
        uploadFileName = "Đang chạy ngầm, bạn có thể thu nhỏ ứng dụng..."

        val workManager = androidx.work.WorkManager.getInstance(context)
        val inputData = androidx.work.workDataOf(
            "treeUri" to treeUri.toString(),
            "currentUrl" to currentUrl,
            "user" to webDavManager.currentUser,
            "pass" to webDavManager.currentPass
        )

        // Tạo yêu cầu công việc chạy 1 lần duy nhất
        val syncWorkRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueue(syncWorkRequest)

        // Theo dõi tiến trình từ WorkManager bằng Flow để tự cập nhật UI mượt mà
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(syncWorkRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    val statusText = workInfo.progress.getString("status")
                    if (statusText != null) syncStatusText = statusText

                    val fileName = workInfo.progress.getString("fileName")
                    if (fileName != null) uploadFileName = fileName

                    if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        isSyncing = false
                        isUploading = false
                        refresh()
                        commonDialogIcon = Icons.Default.CheckCircle
                        commonDialogColor = Color(0xFF43A047)
                        commonDialogMessage = "Đồng bộ nền hoàn tất!"
                        showCommonDialog = true
                    } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                        isSyncing = false
                        isUploading = false
                        commonDialogIcon = Icons.Default.Error
                        commonDialogColor = Color(0xFFE53935)
                        commonDialogMessage = "Lỗi đồng bộ chạy ngầm!"
                        showCommonDialog = true
                    }
                }
            }
        }
    }
    fun startBackgroundDuplicateScan(context: android.content.Context) {
        commonDialogIcon = Icons.Default.Info
        commonDialogColor = Color(0xFF1E88E5)
        commonDialogMessage = "Đã nhận lệnh! Đang khởi động trình quét rác..."
        showCommonDialog = true

        isScanningDuplicates = true
        if (isWorkerRunning) return

        isWorkerRunning = true
        scanDuplicatesCurrentFolderUrl = "Đang kết nối..."
        scanDuplicatesCurrentItemName = "Khởi tạo..."
        scanDuplicatesTotalScanned = 0
        scanDuplicatesFound = 0

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(500)
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)
                val inputData = androidx.work.workDataOf(
                    "currentUrl" to currentUrl,
                    "user" to webDavManager.currentUser,
                    "pass" to webDavManager.currentPass
                )

                val scanWorkRequest = androidx.work.OneTimeWorkRequestBuilder<DuplicateScanWorker>()
                    .setInputData(inputData)
                    .build()

                workManager.enqueueUniqueWork("Unique_Scan_V3", androidx.work.ExistingWorkPolicy.REPLACE, scanWorkRequest)

                workManager.getWorkInfoByIdFlow(scanWorkRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        workInfo.progress.getString("currentFolderUrl")?.let { scanDuplicatesCurrentFolderUrl = it }
                        workInfo.progress.getString("itemName")?.let { scanDuplicatesCurrentItemName = it }
                        scanDuplicatesIsFolder = workInfo.progress.getBoolean("isFolder", false)

                        val count = workInfo.progress.getInt("scannedCount", -1)
                        if (count >= 0) scanDuplicatesTotalScanned = count

                        val found = workInfo.progress.getInt("foundCount", -1)
                        if (found >= 0) scanDuplicatesFound = found

                        scanDuplicatesPercent = workInfo.progress.getFloat("percent", 0f)

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            scanDuplicatesCurrentFolderUrl = "Hoất tất!"
                            scanDuplicatesCurrentItemName = "Đã quét xong toàn bộ."
                            isWorkerRunning = false
                            loadDuplicateResultsFromCache(context)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            scanDuplicatesCurrentFolderUrl = "Gặp lỗi hệ thống!"
                            isWorkerRunning = false
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isWorkerRunning = false
                        }
                    }
                }
            } catch (e: Exception) {
                isWorkerRunning = false
            }
        }
    }

    private fun loadDuplicateResultsFromCache(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val duplicates = repository.getDuplicateFiles()
                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicates
                    isShowingDuplicates = true
                    if (duplicateFilesList.isEmpty()) {
                        errorMessage = "NAS của bạn rất gọn gàng! Không có file trùng lặp."
                    } else {
                        errorMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lỗi nạp danh sách từ DB: ${e.message}" }
            }
        }
    }

    fun deleteDuplicateFile(file: NasFile) {
        viewModelScope.launch {
            try {
                isLoading = true
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME

                // 1. Kiểm tra nếu file đang ở trong thùng rác rồi thì xoá vĩnh viễn
                if (file.path.contains(TRASH_FOLDER_NAME)) {
                    webDavManager.deleteFile(file.path)
                } else {
                    // 2. Nếu chưa, hãy đảm bảo thư mục thùng rác tồn tại và di chuyển vào đó
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { /* Đã tồn tại */ }

                    val targetUrl = trashUrl + file.name
                    webDavManager.renameFile(file.path, targetUrl)
                }

                duplicateFilesList = duplicateFilesList.filter { it.path != file.path }
                withContext(Dispatchers.IO) {
                    repository.removeDuplicateFromDb(file.path)
                }
                refresh()
            } catch (e: Exception) {
                errorMessage = "Lỗi xử lý thùng rác: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteSelectedDuplicates() {
        viewModelScope.launch {
            try {
                isLoading = true
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME
                try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { }

                for (file in selectedDuplicates) {
                    if (file.path.contains(TRASH_FOLDER_NAME)) {
                        webDavManager.deleteFile(file.path)
                    } else {
                        val targetUrl = trashUrl + file.name
                        webDavManager.renameFile(file.path, targetUrl)
                    }
                }

                val deletedPaths = selectedDuplicates.map { it.path }.toSet()
                withContext(Dispatchers.IO) {
                    for (path in deletedPaths) {
                        repository.removeDuplicateFromDb(path)
                    }
                }
                duplicateFilesList = duplicateFilesList.filter { it.path !in deletedPaths }
                selectedDuplicates.clear()
                refresh()
            } catch (e: Exception) {
                errorMessage = "Lỗi dọn rác hàng loạt: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    fun scheduleIdleDuplicateScan(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl,
            "user" to webDavManager.currentUser,
            "pass" to webDavManager.currentPass
        )

        val periodicScanRequest = androidx.work.PeriodicWorkRequestBuilder<DuplicateScanWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Duplicate_Scan",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            periodicScanRequest
        )
    }

    fun scheduleIdleSpeedTest(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresDeviceIdle(true) // ĐIỀU KIỆN 1: Điện thoại đang tắt màn hình, không sử dụng
            .setRequiresCharging(true)   // ĐIỀU KIỆN 2: Đang cắm sạc (Đảm bảo an toàn pin)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // ĐIỀU KIỆN 3: Có Wi-Fi
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl
        )

        // CHU KỲ BẢO VỆ Ổ CỨNG: Chỉ lén chạy Stress Test 30 ngày 1 lần để không làm giảm tuổi thọ ổ đĩa
        val periodicSpeedTestRequest = androidx.work.PeriodicWorkRequestBuilder<IdleSpeedTestWorker>(
            30, java.util.concurrent.TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Speed_Test",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Giữ nguyên lịch trình cũ nếu đã tồn tại
            periodicSpeedTestRequest
        )
    }

    fun connect(url: String, user: String, pass: String) {
        currentUrl = if (!url.endsWith("/")) "$url/" else url
        // 1. NGAY LẬP TỨC THỰC HIỆN HANDSHAKE ĐỂ CẤP PHÉP IP
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
                val authHeader = okhttp3.Credentials.basic(user, pass)
                val request = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/auth/authorize")
                    .header("Authorization", authHeader)
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            connectionStatus = "Đã xác thực IP thành công"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NAS_AUTH", "Không thể xác thực IP: ${e.message}")
            }
        }
        webDavManager.currentUser = user
        webDavManager.currentPass = pass
        webDavManager.initConnection()
        urlStack.clear()
        refresh()
    }
}