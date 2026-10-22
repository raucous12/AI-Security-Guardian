package lian.core.platform.untrusted

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process side of [UntrustedContentService]. Opens the user's file read-only, hands the
 * descriptor to the isolated process, waits for a bounded text reply, and unbinds. Blocking: call
 * from a background thread.
 */
class UntrustedClient(private val context: Context) {

    sealed interface Outcome {
        data class Ok(val text: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun parseApk(uri: Uri): Outcome = withSeekableFile(uri, MAX_APK_BYTES) { request(UntrustedContentService.MSG_PARSE_APK, it) }

    /** Already-open descriptors (on-device tests). The caller keeps ownership of [pfd]. */
    fun parseApk(pfd: ParcelFileDescriptor): Outcome = request(UntrustedContentService.MSG_PARSE_APK, pfd)

    /** Asks the checker process to describe itself ("isolated=true uid=…"), for the Privacy proof screen. */
    fun selfTest(): Outcome {
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[1].close()
        return pipe[0].use { request(UntrustedContentService.MSG_SELF_TEST, it) }
    }

    /**
     * Only `content://` URIs from other apps are accepted (never LIAN's own: no confused deputy).
     * Pipes and other non-seekable sources are first copied, under a random name the sender can't
     * influence (Dirty Stream), into no-backup storage, and the copy is deleted afterwards.
     */
    private fun withSeekableFile(uri: Uri, maxBytes: Long, body: (ParcelFileDescriptor) -> Outcome): Outcome {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority?.startsWith(context.packageName) == true) return Outcome.Failed("unsupported file location")
        var copy: File? = null
        try {
            val original = context.contentResolver.openFileDescriptor(uri, "r") ?: return Outcome.Failed("couldn't open the file")
            val pfd = if (original.statSize >= 0) {
                if (original.statSize > maxBytes) { original.close(); return Outcome.Failed("file too large") }
                original
            } else {
                copy = copyToInbox(original, maxBytes) ?: return Outcome.Failed("file too large")
                ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return pfd.use(body)
        } catch (_: IOException) {
            return Outcome.Failed("couldn't read the file")
        } catch (_: SecurityException) {
            return Outcome.Failed("no permission to read the file")
        } finally {
            copy?.delete()
        }
    }

    private fun copyToInbox(source: ParcelFileDescriptor, maxBytes: Long): File? {
        val inbox = File(context.noBackupFilesDir, "inbox").apply { mkdirs() }
        val name = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val target = File(inbox, "$name.bin")
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) { out.close(); target.delete(); return null }
                    out.write(buffer, 0, n)
                }
            }
        }
        return target
    }

    private fun request(what: Int, pfd: ParcelFileDescriptor): Outcome {
        val binder = AtomicReference<IBinder?>()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { connected.countDown() }
            override fun onBindingDied(name: ComponentName) { connected.countDown() }
        }
        val intent = Intent(context, UntrustedContentService::class.java)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return Outcome.Failed("checker unavailable")
        try {
            if (!connected.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) return Outcome.Failed("checker didn't start")
            val service = binder.get() ?: return Outcome.Failed("checker stopped")
            val reply = AtomicReference<Bundle?>()
            val done = CountDownLatch(1)
            val replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { reply.set(msg.data); done.countDown() }
            })
            val message = Message.obtain(null, what).apply {
                data = Bundle().apply { putParcelable(UntrustedContentService.KEY_FD, pfd) }
                this.replyTo = replyTo
            }
            try {
                Messenger(service).send(message)
            } catch (_: RemoteException) {
                return Outcome.Failed("checker stopped")
            }
            if (!done.await(WORK_TIMEOUT_S, TimeUnit.SECONDS)) return Outcome.Failed("took too long")
            val data = reply.get() ?: return Outcome.Failed("no answer")
            data.getString(UntrustedContentService.KEY_RESULT)?.let { return Outcome.Ok(it) }
            return Outcome.Failed(data.getString(UntrustedContentService.KEY_ERROR) ?: "unreadable file")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Failed("cancelled")
        } finally {
            context.unbindService(connection)
        }
    }

    companion object {
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        private const val BIND_TIMEOUT_S = 10L
        private const val WORK_TIMEOUT_S = 30L

        /** Leftover copies from a crash are swept at start-up. */
        fun sweepInbox(context: Context) {
            File(context.noBackupFilesDir, "inbox").listFiles()?.forEach { it.delete() }
        }
    }
}
