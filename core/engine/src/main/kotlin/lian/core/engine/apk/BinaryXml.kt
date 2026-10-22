package lian.core.engine.apk

/** Android's compiled resource string pool (ResStringPool), shared by AndroidManifest.xml and resources.arsc. */
internal class StringPool(b: ByteArray, start: Int) {
    private val strings: Array<String?>

    init {
        val headerSize = le16(b, start + 2)
        val count = le32(b, start + 8).toInt()
        val flags = le32(b, start + 16)
        val stringsStart = le32(b, start + 20).toInt()
        if (count < 0 || count > MAX_STRINGS) throw ApkFormatException("string pool too large")
        val utf8 = flags and UTF8_FLAG != 0L
        strings = Array(count) { i ->
            val offsetPos = start + headerSize + i * 4
            if (offsetPos + 4 > b.size) return@Array null
            val pos = start + stringsStart + le32(b, offsetPos).toInt()
            try { if (utf8) utf8At(b, pos) else utf16At(b, pos) } catch (_: IndexOutOfBoundsException) { null }
        }
    }

    operator fun get(index: Long): String? = if (index in 0 until strings.size) strings[index.toInt()] else null

    private fun utf8At(b: ByteArray, p0: Int): String {
        var p = p0
        // Length in characters, then in bytes; each is 1 byte, or 2 with the high bit set.
        p += if (b[p].toInt() and 0x80 != 0) 2 else 1
        var len = b[p].toInt() and 0xFF
        if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2 } else p += 1
        return String(b, p, minOf(len, MAX_CHARS * 4), Charsets.UTF_8)
    }

    private fun utf16At(b: ByteArray, p0: Int): String {
        var p = p0
        var len = le16(b, p)
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or le16(b, p + 2); p += 4 } else p += 2
        val n = minOf(len, MAX_CHARS)
        return String(CharArray(n) { le16(b, p + it * 2).toChar() })
    }

    companion object {
        private const val UTF8_FLAG = 0x100L
        private const val MAX_STRINGS = 500_000
        private const val MAX_CHARS = 4_096
    }
}

/** One element of the compiled manifest: name, attributes keyed by resource id or name, and children. */
class XmlElement(val name: String, val attributes: List<XmlAttribute>, val children: MutableList<XmlElement> = ArrayList()) {
    fun attr(resId: Long, name: String): XmlAttribute? = attributes.firstOrNull { it.resId == resId } ?: attributes.firstOrNull { it.name == name && it.resId == 0L }
    fun string(resId: Long, name: String): String? = attr(resId, name)?.string
    fun bool(resId: Long, name: String): Boolean? = attr(resId, name)?.bool
    fun int(resId: Long, name: String): Long? = attr(resId, name)?.int
    fun children(tag: String): List<XmlElement> = children.filter { it.name == tag }
}

class XmlAttribute(val name: String, val resId: Long, val type: Int, val data: Long, val rawString: String?) {
    val string: String? get() = if (type == TYPE_STRING) rawString else rawString ?: if (type in INT_TYPES) data.toString() else null
    val bool: Boolean? get() = when (type) { TYPE_BOOLEAN -> data != 0L; TYPE_STRING -> rawString?.toBooleanStrictOrNull(); else -> null }
    val int: Long? get() = when (type) { in INT_TYPES -> data; TYPE_STRING -> rawString?.toLongOrNull(); else -> null }
    /** Resource id when the value is a reference (e.g. `@string/app_name`). */
    val reference: Long? get() = if (type == TYPE_REFERENCE) data else null

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_BOOLEAN = 0x12
        private val INT_TYPES = setOf(0x10, 0x11)
    }
}

/**
 * Parses Android binary XML (AXML) into a small element tree. Bounded: at most [MAX_ELEMENTS]
 * elements and [MAX_DEPTH] nesting levels; anything malformed raises [ApkFormatException].
 */
object BinaryXml {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val MAX_ELEMENTS = 20_000
    private const val MAX_DEPTH = 64
    private const val NO_INDEX = 0xFFFFFFFFL

