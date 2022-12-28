package tk.zwander.rootactivitylauncher.util.launch

import android.app.AppOpsManager
import android.app.IActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import eu.chainfire.libsuperuser.Shell
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import tk.zwander.rootactivitylauncher.util.hasShizukuPermission
import tk.zwander.rootactivitylauncher.util.requestShizukuPermission
import java.util.concurrent.TimeUnit

interface LaunchStrategy {
    suspend fun Context.canRun(): Boolean = true
    suspend fun Context.tryLaunch(args: LaunchArgs): Throwable?
}

interface CommandLaunchStrategy : LaunchStrategy {
    fun makeCommand(args: LaunchArgs): String
}

interface ShizukuLaunchStrategy : LaunchStrategy {
    override suspend fun Context.canRun(): Boolean {
        return Shizuku.pingBinder() &&
                (hasShizukuPermission || requestShizukuPermission())
    }

    suspend fun Context.callLaunch(intent: Intent)
}

interface ShizukuActivityLaunchStrategy : ShizukuLaunchStrategy {
    override suspend fun Context.callLaunch(intent: Intent) {
        val iam = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE))
        )

        iam.startActivity(
            null, "com.android.shell", intent,
            null, null, null, 0, 0,
            null, null
        )
    }
}

interface ShizukuReceiverLaunchStrategy : ShizukuLaunchStrategy {
    override suspend fun Context.callLaunch(intent: Intent) {
        val iam = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(
            SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))

        iam.broadcastIntent(
            null, intent, null, null, 0, null,
            null, null, AppOpsManager.OP_NONE, null, false, false,
            0
        )
    }
}

interface ShizukuServiceLaunchStrategy : ShizukuLaunchStrategy {
    override suspend fun Context.callLaunch(intent: Intent) {
        val iam = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(
            SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))

        iam.startService(
            null, intent, null, false, "com.android.shell",
            null, UserHandle.USER_CURRENT
        )
    }
}

interface ShizukuShellLaunchStrategy : ShizukuLaunchStrategy, CommandLaunchStrategy {
    override suspend fun Context.tryLaunch(args: LaunchArgs): Throwable? {
        return try {
            val command = StringBuilder(makeCommand(args))

            args.addToCommand(command)

            Shizuku.newProcess(arrayOf("sh", "-c", command.toString()), null, null).run {
                waitForTimeout(1000, TimeUnit.MILLISECONDS)

                Log.e("RootActivityLauncher", "Shizuku Command Output\n${inputStream.bufferedReader().use { it.readText() }}")
                Log.e("RootActivityLauncher", "Shizuku Error Output\n${errorStream.bufferedReader().use { it.readText() }}")

                if (exitValue() == 0) {
                    null
                } else {
                    Exception(errorStream.bufferedReader().use { it.readText() })
                }
            }
        } catch (e: Exception) {
            Log.e("RootActivityLauncher", "Failure to launch through Shizuku process.", e)
            e
        }
    }

    override suspend fun Context.callLaunch(intent: Intent) {
        throw IllegalAccessException("Not supported!")
    }
}

interface RootLaunchStrategy : CommandLaunchStrategy {
    override suspend fun Context.canRun(): Boolean {
        return Shell.SU.available()
    }

    override suspend fun Context.tryLaunch(args: LaunchArgs): Throwable? {
        val command = StringBuilder(makeCommand(args))
        val errorOutput = mutableListOf<String>()

        args.addToCommand(command)

        val result = Shell.Pool.SU.run(command.toString(), null, errorOutput, false)

        return if (result == 0) null else Exception(errorOutput.joinToString("\n"))
    }
}

interface IterativeLaunchStrategy : LaunchStrategy {
    fun extraFlags(): Int? {
        return null
    }

    suspend fun Context.performLaunch(args: LaunchArgs, intent: Intent)

    override suspend fun Context.tryLaunch(args: LaunchArgs): Throwable? {
        var latestError: Throwable? = null

        args.filters.forEach { filter ->
            try {
                val intent = Intent(args.intent)

                extraFlags()?.let { flags ->
                    intent.addFlags(flags)
                }

                intent.categories?.clear()
                intent.action = if (filter.countActions() > 0) filter.getAction(0) else Intent.ACTION_MAIN
                intent.data = if (filter.countDataSchemes() > 0) Uri.parse("${filter.getDataScheme(0)}://yes") else null
                filter.categoriesIterator().forEach { intent.addCategory(it) }

                performLaunch(args, intent)
                return null
            } catch (e: Throwable) {
                Log.e("RootActivityLauncher", "Error with alternative filter", e)
                latestError = e
            }
        }

        return latestError
    }
}

private fun LaunchArgs.addToCommand(command: StringBuilder) {
    command.append(" -a ${intent.action}")

    if (extras.isNotEmpty()) {
        extras.forEach {
            command.append(" --${it.safeType.shellArgName} \"${it.key}\" \"${it.value}\"")
        }
    }

    if (intent.categories?.isNotEmpty() == true) {
        intent.categories.forEach {
            command.append(" -c \"${it}\"")
        }
    }
}
