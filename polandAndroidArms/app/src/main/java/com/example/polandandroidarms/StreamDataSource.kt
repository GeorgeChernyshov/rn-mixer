package com.example.polandandroidarms

import android.net.Uri
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

@OptIn(UnstableApi::class)
class StreamDataSource(
    private val getAvailableBytes: PlayableInputStream.GetAvailableBytes
) : BaseDataSource(false) {

    private var uriString: String? = null
    private var inputStream: PlayableInputStream? = null
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false

    override fun open(dataspec: DataSpec): Long {
        try {
            uriString = dataspec.uri.toString()

            val file = File(Environment.getExternalStorageDirectory(), uriString)

            val randomAccessFile = RandomAccessFile(file, "r")
            inputStream = PlayableInputStream(getAvailableBytes, randomAccessFile)

            val skipped = inputStream?.skip(dataspec.position) ?: 0
            if (skipped < dataspec.position) {
                // assetManager.open() returns an AssetInputStream, whose skip() implementation only skips
                // fewer bytes than requested if the skip is beyond the end of the asset's data.
                throw EOFException()
            }
            if (dataspec.length.toInt() != -1) {
                bytesRemaining = dataspec.length - skipped
            } else {
                /*if dataspec.length==-1 then number of bytesRemaining=number of bytes file contains*/
                bytesRemaining = file.length()

                if (bytesRemaining.toInt() == Int.MAX_VALUE) {
                    // assetManager.open() returns an AssetInputStream, whose available() implementation
                    // returns Integer.MAX_VALUE if the remaining length is greater than (or equal to)
                    // Integer.MAX_VALUE. We don't know the true length in this case, so treat as unbounded.
                    bytesRemaining = -1
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        opened = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (bytesRemaining == 0L) {
            return -1
        } else {
            var bytesRead = 0
            try {
                val bytesToRead = if (bytesRemaining == -1L)
                    readLength
                else min(bytesRemaining.toDouble(), readLength.toDouble()).toInt()

                bytesRead = inputStream?.read(buffer, offset, bytesToRead) ?: 0
            }
            catch (_: IOException) {}

            if (bytesRead > 0 && bytesRemaining != -1L)
                bytesRemaining -= bytesRead.toLong()

            return bytesRead
        }
    }

    override fun getUri(): Uri? = Uri.parse(uriString)

    override fun close() {
        uriString = null
        inputStream?.also {
            try { it.close() }
            catch (_: IOException) { }
            finally {
                inputStream = null
                opened = false
            }
        }
    }
}