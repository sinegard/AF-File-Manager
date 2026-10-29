package com.affilemanager.app.advanced

/** Small, versioned Binder payload. No filenames, content or unbounded xattr lists. */
internal data class PrivilegedFileMetadata(
    val device: Long,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    val mode: Int,
    val selinuxHex: String?,
) {
    fun encode(): Array<String> = arrayOf(
        VERSION.toString(), device.toString(), inode.toString(), uid.toString(),
        gid.toString(), mode.toString(), selinuxHex ?: "-",
    )

    fun sameSecurity(other: PrivilegedFileMetadata): Boolean =
        uid == other.uid && gid == other.gid && mode == other.mode && selinuxHex == other.selinuxHex

    fun sameEntry(other: PrivilegedFileMetadata): Boolean = device == other.device && inode == other.inode

    companion object {
        const val VERSION = 2
        const val MAX_LABEL_BYTES = 4_096
        private const val MAX_PAYLOAD_BYTES = 16_384

        fun decode(fields: Array<String>): PrivilegedFileMetadata {
            // Parcel strings are UTF-16, with lengths/terminators/padding. Reserve8 bytes each.
            require(fields.size == 7 && 4L + fields.sumOf { 8L + it.length.toLong() * 2 } <= MAX_PAYLOAD_BYTES)
            require(fields[0] == VERSION.toString()) { "AF-META-VERSION" }
            val label = fields[6].takeUnless { it == "-" }
            require(label == null || (label.isNotEmpty() && label.length <= MAX_LABEL_BYTES * 2 &&
                label.length % 2 == 0 && label.all { it in '0'..'9' || it in 'a'..'f' }))
            if (label != null) {
                val bytes = ByteArray(label.length / 2) { label.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                require(bytes.last() == 0.toByte() && bytes.dropLast(1).none { it == 0.toByte() }) { "AF-META-LABEL" }
                val context = String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
                require(context.matches(Regex("[A-Za-z0-9_]+:[A-Za-z0-9_]+:[A-Za-z0-9_]+:s[0-9]+[A-Za-z0-9_:,.-]*"))) { "AF-META-LABEL" }
            }
            return PrivilegedFileMetadata(
                fields[1].toLong(), fields[2].toLong(), fields[3].toInt(), fields[4].toInt(),
                fields[5].toInt(), label,
            ).also {
                require(it.uid >= 0 && it.gid >= 0 && it.mode in 0..0xffff)
                require(it.mode and 0xf000 == 0x8000 || it.mode and 0xf000 == 0x4000) { "AF-META-TYPE" }
            }
        }
    }
}
