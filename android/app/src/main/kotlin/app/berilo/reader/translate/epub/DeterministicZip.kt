package app.berilo.reader.translate.epub

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes zip archives byte-for-byte the way Python's `zipfile` writes them.
 *
 * [EpubWriter] must produce an archive **byte-identical** to `berilo.assemble.build_epub`'s
 * (see that class for why), and `java.util.zip.ZipOutputStream` provably cannot: measured
 * against CPython 3.10's output, it disagrees in three header fields on every archive.
 *
 * | field | Python `zipfile` | `ZipOutputStream` |
 * |---|---|---|
 * | local + central "version needed to extract" | 20 for every entry | **10** for STORED |
 * | central "version made by" high byte (create system) | **3** (Unix) | 0 (MS-DOS) |
 * | central "external file attributes" | `0x01800000` (`0o600 << 16`) | 0 |
 *
 * `ZipOutputStream` also flips general-purpose bit 3 and appends a data descriptor whenever a
 * DEFLATED entry's size is not pre-declared, which Python never does when writing to a seekable
 * file. So the archive is assembled here by hand — 96 bytes of header layout against a
 * dependency-free `java.util.zip.Deflater`, whose output was verified byte-identical to
 * `zlib.compressobj(Z_DEFAULT_COMPRESSION, DEFLATED, -15)` (both sides zlib 1.2.11).
 *
 * Only the fixed shape `build_epub` emits is supported: no zip64, no comments, no extra fields,
 * no directory entries, and every timestamp pinned to the DOS epoch. Anything outside it fails
 * loudly rather than producing an archive that merely opens.
 */
internal object DeterministicZip {

    /** Compression method: the bytes are written through unchanged. */
    const val STORED = 0

    /** Compression method: raw DEFLATE at zlib's default level, matching Python's default. */
    const val DEFLATED = 8

    /**
     * MS-DOS date for 1980-01-01: `(year - 1980) shl 9 or (month shl 5) or day`.
     *
     * The earliest date the format can represent, and what `assemble._ZIP_EPOCH` pins every
     * entry to — a wall-clock timestamp would make re-assembly of the same book non-identical.
     */
    private const val DOS_DATE_EPOCH = 33

    /** MS-DOS time for 00:00:00. */
    private const val DOS_TIME_MIDNIGHT = 0

    /** `zipfile.ZipInfo.extract_version`, written for **every** entry regardless of method. */
    private const val EXTRACT_VERSION = 20

    /** `zipfile.ZipInfo.create_version`. */
    private const val CREATE_VERSION = 20

    /** `zipfile.ZipInfo.create_system` on Linux: 3 = Unix. */
    private const val CREATE_SYSTEM_UNIX = 3

    /** `0o600 << 16` — what `ZipFile._open_to_write` stamps on an entry with no attributes. */
    private const val EXTERNAL_ATTRIBUTES = 0x0180_0000

    private const val LOCAL_HEADER_SIGNATURE = 0x0403_4B50
    private const val CENTRAL_HEADER_SIGNATURE = 0x0201_4B50
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x0605_4B50

    /** Fixed byte length of a local file header, before the file name. */
    private const val LOCAL_HEADER_LENGTH = 30

    /** Fixed byte length of a central directory record, before the file name. */
    private const val CENTRAL_HEADER_LENGTH = 46

    /**
     * `zipfile.ZIP64_LIMIT`. Above it Python switches to zip64, which changes the header
     * layout; nothing here follows it, so an oversized archive throws instead.
     */
    private const val ZIP64_LIMIT = 0x7FFF_FFFFL

    private const val DEFLATE_BUFFER_BYTES = 16 * 1024

    /**
     * One archive member, in the exact order it is to be written.
     *
     * @property name Zip-internal path. Must be ASCII: a non-ASCII name would make Python set
     *   the UTF-8 filename flag, which this writer does not reproduce.
     * @property data The member's uncompressed bytes.
     * @property method [STORED] or [DEFLATED].
     */
    data class Member(val name: String, val data: ByteArray, val method: Int) {

        /** Value equality including the bytes — a data class compares [data] by reference. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Member) return false
            return name == other.name && method == other.method && data.contentEquals(other.data)
        }

        override fun hashCode(): Int =
            31 * (31 * name.hashCode() + data.contentHashCode()) + method
    }

    /**
     * Write [members] to [target] as a zip archive, in the order given.
     *
     * @param target Destination stream; not closed here.
     * @param members The archive's entries, already in their final order.
     * @throws IllegalArgumentException If a name is not ASCII, a method is unsupported, or the
     *   archive would need zip64.
     */
    fun write(target: OutputStream, members: List<Member>) {
        var offset = 0L
        val central = GrowableBytes()
        for (member in members) {
            val name = asciiName(member.name)
            val payload = compress(member)
            val crc = CRC32().apply { update(member.data) }.value
            require(offset <= ZIP64_LIMIT) {
                "Archive exceeds the ${ZIP64_LIMIT} byte zip64 threshold at ${member.name}; " +
                    "Python would switch header formats and this writer does not follow it"
            }
            require(
                member.data.size.toLong() <= ZIP64_LIMIT && payload.size.toLong() <= ZIP64_LIMIT,
            ) {
                "Entry ${member.name} exceeds the ${ZIP64_LIMIT} byte zip64 threshold"
            }

            target.write(localHeader(name, member.method, crc, payload.size, member.data.size))
            target.write(payload)
            central.write(
                centralHeader(name, member.method, crc, payload.size, member.data.size, offset),
            )
            offset += LOCAL_HEADER_LENGTH + name.size + payload.size
        }

        val directory = central.toByteArray()
        target.write(directory)
        target.write(endOfCentralDirectory(members.size, directory.size, offset))
    }

