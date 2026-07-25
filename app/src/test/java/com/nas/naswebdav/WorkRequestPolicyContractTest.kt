package com.nas.naswebdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WorkRequestPolicyContractTest — release-blocking policy guard.
 *
 * Why this is a contract test rather than a unit test:
 *
 * Every WorkManager scheduling site that uses Constraints (network, charging,
 * battery) MUST also set a backoff policy. Without `setBackoffCriteria`, a
 * transient failure (server down, no network) makes the worker fail
 * permanently with no retry — which silently breaks background sync.
 *
 * This test reads the source files that build WorkRequest instances and
 * asserts the policy holds. It does not exercise WorkManager itself
 * (which requires Robolectric/Android) but pins the contract at the
 * source-text level so future refactors cannot silently break it.
 *
 * Coverage map:
 * - auth/AuthSessionViewModel.enqueueOfflineAction (CONNECTED constraint)
 * - ui/screens/MainMenuScreen.AutoBackup periodic (UNMETERED + charging)
 * - smarttools/SmartToolsViewModel.scheduleAutoDuplicate (charging + UNMETERED)
 * - PowerActions.scheduleIdleDuplicateScan, scheduleIdleSpeedTest, scheduleFingerprintWorker
 * - LivestreamDiscoveryWorker.schedule (CONNECTED constraint)
 */
class WorkRequestPolicyContractTest {

    @Test
    fun `every constrained WorkRequest builder also sets a backoff policy`() {
        val filesUnderContract = listOf(
            "app/src/main/java/com/nas/naswebdav/auth/AuthSessionViewModel.kt",
            "app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt",
            "app/src/main/java/com/nas/naswebdav/smarttools/SmartToolsViewModel.kt",
            "app/src/main/java/com/nas/naswebdav/PowerActions.kt",
            "app/src/main/java/com/nas/naswebdav/LivestreamDiscoveryWorker.kt"
        )

        for (relativePath in filesUnderContract) {
            val file = locateSourceFile(relativePath)
            assertNotNull("Source file must exist: $relativePath", file)
            val body = file!!.readText()

            val usesConstraints = body.contains("Constraints.Builder") ||
                body.contains("setRequiredNetworkType") ||
                body.contains("setRequiresCharging")
            val buildsWorkRequest = body.contains("OneTimeWorkRequestBuilder") ||
                body.contains("PeriodicWorkRequestBuilder")
            val hasBackoff = body.contains("setBackoffCriteria")

            // Only enforce the contract on files that actually build a constrained request.
            // Files that just import the WorkManager API or define helpers stay out of scope.
            if (usesConstraints && buildsWorkRequest) {
                assertTrue(
                    "Contract violation: $relativePath uses Constraints but does NOT " +
                        "call setBackoffCriteria. Transient failures will not retry.",
                    hasBackoff
                )
            }
        }
    }

    @Test
    fun `no production scheduling site drops setBackoffCriteria`() {
        val root = locateSourceFile("app/src/main/java") ?: return
        val offenders = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { !it.path.contains("/test/") && !it.path.contains("/androidTest/") }
            .forEach { file ->
                val body = file.readText()
                val hasConstraints = body.contains("Constraints.Builder")
                val hasBuilder = body.contains("OneTimeWorkRequestBuilder") ||
                    body.contains("PeriodicWorkRequestBuilder")
                val hasBackoff = body.contains("setBackoffCriteria")
                if (hasConstraints && hasBuilder && !hasBackoff) {
                    offenders += file.path
                }
            }

        assertFalse(
            "All constrained WorkRequest builders must include setBackoffCriteria. " +
                "Offenders: $offenders",
            offenders.isNotEmpty()
        )
    }

    private fun locateSourceFile(relativePath: String): File? {
        // Walk upwards from the working directory to find the project root.
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            if (dir == null) return null
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
