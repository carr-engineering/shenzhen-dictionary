package com.szdict.app.platform

import android.content.Context
import com.szdict.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SQLite needs real files, so the datasets and SQLite packs are extracted once per app version from
 * the APK (where they are deflate-compressed) into no-backup storage. Audio stays in the APK.
 */
object DatasetInstaller {
    private const val ASSET_ROOT = "data"

    suspend fun ensureInstalled(context: Context): File = withContext(Dispatchers.IO) {
        val stamp = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}-" +
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val root = File(context.noBackupFilesDir, "dataset")
        val marker = File(root, ".installed")
        if (marker.exists() && marker.readText() == stamp) return@withContext root
        root.deleteRecursively()
        root.mkdirs()
        copyDir(context, ASSET_ROOT, root)
        marker.writeText(stamp)
        root
    }

    private fun copyDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        for (child in children) {
            val childAsset = "$assetPath/$child"
            if (childAsset == "$ASSET_ROOT/packs/audio") continue
            val isFile = child.endsWith(".db") || child.endsWith(".json") || child.endsWith(".bin")
            if (isFile) {
                target.mkdirs()
                context.assets.open(childAsset).use { input ->
                    File(target, child).outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            } else {
                copyDir(context, childAsset, File(target, child))
            }
        }
    }
}
