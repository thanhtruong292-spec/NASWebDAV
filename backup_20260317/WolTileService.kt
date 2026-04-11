package com.nas.naswebdav

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nas.naswebdav.util.WolUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WolTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        val macStr = prefs.getString("mac_address", "") ?: ""

        val tile = qsTile
        if (tile != null) {
            // Nút chỉ sáng lên cho phép bấm nếu bạn đã từng lưu địa chỉ MAC NAS trong App
            tile.state = if (macStr.isNotBlank()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        val macStr = prefs.getString("mac_address", "") ?: ""
        val tile = qsTile ?: return

        if (macStr.isNotBlank()) {
            // Chớp sáng nút lên để tạo phản hồi thị giác
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()

            CoroutineScope(Dispatchers.IO).launch {
                WolUtil.sendMagicPacket(macStr)

                // Giữ đèn báo sáng 1.5 giây rồi tắt (Mô phỏng như nút khởi động xe hơi)
                delay(1500)
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }
}