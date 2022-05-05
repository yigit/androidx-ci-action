/*
 * Copyright 2022 The Android Open Source Project
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

package dev.androidx.ci.testRunner

import com.google.auth.Credentials
import dev.androidx.ci.config.Config
import dev.androidx.ci.datastore.DatastoreApi
import dev.androidx.ci.firebase.FirebaseTestLabApi
import dev.androidx.ci.firebase.ToolsResultApi
import dev.androidx.ci.gcloud.GoogleCloudApi
import dev.androidx.ci.generated.ftl.AndroidDevice
import dev.androidx.ci.generated.ftl.TestEnvironmentCatalog
import dev.androidx.ci.generated.ftl.TestMatrix
import dev.androidx.ci.testRunner.vo.TestResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.logging.HttpLoggingInterceptor
import org.apache.logging.log4j.kotlin.logger
import java.io.File
import java.io.Serializable
import java.util.concurrent.TimeUnit

class TestRunnerService(
    private val googleCloudApi: GoogleCloudApi,
    datastoreApi: DatastoreApi,
    firebaseTestLabApi: FirebaseTestLabApi,
    toolsResultApi: ToolsResultApi,
    firebaseProjectId: String,
    gcsResultPath: String, // should be unique per run, otherwise it will turn into a giant folder
) {
    private val logger = logger()
    private val testMatrixStore = TestMatrixStore(
        firebaseProjectId = firebaseProjectId,
        datastoreApi = datastoreApi,
        firebaseTestLabApi = firebaseTestLabApi,
        toolsResultApi = toolsResultApi,
        resultsGcsPrefix = googleCloudApi.getGcsPath("ftl/$gcsResultPath")
    )
    private val apkStore = ApkStore(googleCloudApi)
    val testLabController = FirebaseTestLabController(
        firebaseTestLabApi = firebaseTestLabApi,
        firebaseProjectId = firebaseProjectId,
        testMatrixStore = testMatrixStore
    )

    /**
     * Runs the test for the given [testApk] [appApk] pair by invoking [scheduleTests] followed by
     * [getTestResults]. See their documentation for details.
     *
     * @see scheduleTests
     * @see getTestResults
     */
    suspend fun runTest(
        testApk: File,
        appApk: File? = null,
        localDownloadFolder: File,
        devices: List<AndroidDevice>
    ): TestRunResponse {
        return runTest(
            testApk = testApk,
            appApk = appApk,
            localDownloadFolder = localDownloadFolder,
            devicePicker = {
                devices
            }
        )
    }

    /**
     * Schedules the tests for the given devices.
     *
     * @see scheduleTests for details.
     */
    suspend fun scheduleTests(
        testApk: File,
        appApk: File? = null,
        devices: List<AndroidDevice>
    ): ScheduledFtlTests = scheduleTests(
        testApk = testApk,
        appApk = appApk,
        devicePicker = { devices }
    )
    /**
     * Schedules test runs on FirebaseTestLab for the given testApk / appApk pair.
     * Note that this method will return before test executions are complete.
     *
     * @param testApk The test apk which has the instrumentation tests
     * @param appApk The application under test. Can be `null` for library tests
     * @param devicePicker A block that can return desired devices from a [TestEnvironmentCatalog].
     *
     * @return A [ScheduledFtlTests] that includes the information about the tests that are
     * scheduled. You can later use the [getTestResults] API to collect results.
     */
    suspend fun scheduleTests(
        testApk: File,
        appApk: File? = null,
        devicePicker: DevicePicker? = null,
    ): ScheduledFtlTests {
        logger.trace { "Scheduling tests for testApk: $testApk appApk: $appApk" }
        val uploadedTestApk = apkStore.uploadApk(testApk.name, testApk.readBytes())
        val uploadedAppApk = appApk?.let {
            apkStore.uploadApk(appApk.name, appApk.readBytes())
        } ?: apkStore.getPlaceholderApk()
        logger.trace { "Will submit tests to the test lab" }
        val testMatrices = testLabController.submitTests(
            appApk = uploadedAppApk,
            testApk = uploadedTestApk,
            devicePicker = devicePicker
        )
        logger.trace { "Enqueued ${testMatrices.size} matrices" }
        return ScheduledFtlTests.create(testMatrices)
    }

    /**
     * Queries the Firebase Test Lab API to get the results of the [scheduledTests].
     * It will also download the outputs of the test into the given [localDownloadFolder]. You
     * can access downloaded artifacts via [TestRunResponse.downloads].
     *
     *
     * @param scheduledTests The list of [ScheduledFtlTests] that are obtained from [scheduledTests]
     * @param localDownloadFolder A local folder into which the results will be downloaded. Note
     * that the contents of the folder will be cleared.
     * @param pollIntervalMs FirebaseTestLab does not provide an API to observe results, hence this
     * method will poll one of the pending [TestMatrix]es in the given interval, until all of them
     * are complete.
     *
     * @return a [TestRunResponse] that includes all of the [TestMatrix] information along with the
     * downloaded artifacts.
     */
    suspend fun getTestResults(
        scheduledTests: List<ScheduledFtlTests>,
        localDownloadFolder: File,
        pollIntervalMs: Long = TimeUnit.SECONDS.toMillis(10)
    ): TestRunResponse {
        val testMatrixIds = scheduledTests.flatMap {
            it.testMatrixIds
        }.distinct()
        logger.trace { "Will collect results for ${testMatrixIds.size} matrices" }
        val result = testLabController.collectTestResultsByTestMatrixIds(
            testMatrixIds = testMatrixIds,
            pollIntervalMs = pollIntervalMs
        )
        logger.trace { "All test matrices complete. Downloading outputs." }
        val downloadResult = TestResultDownloader(
            googleCloudApi = googleCloudApi
        ).downloadTestResults(
            outputFolder = localDownloadFolder,
            result = result
        )
        return TestRunResponse(
            testResult = result,
            downloads = downloadResult
        )
    }

    /**
     * Runs the test for the given [testApk] [appApk] pair by invoking [scheduleTests] followed by
     * [getTestResults]. See their documentation for details.
     *
     * @see scheduleTests
     * @see getTestResults
     */
    suspend fun runTest(
        testApk: File,
        appApk: File? = null,
        localDownloadFolder: File,
        devicePicker: DevicePicker? = null,
    ): TestRunResponse {
        logger.trace { "Running tests for testApk: $testApk appApk: $appApk" }
        val enqueue = scheduleTests(
            testApk = testApk,
            appApk = appApk,
            devicePicker = devicePicker
        )
        return getTestResults(listOf(enqueue), localDownloadFolder = localDownloadFolder)
    }

    companion object {
        fun create(
            /**
             * service account file contents
             */
            credentials: Credentials,
            /**
             * Firebase project id
             */
            firebaseProjectId: String,
            /**
             * GCP bucket name
             */
            bucketName: String,
            /**
             * GCP bucket path (path inside the bucket)
             */
            bucketPath: String,
            /**
             * GCP path to put the results into
             */
            gcsResultPath: String,
            /**
             * If enabled, HTTP requests will also be logged. Keep in mind, they might include
             * sensitive data.
             */
            logHttpCalls: Boolean = true,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TestRunnerService {
            val httpLogLevel = if (logHttpCalls) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            return TestRunnerService(
                googleCloudApi = GoogleCloudApi.build(
                    Config.GCloud(
                        credentials = credentials,
                        bucketName = bucketName,
                        bucketPath = bucketPath
                    ),
                    context = ioDispatcher
                ),
                datastoreApi = DatastoreApi.build(
                    Config.Datastore(
                        credentials = credentials
                    ),
                    context = ioDispatcher
                ),
                toolsResultApi = ToolsResultApi.build(
                    config = Config.ToolsResult(
                        credentials = credentials,
                        httpLogLevel = httpLogLevel
                    )
                ),
                firebaseProjectId = firebaseProjectId,
                firebaseTestLabApi = FirebaseTestLabApi.build(
                    config = Config.FirebaseTestLab(
                        credentials = credentials,
                        httpLogLevel = httpLogLevel
                    )
                ),
                gcsResultPath = gcsResultPath
            )
        }
    }

    data class TestRunResponse(
        val testResult: TestResult,
        val downloads: List<TestResultDownloader.DownloadedTestResults>
    ) {
        fun downloadsFor(
            testMatrixId: String
        ) = downloads.find {
            it.testMatrixId == testMatrixId
        }

        fun testMatrixFor(
            testMatrixId: String
        ): TestMatrix? = (testResult as TestResult.CompleteRun).matrices.find {
            it.testMatrixId == testMatrixId
        }
    }

    /**
     * A [Serializable] class that holds the information for a set of [TestMatrix]es that are
     * scheduled in Firebase Test Lab.
     *
     * You can later use this object to get the results of those tests.
     */
    data class ScheduledFtlTests(
        /**
         * List of test matrix ids that are scheduled to run
         */
        val testMatrixIds: List<String>,
        /**
         * Number of tests that are new
         */
        val newTests: Int,
        /**
         * Number of tests where we used a previously scheduled run
         */
        val cachedTests: Int,
    ) : Serializable {
        companion object {
            fun create(
                testMatices: List<TestMatrix>
            ) = ScheduledFtlTests(
                testMatrixIds = testMatices.map {
                    checkNotNull(it.testMatrixId) {
                        "Invalid test matrix without id!!! $testMatices"
                    }
                },
                newTests = testMatices.count {
                    it.outcomeSummary == null
                },
                cachedTests = testMatices.count {
                    it.outcomeSummary != null
                }
            )
        }
    }
}
