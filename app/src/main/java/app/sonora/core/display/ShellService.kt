package app.sonora.core.display

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Выполняет shell-команды в процессе Shizuku (shell/root UID).
 * Запускается только через Shizuku.bindUserService, наружу не экспортируется.
 * Binder-протокол написан вручную, без AIDL-генератора: проект лежит в пути
 * с кириллицей, а aidl.exe на Windows в таком пути падает.
 */
class ShellService : Service() {

    private val binder = ShellBinder()

    override fun onBind(intent: Intent): IBinder = binder

    private class ShellBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_EXEC) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val command = data.readString().orEmpty()
            val result = runCatching {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                "$exit\n$output"
            }.getOrElse { "1\n${it.message}" }
            reply?.writeNoException()
            reply?.writeString(result)
            return true
        }
    }

    companion object {
        private const val DESCRIPTOR = "app.sonora.core.display.IShellService"
        private const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION

        /** Выполнить команду через binder, полученный от Shizuku. */
        fun exec(binder: IBinder, command: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                binder.transact(TRANSACTION_EXEC, data, reply, 0)
                reply.readException()
                reply.readString().orEmpty()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}