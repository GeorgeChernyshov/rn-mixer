package com.example.polandandroidarms

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile


class PlayableInputStream(
    private val getAvailableBytes: GetAvailableBytes,
    private val randomAccessFile: RandomAccessFile
) : InputStream() {
    /*keeping track of bytesRead so that we have to call getAvailableBytes() whenever bytesRead is <= 0 */
    private var numberOfBytesCanBeRead: Long = 0

    override fun available(): Int {
        if (numberOfBytesCanBeRead > 0)
            return numberOfBytesCanBeRead.toInt()

        /*calculate number of bytes that can be read from myInputStream without blocking it */
        numberOfBytesCanBeRead = getAvailableBytes.availableBytes - randomAccessFile.filePointer

        /*if bytes are not available return -1 */
        if (numberOfBytesCanBeRead < 0)
            return -1

        /*keep running loop until we get a non-zero value of numberOfBytesCanBeRead*/
        while (numberOfBytesCanBeRead == 0L) {
            try {
                /*sleep for 4 seconds*/
                Thread.sleep(400L)
            } catch (e: InterruptedException) {
                e.printStackTrace()
                return -1
            }

            numberOfBytesCanBeRead = getAvailableBytes.availableBytes - randomAccessFile.filePointer
        }

        return numberOfBytesCanBeRead.toInt()
    }

    override fun close() {
        randomAccessFile.close()
        super.close()
    }

    override fun read(): Int {
        throw IOException("Illegal To Enter Here")
    }

    override fun read(buffer: ByteArray?): Int {
        throw IOException("Illegal To Enter Here")
    }

    override fun read(buffer: ByteArray?, byteOffset: Int, byteCount: Int): Int {
        val availableBytes = available()

        if (availableBytes < 0)
            return -1

        val cnt = availableBytes.coerceAtMost(byteCount)

        /*read from randomAccessFile*/
        val read = randomAccessFile.read(buffer, byteOffset, cnt)
        numberOfBytesCanBeRead -= read.toLong()
        return read
    }

    /*method to skip bytes in a the myInputStream*/
    override fun skip(byteCount: Long): Long {
        if (byteCount < 0)
            return -1

        val availableBytes = available().toLong()

        val skipped = availableBytes.coerceAtMost(byteCount)

        /*return number of bytes skipped*/
        val actualSkipped = randomAccessFile.skipBytes(skipped.toInt())
        numberOfBytesCanBeRead -= actualSkipped.toLong()
        return actualSkipped.toLong()
    }

    interface GetAvailableBytes {
        val availableBytes: Long
    }
}