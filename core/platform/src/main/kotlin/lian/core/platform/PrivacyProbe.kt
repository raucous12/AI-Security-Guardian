package lian.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket // privacy-gate: allow net.socket (the probe proves the kernel refuses IP sockets)
import java.security.MessageDigest

/**
 * Evidence for the Privacy proof screen, read from the live system rather than asserted:
 * what the app requests, whether the kernel lets it open a network socket, where it was installed
 * from, and whether its signing certificate is one a genuine build carries (Phase 3.8.4).
 */
class PrivacyProbe(private val context: Context, private val expectedSigners: Set<String>) {

    enum class Genuineness { GENUINE, NOT_GENUINE, NOT_CONFIGURED }

    sealed interface SocketTest {
        /**
         * Socket creation was refused by the kernel: EACCES on older releases, EPERM where the
         * INTERNET permission is enforced by the eBPF socket-create hook.
         */
        data class BlockedByAndroid(val errno: String) : SocketTest

        /** A socket could be created (connection refused or similar). Network access is NOT blocked. */
        data class Allowed(val detail: String) : SocketTest
    }

    data class Report(
        val requestedPermissions: List<String>,
        val internetRequested: Boolean,
        val socket: SocketTest,
        val installer: String?,
        val signerSha256: List<String>,
        val genuineness: Genuineness,
    )

    fun run(): Report {
        val info = packageInfo()
        val permissions = info.requestedPermissions.orEmpty().sorted()
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }
        val genuineness = when {
            expectedSigners.isEmpty() -> Genuineness.NOT_CONFIGURED
            signers.any { it in expectedSigners } -> Genuineness.GENUINE
            else -> Genuineness.NOT_GENUINE
        }
        return Report(
            requestedPermissions = permissions,
            internetRequested = Manifest.permission.INTERNET in permissions,
            socket = socketTest(),
            installer = installer(),
            signerSha256 = signers,
            genuineness = genuineness,
        )
    }

    private fun socketTest(): SocketTest = try {
        // Loopback discard port: harmless even if it ever succeeded. Literal address, so no DNS lookup.
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 9), 1_000) } // privacy-gate: allow net.socket (probe)
        SocketTest.Allowed("connected")
    } catch (e: IOException) {
        val message = e.message.orEmpty()
        when {
            "EACCES" in message || "Permission denied" in message -> SocketTest.BlockedByAndroid("EACCES")
            "EPERM" in message || "Operation not permitted" in message -> SocketTest.BlockedByAndroid("EPERM")
            else -> SocketTest.Allowed("${e.javaClass.simpleName}: $message")
        }
    } catch (_: SecurityException) {
        SocketTest.BlockedByAndroid("SecurityException")
    }

    private fun installer(): String? = try {
        context.packageManager.getInstallSourceInfo(context.packageName).initiatingPackageName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun packageInfo(): PackageInfo {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) { for (b in digest) append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF]) }
    }
}