    fun parse(b: ByteArray): XmlElement {
        if (b.size < 8 || le16(b, 0) != RES_XML_TYPE) throw ApkFormatException("not a binary XML file")
        var pool: StringPool? = null
        var resIds = LongArray(0)
        val stack = ArrayList<XmlElement>()
        var root: XmlElement? = null
        var elements = 0
        var p = le16(b, 2)
        try {
            while (p + 8 <= b.size) {
                val type = le16(b, p)
                val headerSize = le16(b, p + 2)
                val size = le32(b, p + 4).toInt()
                if (size < 8 || p + size > b.size) throw ApkFormatException("bad XML chunk")
                when (type) {
                    RES_STRING_POOL_TYPE -> pool = StringPool(b, p)
                    RES_XML_RESOURCE_MAP_TYPE -> resIds = LongArray((size - headerSize) / 4) { le32(b, p + headerSize + it * 4) }
                    START_ELEMENT -> {
                        val strings = pool ?: throw ApkFormatException("element before string pool")
                        if (++elements > MAX_ELEMENTS || stack.size >= MAX_DEPTH) throw ApkFormatException("manifest too large")
                        val ext = p + headerSize
                        val name = strings[le32(b, ext + 4)] ?: ""
                        val attrStart = le16(b, ext + 8)
                        val attrSize = le16(b, ext + 10)
                        val attrCount = le16(b, ext + 12)
                        val attrs = (0 until attrCount).map { i ->
                            val a = ext + attrStart + i * attrSize
                            val nameIndex = le32(b, a + 4)
                            val rawIndex = le32(b, a + 8)
                            val dataType = b[a + 15].toInt() and 0xFF
                            val data = le32(b, a + 16)
                            XmlAttribute(
                                name = strings[nameIndex] ?: "",
                                resId = if (nameIndex < resIds.size) resIds[nameIndex.toInt()] else 0L,
                                type = dataType,
                                data = data,
                                rawString = if (rawIndex != NO_INDEX) strings[rawIndex] else if (dataType == XmlAttribute.TYPE_STRING) strings[data] else null,
                            )
                        }
                        val element = XmlElement(name, attrs)
                        if (stack.isEmpty()) { if (root == null) root = element } else stack.last().children += element
                        stack += element
                    }
                    END_ELEMENT -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                p += size
            }
        } catch (_: IndexOutOfBoundsException) {
            throw ApkFormatException("truncated binary XML")
        }
        return root ?: throw ApkFormatException("empty manifest")
    }
}

/**
 * Looks up one string resource in resources.arsc (used for the app's label). Only the default
 * configuration is preferred; any configuration with a string value is accepted as a fallback.
 */
object ArscStrings {
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008

    fun resolveString(arsc: ByteArray, resId: Long): String? = try {
        lookup(arsc, resId)
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: ApkFormatException) {
        null
    }

    private fun lookup(b: ByteArray, resId: Long): String? {
        if (b.size < 12 || le16(b, 0) != RES_TABLE_TYPE) return null
        val packageId = (resId ushr 24).toInt()
        val typeId = ((resId ushr 16) and 0xFF).toInt()
        val entryId = (resId and 0xFFFF).toInt()
        var globalPool: StringPool? = null
        var p = le16(b, 2)
        var fallback: String? = null
        while (p + 8 <= b.size) {
            val type = le16(b, p)
            val size = le32(b, p + 4).toInt()
            if (size < 8 || p + size > b.size) return fallback
            when (type) {
                RES_STRING_POOL_TYPE -> if (globalPool == null) globalPool = StringPool(b, p)
                RES_TABLE_PACKAGE_TYPE -> if (le32(b, p + 8).toInt() == packageId) {
                    val pool = globalPool ?: return null
                    val headerSize = le16(b, p + 2)
                    var q = p + headerSize
                    while (q + 8 <= p + size) {
                        val ct = le16(b, q)
                        val cSize = le32(b, q + 4).toInt()
                        if (cSize < 8 || q + cSize > p + size) break
                        if (ct == RES_TABLE_TYPE_TYPE && (b[q + 8].toInt() and 0xFF) == typeId) {
                            val value = typeEntryString(b, q, entryId, pool)
                            if (value != null) {
                                val configSize = le32(b, q + 20).toInt()
                                val isDefault = (4 until configSize).all { b[q + 20 + it].toInt() == 0 }
                                if (isDefault) return value
                                if (fallback == null) fallback = value
                            }
                        }
                        q += cSize
                    }
                }
            }
            p += size
        }
        return fallback
    }

    private fun typeEntryString(b: ByteArray, chunk: Int, entryId: Int, pool: StringPool): String? {
        val headerSize = le16(b, chunk + 2)
        val flags = b[chunk + 9].toInt() and 0xFF
        val entryCount = le32(b, chunk + 12).toInt()
        val entriesStart = le32(b, chunk + 16).toInt()
        if (entryCount < 0 || entryCount > 65_536 || entriesStart < 0) return null
        val offsets = chunk + headerSize
        val offset: Long = when {
            flags and FLAG_SPARSE != 0 -> {
                // (u16 entry index, u16 offset / 4) pairs, sorted by index.
                (0 until entryCount).map { offsets + it * 4 }.firstOrNull { le16(b, it) == entryId }?.let { le16(b, it + 2).toLong() * 4 } ?: return null
            }
            entryId >= entryCount -> return null
            flags and FLAG_OFFSET16 != 0 -> le16(b, offsets + entryId * 2).let { if (it == 0xFFFF) return null else it.toLong() * 4 }
            else -> le32(b, offsets + entryId * 4).let { if (it == 0xFFFFFFFFL) return null else it }
        }
        val e = chunk + entriesStart + offset.toInt()
        val entryFlags = le16(b, e + 2)
        if (entryFlags and ENTRY_FLAG_COMPACT != 0) {
            val dataType = (entryFlags ushr 8) and 0xFF
            return if (dataType == XmlAttribute.TYPE_STRING) pool[le32(b, e + 4)] else null
        }
        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) return null
        val entrySize = le16(b, e)
        val valueAt = e + entrySize
        val dataType = b[valueAt + 3].toInt() and 0xFF
        return if (dataType == XmlAttribute.TYPE_STRING) pool[le32(b, valueAt + 4)] else null
    }
}
