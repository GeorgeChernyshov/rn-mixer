package com.example.polandandroidarms

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.polandandroidarms.MainActivity.Companion.TAG
import java.io.File


object Util {
    fun deleteCache(context: Context) {
        try {
            val dir = context.cacheDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            for (i in children.indices) {
                val success = deleteDir(File(dir, children[i]))
                if (!success) {
                    return false
                }
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        } else {
            return false
        }
    }

    fun convertFile(
        inputFile: File,
        outputFile: File,
        outputFileHandler: (File) -> Unit
    ) {
        val session = FFmpegKit.execute("-i $inputFile $outputFile")
        if (ReturnCode.isSuccess(session.returnCode)) {
            outputFileHandler.invoke(outputFile)
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
        } else {
            Log.d(
                TAG,
                String.format(
                    "Command failed with state %s and rc %s.%s",
                    session.state,
                    session.returnCode,
                    session.failStackTrace
                )
            )
        }
    }
}