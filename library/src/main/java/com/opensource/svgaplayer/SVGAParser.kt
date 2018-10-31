package com.opensource.svgaplayer

import android.app.Activity
import android.content.Context
import android.net.http.HttpResponseCache
import android.os.Handler
import android.util.Log
import com.opensource.svgaplayer.proto.MovieEntity

import org.json.JSONObject
import java.io.*

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Created by PonyCui_Home on 16/6/18.
 */

private var sharedLock: Int = 0

class SVGAParser(private val context: Context) {

    interface ParseCompletion {

        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()

    }

    open class FileDownloader {

        var noCache = false

        open fun resume(url: URL, complete: (inputStream: InputStream) -> Unit, failure: (e: Exception) -> Unit) {
            Thread {
                try {
                    if (HttpResponseCache.getInstalled() == null && !noCache) {
                        Log.e("SVGAParser", "SVGAParser can not handle cache before install HttpResponseCache. see https://github.com/yyued/SVGAPlayer-Android#cache")
                        Log.e("SVGAParser", "在配置 HttpResponseCache 前 SVGAParser 无法缓存. 查看 https://github.com/yyued/SVGAPlayer-Android#cache ")
                    }
                    (url.openConnection() as? HttpURLConnection)?.let {
                        it.connectTimeout = 20 * 1000
                        it.requestMethod = "GET"
                        it.connect()
                        it.inputStream.use { inputStream ->
                            ByteArrayOutputStream().use { outputStream ->
                                val buffer = ByteArray(4096)
                                var count: Int
                                while (true) {
                                    count = inputStream.read(buffer, 0, 4096)
                                    if (count == -1) {
                                        break
                                    }
                                    outputStream.write(buffer, 0, count)
                                }
                                ByteArrayInputStream(outputStream.toByteArray()).use {
                                    complete(it)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    failure(e)
                }
            }.start()
        }

    }

    var fileDownloader = FileDownloader()

    fun parse(assetsName: String, callback: ParseCompletion) {
        try {
            context.assets.open(assetsName)?.let {
                parse(it, cacheKey("file:///assets/$assetsName"), callback, true)
            }
        } catch (e: Exception) {}
    }

    fun parse(url: URL, callback: ParseCompletion) {
        if (cacheDir(cacheKey(url)).exists()) {
            parseWithCacheKey(cacheKey(url))?.let {
                Handler(context.mainLooper).post {
                    callback.onComplete(it)
                }
                return
            }
        }
        fileDownloader.resume(url, {
            val videoItem = parse(it, cacheKey(url)) ?: return@resume (Handler(context.mainLooper).post { callback.onError() } as? Unit ?: Unit)
            videoItem.prepare {
                Handler(context.mainLooper).post {
                    callback.onComplete(videoItem)
                }
            }
        }, {
            Handler(context.mainLooper).post {
                callback.onError()
            }
        })
    }

    fun parse(inputStream: InputStream, cacheKey: String, callback: ParseCompletion, closeInputStream: Boolean = false) {
        Thread {
            val videoItem = parse(inputStream, cacheKey)
            if (closeInputStream) {
                inputStream.close()
            }
            if (videoItem != null) {
                videoItem.prepare {
                    Handler(context.mainLooper).post {
                        callback.onComplete(videoItem)
                    }
                }
            } else {
                Handler(context.mainLooper).post {
                    callback.onError()
                }
            }
        }.start()
    }

    private fun parse(inputStream: InputStream, cacheKey: String): SVGAVideoEntity? {
        readAsBytes(inputStream)?.let { bytes ->
            if (bytes.size > 4 && bytes[0].toInt() == 80 && bytes[1].toInt() == 75 && bytes[2].toInt() == 3 && bytes[3].toInt() == 4) {
                synchronized(sharedLock) {
                    if (!cacheDir(cacheKey).exists()) {
                        try {
                            ByteArrayInputStream(bytes).use {
                                unzip(it, cacheKey)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    try {
                        val cacheDir = File(context.cacheDir.absolutePath + "/" + cacheKey + "/")
                        File(cacheDir, "movie.binary").takeIf { it.isFile }?.let { binaryFile ->
                            try {
                                FileInputStream(binaryFile).use {
                                    return SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), cacheDir)
                                }
                            } catch (e: Exception) {
                                cacheDir.delete()
                                binaryFile.delete()
                                throw e
                            }
                        }
                        File(cacheDir, "movie.spec").takeIf { it.isFile }?.let { jsonFile ->
                            try {
                                FileInputStream(jsonFile).use { fileInputStream ->
                                    ByteArrayOutputStream().use { byteArrayOutputStream ->
                                        val buffer = ByteArray(2048)
                                        while (true) {
                                            val size = fileInputStream.read(buffer, 0, buffer.size)
                                            if (size == -1) {
                                                break
                                            }
                                            byteArrayOutputStream.write(buffer, 0, size)
                                        }
                                        byteArrayOutputStream.toString().let {
                                            JSONObject(it).let {
                                                return SVGAVideoEntity(it, cacheDir)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                cacheDir.delete()
                                jsonFile.delete()
                                throw e
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            else {
                try {
                    inflate(bytes)?.let {
                        return SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), File(cacheKey))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    private fun parseWithCacheKey(cacheKey: String): SVGAVideoEntity? {
        synchronized(sharedLock) {
            try {
                val cacheDir = File(context.cacheDir.absolutePath + "/" + cacheKey + "/")
                File(cacheDir, "movie.binary").takeIf { it.isFile }?.let { binaryFile ->
                    try {
                        FileInputStream(binaryFile).use {
                            return SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), cacheDir)
                        }
                    } catch (e: Exception) {
                        cacheDir.delete()
                        binaryFile.delete()
                        throw e
                    }
                }
                File(cacheDir, "movie.spec").takeIf { it.isFile }?.let { jsonFile ->
                    try {
                        FileInputStream(jsonFile).use { fileInputStream ->
                            ByteArrayOutputStream().use { byteArrayOutputStream ->
                                val buffer = ByteArray(2048)
                                while (true) {
                                    val size = fileInputStream.read(buffer, 0, buffer.size)
                                    if (size == -1) {
                                        break
                                    }
                                    byteArrayOutputStream.write(buffer, 0, size)
                                }
                                byteArrayOutputStream.toString().let {
                                    JSONObject(it).let {
                                        return SVGAVideoEntity(it, cacheDir)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        cacheDir.delete()
                        jsonFile.delete()
                        throw e
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun cacheKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    private fun cacheKey(url: URL): String = cacheKey(url.toString())

    private fun cacheDir(cacheKey: String): File = File(context.cacheDir.absolutePath + "/" + cacheKey + "/")

    private fun readAsBytes(inputStream: InputStream): ByteArray? {
        return try {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            val byteArray = ByteArray(2048)
            while (true) {
                val count = inputStream.read(byteArray, 0, 2048)
                if (count <= 0) {
                    break
                }
                else {
                    byteArrayOutputStream.write(byteArray, 0, count)
                }
            }
            return byteArrayOutputStream.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun inflate(byteArray: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(byteArray, 0, byteArray.size)
            val inflatedBytes = ByteArray(2048)
            ByteArrayOutputStream().use { inflatedOutputStream ->
                while (true) {
                    val count = inflater.inflate(inflatedBytes, 0, 2048)
                    if (count <= 0) {
                        break
                    }
                    else {
                        inflatedOutputStream.write(inflatedBytes, 0, count)
                    }
                }
                inflater.end()
                return inflatedOutputStream.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun unzip(inputStream: InputStream, cacheKey: String) {
        val cacheDir = this.cacheDir(cacheKey)
        cacheDir.mkdirs()
        try {
            BufferedInputStream(inputStream).use {
                ZipInputStream(it).use { zipInputStream ->
                    while (true) {
                        val zipItem = zipInputStream.nextEntry ?: break
                        if (zipItem.name.contains("/")) {
                            continue
                        }
                        val file = File(cacheDir, zipItem.name)
                        FileOutputStream(file).use { fileOutputStream ->
                            val buff = ByteArray(2048)
                            while (true) {
                                val readBytes = zipInputStream.read(buff)
                                if (readBytes <= 0) {
                                    break
                                }
                                fileOutputStream.write(buff, 0, readBytes)
                            }
                        }
                        zipInputStream.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            cacheDir.delete()
        }
    }
}