    /** Encode a member name, refusing anything that would flip Python's UTF-8 filename flag. */
    private fun asciiName(name: String): ByteArray {
        require(name.all { it.code in 1..127 }) {
            "Zip entry name must be ASCII (Python would set the UTF-8 name flag): $name"
        }
        return name.toByteArray(Charsets.US_ASCII)
    }

    /** Apply a member's compression method, matching Python's zlib parameters exactly. */
    private fun compress(member: Member): ByteArray =
        when (member.method) {
            STORED -> member.data
            DEFLATED -> deflate(member.data)
            else ->
                throw IllegalArgumentException(
                    "Unsupported compression method: ${member.method}",
                )
        }

    /** Raw DEFLATE at zlib's default level — `zlib.compressobj(-1, DEFLATED, -15)`. */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = GrowableBytes()
            val buffer = ByteArray(DEFLATE_BUFFER_BYTES)
            while (!deflater.finished()) {
                val produced = deflater.deflate(buffer)
                out.write(buffer, produced)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun localHeader(
        name: ByteArray,
        method: Int,
        crc: Long,
        compressedSize: Int,
        size: Int,
    ): ByteArray {
        val header = ByteArray(LOCAL_HEADER_LENGTH + name.size)
        putInt(header, 0, LOCAL_HEADER_SIGNATURE.toLong())
        putShort(header, 4, EXTRACT_VERSION)
        putShort(header, 6, 0) // general purpose bit flag: no data descriptor, ASCII name
        putShort(header, 8, method)
        putShort(header, 10, DOS_TIME_MIDNIGHT)
        putShort(header, 12, DOS_DATE_EPOCH)
        putInt(header, 14, crc)
        putInt(header, 18, compressedSize.toLong())
        putInt(header, 22, size.toLong())
        putShort(header, 26, name.size)
        putShort(header, 28, 0) // extra field length
        name.copyInto(header, LOCAL_HEADER_LENGTH)
        return header
    }

    private fun centralHeader(
        name: ByteArray,
        method: Int,
        crc: Long,
        compressedSize: Int,
        size: Int,
        headerOffset: Long,
    ): ByteArray {
        val header = ByteArray(CENTRAL_HEADER_LENGTH + name.size)
        putInt(header, 0, CENTRAL_HEADER_SIGNATURE.toLong())
        header[4] = CREATE_VERSION.toByte()
        header[5] = CREATE_SYSTEM_UNIX.toByte()
        header[6] = EXTRACT_VERSION.toByte()
        header[7] = 0 // reserved
        putShort(header, 8, 0) // general purpose bit flag
        putShort(header, 10, method)
        putShort(header, 12, DOS_TIME_MIDNIGHT)
        putShort(header, 14, DOS_DATE_EPOCH)
        putInt(header, 16, crc)
        putInt(header, 20, compressedSize.toLong())
        putInt(header, 24, size.toLong())
        putShort(header, 28, name.size)
        putShort(header, 30, 0) // extra field length
        putShort(header, 32, 0) // file comment length
        putShort(header, 34, 0) // disk number start
        putShort(header, 36, 0) // internal file attributes
        putInt(header, 38, EXTERNAL_ATTRIBUTES.toLong())
        putInt(header, 42, headerOffset)
        name.copyInto(header, CENTRAL_HEADER_LENGTH)
        return header
    }

    private fun endOfCentralDirectory(
        entryCount: Int,
        directorySize: Int,
        directoryOffset: Long,
    ): ByteArray {
        val record = ByteArray(22)
        putInt(record, 0, END_OF_CENTRAL_DIRECTORY_SIGNATURE.toLong())
        putShort(record, 4, 0) // number of this disk
        putShort(record, 6, 0) // disk where the central directory starts
        putShort(record, 8, entryCount)
        putShort(record, 10, entryCount)
        putInt(record, 12, directorySize.toLong())
        putInt(record, 16, directoryOffset)
        putShort(record, 20, 0) // archive comment length
        return record
    }

    /** Write a 16-bit little-endian field. */
    private fun putShort(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    /** Write a 32-bit little-endian field. */
    private fun putInt(target: ByteArray, at: Int, value: Long) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
        target[at + 2] = ((value ushr 16) and 0xFF).toByte()
        target[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** A minimal growable byte buffer: `ByteArrayOutputStream` synchronizes every write for nothing. */
    private class GrowableBytes {
        private var buffer = ByteArray(DEFLATE_BUFFER_BYTES)
        private var length = 0

        fun write(bytes: ByteArray, count: Int = bytes.size) {
            if (length + count > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
            }
            bytes.copyInto(buffer, length, 0, count)
            length += count
        }

        fun toByteArray(): ByteArray = buffer.copyOf(length)
    }
}
