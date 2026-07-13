package com.nas.naswebdav.shared

/**
 * Trạng thái kết nối WebDAV — thay thế cho các String hiện rải rác trong ViewModel.
 *
 * UI sẽ dùng `status.message` để hiển thị tiếng Việt, và `status.state` để phân biệt
 * trạng thái trong code (vd: hiện spinner hay không).
 */
sealed class ConnectionStatus(val message: String) {
    /** Chưa đăng nhập, app vừa mở */
    object Idle : ConnectionStatus("Chưa kết nối")

    /** Đang ping kiểm tra môi trường mạng */
    object CheckingLan : ConnectionStatus("Đang kiểm tra môi trường LAN...")

    /** Đang thử kết nối tới 1 URL cụ thể */
    data class Connecting(val url: String) : ConnectionStatus("Đang kết nối: $url")

    /** Kết nối thành công */
    data class Connected(val url: String) : ConnectionStatus("Đã kết nối: $url")

    /** Đã xác thực Basic Auth thành công */
    object Authenticated : ConnectionStatus("Đã xác thực thành công")

    /** Lỗi mạng / DNS / timeout */
    object NetworkError : ConnectionStatus("Lỗi kết nối")

    /** Sai username/password */
    object AuthFailed : ConnectionStatus("Lỗi xác thực")

    /** User bấm Hủy login */
    object Cancelled : ConnectionStatus("Đã dừng đăng nhập")

    /** Smart network switch */
    data class SwitchedToLan(val url: String) : ConnectionStatus("Chuyển sang LAN - Gigabit")
    data class SwitchedToTailscale(val url: String) : ConnectionStatus("Chuyển sang Tailscale VPN")
    data class OnLan(val url: String) : ConnectionStatus("LAN - Gigabit")
    data class OnTailscale(val url: String) : ConnectionStatus("Tailscale VPN")
}