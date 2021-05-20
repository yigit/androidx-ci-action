/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.androidx.ci.github

import com.squareup.moshi.Moshi
import dev.androidx.ci.config.Config
import dev.androidx.ci.github.dto.ArtifactsResponse
import dev.androidx.ci.github.dto.IssueComment
import dev.androidx.ci.github.dto.IssueLabel
import dev.androidx.ci.github.dto.RunInfo
import dev.zacsweers.moshix.reflect.MetadataKotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.jvm.Throws

/**
 * Class to handle communications with the Github API
 */
interface GithubApi {
    /**
     * Returns the artifacts in the given run
     */
    @GET("actions/runs/{runId}/artifacts")
    suspend fun artifacts(@Path("runId") runId: String): ArtifactsResponse

    /**
     * Returns the contents of an artifact. Use [zipArchiveStream] to parse the zip file.
     */
    @GET
    suspend fun zipArchive(@Url path: String): ResponseBody

    @GET("actions/runs/{runId}")
    suspend fun runInfo(@Path("runId") runId: String): RunInfo

    @POST("issues/{issueNumber}/labels")
    suspend fun addLabels(
        @Path("issueNumber") issueNumber: String,
        @Body labels:List<String>
    ): List<IssueLabel>

    @DELETE("issues/{issueNumber}/labels/{name}")
    suspend fun deleteLabel(
        @Path("issueNumber") issueNumber: String,
        @Path("name") label: String
    )

    @POST("issues/{issueNumber}/comments")
    suspend fun comment(
        @Path("issueNumber") issueNumber: String,
        @Body comment: IssueComment
    ): IssueComment


    companion object {
        fun build(
            config: Config.Github
        ): GithubApi {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY
            val client = OkHttpClient.Builder().apply {
                this.addInterceptor {
                    it.proceed(
                        it.request().newBuilder()
                            .addHeader("Accept", "application/vnd.github.v3+json")
                            .addHeader("Authorization", "token ${config.token}")
                            .build()
                    )
                }
            }.callTimeout(10, TimeUnit.MINUTES).addInterceptor(logging)
                .build()
            val moshi = Moshi.Builder()
                .add(MetadataKotlinJsonAdapterFactory())
                .build()
            return Retrofit.Builder()
                .client(client)
                .baseUrl("${config.endPoint}/repos/${config.owner}/${config.repo}/")
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(GithubApi::class.java)
        }
    }
}

/**
 * Opens a stream for the given path and returns a sequence of Zip entries that can be skipped or
 * read.
 */
suspend fun GithubApi.zipArchiveStream(path: String): Sequence<ZipEntryScope> {
    return zipArchive(path).use {
        val zipInputStream = ZipInputStream(it.byteStream().buffered())
        sequence {
            do {
                val next = zipInputStream.nextEntry
                if (next != null) {
                    ZipEntryScopeImpl(zipInputStream, next).use {
                        yield(it)
                    }
                }
            } while (next != null)
        }
    }
}

private class ZipEntryScopeImpl(
    val stream: ZipInputStream,
    override val entry: ZipEntry
) : ZipEntryScope, AutoCloseable {
    private var usable = true
    override val bytes by lazy {
        check(usable) {
            "Must access entries in the sequence"
        }
        stream.readBytes()
    }
    override fun close() {
        usable = false
    }
}

suspend fun GithubApi.tryDeletingLabel(
    issueNumber: String,
    label: String
) = try {
    deleteLabel(
        issueNumber = issueNumber,
        label = label
    )
} catch (httpException: HttpException) {
    if (httpException.code() == 404) {
        // ignore, it doesn't have the label anyways
    } else {
        throw httpException
    }
}

/**
 * The scope for reading a single zip entry.
 * [bytes] can only be accessed while reading the entry in the stream such that trying to access
 * it afterwards will throw an [IllegalStateException]
 */
interface ZipEntryScope {
    val entry: ZipEntry
    @get:Throws(IllegalStateException::class)
    val bytes: ByteArray
}
