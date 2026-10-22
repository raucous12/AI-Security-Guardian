package lian.core.engine.apk

import lian.core.engine.app.Capability

/** What an APK file declares, read without installing it. Bounded plain values, so it can cross a process boundary. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val label: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val hasLauncher: Boolean,
    val declared: Set<Capability>,
    val componentCount: Int,
    val nativeAbis: List<String>,
    val fileSize: Long,
    val signing: SigningFacts,
) {
    /** Line-based encoding for the isolated-process boundary: `key<TAB>value`, lists separated by U+001F. */
    fun encode(): String = buildString {
        fun put(k: String, v: String?) { if (v != null) append(k).append('\t').append(escape(v)).append('\n') }
        put("package", packageName)
        put("versionCode", versionCode?.toString())
        put("versionName", versionName)
        put("label", label)
        put("minSdk", minSdk?.toString())
        put("targetSdk", targetSdk?.toString())
        put("permissions", permissions.joinToString(SEP))
        put("debuggable", debuggable.toString())
        put("testOnly", testOnly.toString())
        put("launcher", hasLauncher.toString())
        put("declared", declared.sorted().joinToString(SEP) { it.name })
        put("components", componentCount.toString())
        put("abis", nativeAbis.joinToString(SEP))
        put("size", fileSize.toString())
        put("schemes", signing.schemes.sorted().joinToString(SEP))
        put("verified", signing.verified?.toString())
        put("problem", signing.problem)
        put("certs", signing.certificates.joinToString(SEP) { "${it.sha256}|${it.sha1}|${it.subject}" })
    }

    companion object {
        private const val SEP = "\u001F"
        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] })
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }

        fun decode(text: String): ApkFacts {
            val m = text.lineSequence().filter { '\t' in it }.associate { it.substringBefore('\t') to unescape(it.substringAfter('\t')) }
            fun list(k: String) = m[k].orEmpty().split(SEP).filter { it.isNotEmpty() }
            return ApkFacts(
                packageName = m["package"] ?: throw ApkFormatException("no package name"),
                versionCode = m["versionCode"]?.toLongOrNull(),
                versionName = m["versionName"],
                label = m["label"],
                minSdk = m["minSdk"]?.toIntOrNull(),
                targetSdk = m["targetSdk"]?.toIntOrNull(),
                permissions = list("permissions"),
                debuggable = m["debuggable"] == "true",
                testOnly = m["testOnly"] == "true",
                hasLauncher = m["launcher"] == "true",
                declared = list("declared").mapNotNull { n -> Capability.entries.firstOrNull { it.name == n } }.toSet(),
                componentCount = m["components"]?.toIntOrNull() ?: 0,
                nativeAbis = list("abis"),
                fileSize = m["size"]?.toLongOrNull() ?: 0,
                signing = SigningFacts(
                    schemes = list("schemes").mapNotNull { it.toIntOrNull() }.toSet(),
                    verified = m["verified"]?.toBooleanStrictOrNull(),
                    problem = m["problem"],
                    certificates = list("certs").mapNotNull { c ->
                        val parts = c.split('|', limit = 3)
                        if (parts.size == 3) CertificateId(parts[0], parts[1], parts[2]) else null
                    },
                ),
            )
        }
    }
}

/**
 * Reads an APK without Android's PackageManager: ZIP directory, binary manifest, label from
 * resources.arsc, and v2/v3 signatures. Designed to run in an isolated process on hostile input:
 * every size is capped and every malformed structure ends in [ApkFormatException].
 */
object ApkParser {
    private const val MAX_MANIFEST = 4 * 1024 * 1024
    private const val MAX_ARSC = 48 * 1024 * 1024
    private const val MAX_TEXT = 256
    private const val MAX_PERMISSIONS = 500

    // android: attribute resource ids (stable framework ids).
    private const val NAME = 0x01010003L
    private const val LABEL = 0x01010001L
    private const val PERMISSION = 0x01010006L
    private const val DEBUGGABLE = 0x0101000fL
    private const val MIN_SDK = 0x0101020cL
    private const val TARGET_SDK = 0x01010270L
    private const val VERSION_CODE = 0x0101021bL
    private const val VERSION_NAME = 0x0101021cL
    private const val TEST_ONLY = 0x01010272L

