package dev.busung.s25uroot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PayloadRepository private constructor(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = context.cacheDir
    
    companion object {
        @Volatile
        private var instance: PayloadRepository? = null
        
        fun getInstance(context: Context): PayloadRepository {
            return instance ?: synchronized(this) {
                instance ?: PayloadRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        private const val COMMIT_API_URL =
            "https://api.github.com/repos/franchoran98/Root-My-Galaxy-Payloads-S25_FE/git/ref/heads/main"
        private const val RAW_REPOSITORY =
            "https://raw.githubusercontent.com/franchoran98/Root-My-Galaxy-Payloads-S25_FE"
        private const val MUTABLE_RAW_PREFIX = "$RAW_REPOSITORY/main/"
    }
    
    suspend fun getLatestCommit(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(COMMIT_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch commit: ${response.code}")
                    )
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(
                    IOException("Empty response")
                )
                
                val refResponse = json.decodeFromString(GitRefResponse.serializer(), body)
                Result.success(refResponse.`object`.sha)
            }
        } catch (e: Exception) {
            Log.e("PayloadRepository", "Error fetching commit", e)
            Result.failure(e)
        }
    }
    
    suspend fun downloadPayload(
        deviceCodename: String,
        buildId: String,
        progressCallback: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val payloadPath = "src/targets/$deviceCodename/$buildId/cve-2026-43499-app.so"
            val url = "$MUTABLE_RAW_PREFIX$payloadPath"
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to download payload: ${response.code}")
                    )
                }
                
                val file = File(cacheDir, "payload-$deviceCodename-$buildId.so")
                
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        val totalBytes = response.body?.contentLength() ?: -1
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            if (totalBytes > 0) {
                                val progress = ((totalRead * 100) / totalBytes).toInt()
                                progressCallback(progress)
                            }
                        }
                    }
                }
                
                Result.success(file)
            }
        } catch (e: Exception) {
            Log.e("PayloadRepository", "Error downloading payload", e)
            Result.failure(e)
        }
    }
    
    @Serializable
    private data class GitRefResponse(
        val ref: String,
        val `object`: GitObject
    )
    
    @Serializable
    private data class GitObject(
        val sha: String,
        val type: String,
        val url: String
    )
}
