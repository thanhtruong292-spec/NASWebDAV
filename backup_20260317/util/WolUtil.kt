package com.nas.naswebdav.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Utility object cho chức năng Wake-on-LAN.
 * Dùng chung cho cả WebDavViewModel và WolTileService để tránh code trùng lặp.
 */
object WolUtil {
    /**
     * Gửi Magic Packet Wake-on-LAN tới địa chỉ MAC cho trước.
     * @param macAddress Địa chỉ MAC dạng "00:1A:2B:3C:4D:5E" hoặc "00-1A-2B-3C-4D-5E"
     * @return true nếu gửi thành công, false nếu lỗi
     */
    fun sendMagicPacket(macAddress: String): Boolean {
        return try {
            val cleanMac = macAddress.replace(":", "").replace("-", "")
            if (cleanMac.length != 12) return false

            val macBytes = ByteArray(6)
            for (i in 0..5) {
                macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            // Magic Packet: 6 bytes of 0xFF followed by 16 repetitions of MAC
            val magicPacket = ByteArray(6 + 16 * macBytes.size)
            for (i in 0..5) magicPacket[i] = 0xFF.toByte()
            var offset = 6
            while (offset < magicPacket.size) {
                System.arraycopy(macBytes, 0, magicPacket, offset, macBytes.size)
                offset += macBytes.size
            }

            val address = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(magicPacket, magicPacket.size, address, 9)
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.send(packet)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
