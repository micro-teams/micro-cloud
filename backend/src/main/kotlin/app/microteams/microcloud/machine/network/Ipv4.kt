/*
 *  Description: Small IPv4 helpers — parse a dotted-quad into an unsigned 32-bit value (held in a
 *               Long) and back. Used to validate network ranges and count addresses.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.network

import org.rucca.cheese.common.error.BadRequestError

object Ipv4 {
    /** Parse "a.b.c.d" into a value in [0, 2^32); rejects anything malformed. */
    fun parse(ip: String): Long {
        val parts = ip.trim().split(".")
        if (parts.size != 4) throw BadRequestError("not an IPv4 address: $ip")
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: throw BadRequestError("not an IPv4 address: $ip")
            if (octet !in 0..255) throw BadRequestError("not an IPv4 address: $ip")
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun format(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    /** Count of addresses in the inclusive range [startIp, endIp]; validates ordering. */
    fun rangeSize(startIp: String, endIp: String): Long {
        val start = parse(startIp)
        val end = parse(endIp)
        if (end < start) throw BadRequestError("endIp must not be before startIp")
        return end - start + 1
    }
}