    private val CAPABILITY_BY_PERMISSION = mapOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to Capability.ACCESSIBILITY,
        "android.permission.BIND_DEVICE_ADMIN" to Capability.DEVICE_ADMIN,
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to Capability.NOTIFICATION_LISTENER,
        "android.permission.BIND_INPUT_METHOD" to Capability.KEYBOARD,
        "android.permission.BIND_VPN_SERVICE" to Capability.VPN,
    )

    fun parse(source: ByteSource): ApkFacts {
        val zip = ZipArchive(source)
        val manifestEntry = zip.find("AndroidManifest.xml") ?: throw ApkFormatException("no AndroidManifest.xml: not an APK")
        val manifest = BinaryXml.parse(zip.read(manifestEntry, MAX_MANIFEST))
        if (manifest.name != "manifest") throw ApkFormatException("unexpected manifest root <${manifest.name}>")
        val packageName = manifest.attributes.firstOrNull { it.name == "package" }?.string?.take(MAX_TEXT)
            ?: throw ApkFormatException("manifest has no package name")

        val usesSdk = manifest.children("uses-sdk").firstOrNull()
        val application = manifest.children("application").firstOrNull()
        val permissions = (manifest.children("uses-permission") + manifest.children("uses-permission-sdk-23"))
            .mapNotNull { it.string(NAME, "name")?.take(MAX_TEXT) }.distinct().sorted().take(MAX_PERMISSIONS)

        val components = application?.children.orEmpty().filter { it.name in COMPONENT_TAGS }
        val declared = components.mapNotNull { c -> c.string(PERMISSION, "permission")?.let(CAPABILITY_BY_PERMISSION::get) }.toSet()
        val launcher = components.filter { it.name == "activity" || it.name == "activity-alias" }.any { a ->
            a.children("intent-filter").any { f ->
                val actions = f.children("action").mapNotNull { it.string(NAME, "name") }
                val categories = f.children("category").mapNotNull { it.string(NAME, "name") }
                "android.intent.action.MAIN" in actions &&
                    ("android.intent.category.LAUNCHER" in categories || "android.intent.category.LEANBACK_LAUNCHER" in categories)
            }
        }

        val labelAttr = application?.attr(LABEL, "label")
        val label = labelAttr?.reference?.let { ref ->
            zip.find("resources.arsc")?.takeIf { it.uncompressedSize <= MAX_ARSC }?.let { ArscStrings.resolveString(zip.read(it, MAX_ARSC), ref) }
        } ?: labelAttr?.takeIf { it.type == XmlAttribute.TYPE_STRING }?.string

        return ApkFacts(
            packageName = packageName,
            versionCode = manifest.int(VERSION_CODE, "versionCode"),
            versionName = manifest.string(VERSION_NAME, "versionName")?.take(MAX_TEXT),
            label = label?.trim()?.take(MAX_TEXT),
            minSdk = usesSdk?.int(MIN_SDK, "minSdkVersion")?.toInt(),
            targetSdk = usesSdk?.int(TARGET_SDK, "targetSdkVersion")?.toInt() ?: usesSdk?.int(MIN_SDK, "minSdkVersion")?.toInt(),
            permissions = permissions,
            debuggable = application?.bool(DEBUGGABLE, "debuggable") == true,
            testOnly = application?.bool(TEST_ONLY, "testOnly") == true,
            hasLauncher = launcher,
            declared = declared,
            componentCount = components.size,
            nativeAbis = zip.entries.mapNotNull { e -> e.name.takeIf { it.startsWith("lib/") && it.endsWith(".so") }?.split('/')?.getOrNull(1) }.distinct().sorted(),
            fileSize = source.size,
            signing = ApkSignatureVerifier.verify(source, zip),
        )
    }

    private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
}
