package lian.core.platform.untrusted

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import lian.core.engine.apk.ApkFormatException
import lian.core.engine.apk.ApkParser
import lian.core.engine.apk.ByteSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Parses hostile files (APKs) in an isolated process (Phase 3.5): `isolatedProcess="true"`
 * gives it its own UID with no permissions, no access to LIAN's files and no network. It receives
 * an already-open read-only file descriptor and returns only bounded plain text.
 */
class UntrustedContentService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("untrusted").apply { start() }
        messenger = Messenger(IncomingHandler(worker.looper, applicationInfo.dataDir))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private class IncomingHandler(looper: Looper, private val appDataDir: String) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            val pfd = msg.data?.let { b ->
                b.classLoader = ParcelFileDescriptor::class.java.classLoader
                if (Build.VERSION.SDK_INT >= 33) {
                    b.getParcelable(KEY_FD, ParcelFileDescriptor::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    b.getParcelable<ParcelFileDescriptor>(KEY_FD)
                }
            }
            val result = Bundle()
            try {
                if (pfd == null) {
                    result.putString(KEY_ERROR, "no file")
                } else pfd.use {
                    when (msg.what) {
                        MSG_PARSE_APK -> result.putString(KEY_RESULT, ApkParser.parse(FdSource(it)).encode())
                        MSG_SELF_TEST -> result.putString(KEY_RESULT, "isolated=${Process.isIsolated()} uid=${Process.myUid()} canReadAppFiles=${canReadAppFiles()}")
                        else -> result.putString(KEY_ERROR, "unknown request")
                    }
                }
            } catch (e: ApkFormatException) {
                result.putString(KEY_ERROR, e.message ?: "unreadable file")
            } catch (_: IOException) {
                result.putString(KEY_ERROR, "couldn't read the file")
            } catch (_: OutOfMemoryError) {
                result.putString(KEY_ERROR, "file too large")
            } catch (_: RuntimeException) {
                // Hostile input must never crash with details; the main process only learns "unreadable".
                result.putString(KEY_ERROR, "unreadable file")
            }
            try {
                replyTo.send(Message.obtain(null, msg.what).apply { data = result })
            } catch (_: RemoteException) {
                // The caller went away (timeout); nothing to do.
            }
        }

        /** True would mean the sandbox leaks: an isolated process must not be able to list LIAN's private data directory. */
        private fun canReadAppFiles(): Boolean = try {
            java.io.File(appDataDir).listFiles() != null
        } catch (_: SecurityException) {
            false
        }
    }

    /** Random access over the passed descriptor via positional reads (no shared file offset). */
    private class FdSource(pfd: ParcelFileDescriptor) : ByteSource {
        private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel
        override val size: Long = channel.size()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (position < 0 || length < 0 || position + length > size) throw ApkFormatException("read past end of file")
            val bb = ByteBuffer.wrap(buffer, offset, length)
            var pos = position
            while (bb.hasRemaining()) {
                val n = channel.read(bb, pos)
                if (n < 0) throw ApkFormatException("unexpected end of file")
                pos += n
            }
        }
    }

    companion object {
        const val MSG_PARSE_APK = 1
        const val MSG_SELF_TEST = 3
        const val KEY_FD = "fd"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
    }
}
