package com.nas.naswebdav.shared

import android.content.Context
import com.nas.naswebdav.NasApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holder singleton cho các state CHIA SẺ giữa các domain ViewModels.
 *
 * Tại sao không truyền VM-to-VM qua constructor?
 *  - Circular dependency: AuthSession cần currentUrl để file browser dùng,
 *    nhưng đôi khi FileBrowser cũng trigger checkSmartNetwork.
 *  - Lifecycle: ViewModel có thể bị destroy nhưng auth state vẫn phải sống.
 *
 * Tại sao không dùng singleton trực tiếp từ ViewModel?
 *  - Test: có thể inject mock SharedStateHolder trong unit test.
 *  - Boundary rõ ràng: domain VM nào được đọc/gì state nào.
 *
 * Scope: chỉ chứa state cần share giữa ≥2 VMs.
 * State nội bộ (livestream jobs, backup schedule, system metrics) KHÔNG vào đây.
 */
object SharedStateHolder {

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isOnLan = MutableStateFlow(true) // true = LAN, false = Tailscale
    val isOnLan: StateFlow<Boolean> = _isOnLan.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Application context — chỉ dùng để gọi SharedPreferences/system services.
     * KHÔNG lưu UI Context (Activity/View) ở đây.
     */
    private var _appContext: Context? = null

    /**
     * Khởi tạo 1 lần trong NasApplication.onCreate().
     * Sau đó SharedStateHolder.currentUrl.value = "..." có thể gọi từ bất kỳ domain VM.
     */
    fun init(context: Context) {
        _appContext = context.applicationContext
    }

    fun appContext(): Context =
        _appContext ?: NasApplication.instance.applicationContext

    // ─── Update helpers — các domain VM gọi các hàm này thay vì ghi trực tiếp vào StateFlow ───

    fun updateCurrentUrl(url: String) {
        _currentUrl.value = url
    }

    fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun updateIsOnLan(onLan: Boolean) {
        _isOnLan.value = onLan
    }

    fun updateErrorMessage(message: String?) {
        _errorMessage.value = message
    }

    /**
     * Reset toàn bộ state khi logout.
     */
    fun reset() {
        _currentUrl.value = ""
        _connectionStatus.value = ConnectionStatus.Idle
        _isOnLan.value = true
        _errorMessage.value = null
    }
}