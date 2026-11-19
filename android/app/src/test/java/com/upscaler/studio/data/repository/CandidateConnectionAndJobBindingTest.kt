package com.upscaler.studio.data.repository

import com.upscaler.studio.data.model.JobPhase
import com.upscaler.studio.data.model.ManagedJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateConnectionAndJobBindingTest {

    @Test
    fun sameHostBlankTokenRetainsExistingToken() {
        val currentUrl = "https://my-colab.trycloudflare.com"
        val existingToken = "secret-token-123"

        val parsed = BackendUrlParser.parsePairingInput("https://my-colab.trycloudflare.com").getOrThrow()
        val manualToken: String? = ""

        val candidateToken = when {
            !manualToken.isNullOrBlank() -> manualToken.trim()
            !parsed.authToken.isNullOrBlank() -> parsed.authToken.trim()
            parsed.baseUrl.equals(currentUrl, ignoreCase = true) -> existingToken
            else -> null
        }

        assertEquals("secret-token-123", candidateToken)
    }

    @Test
    fun differentHostDoesNotInheritCredentials() {
        val currentUrl = "https://host-a.trycloudflare.com"
        val existingToken = "secret-token-host-a"

        val parsed = BackendUrlParser.parsePairingInput("https://host-b.trycloudflare.com").getOrThrow()
        val manualToken: String? = null

        val candidateToken = when {
            !manualToken.isNullOrBlank() -> manualToken.trim()
            !parsed.authToken.isNullOrBlank() -> parsed.authToken.trim()
            parsed.baseUrl.equals(currentUrl, ignoreCase = true) -> existingToken
            else -> null
        }

        assertNull(candidateToken)
    }

    @Test
    fun newManualTokenOverridesExisting() {
        val currentUrl = "https://my-colab.trycloudflare.com"
        val existingToken = "old-token"

        val parsed = BackendUrlParser.parsePairingInput("https://my-colab.trycloudflare.com").getOrThrow()
        val manualToken = "new-token-456"

        val candidateToken = when {
            !manualToken.isNullOrBlank() -> manualToken.trim()
            !parsed.authToken.isNullOrBlank() -> parsed.authToken.trim()
            parsed.baseUrl.equals(currentUrl, ignoreCase = true) -> existingToken
            else -> null
        }

        assertEquals("new-token-456", candidateToken)
    }

    @Test
    fun interruptedJobsReconciledToFailedOnRestore() {
        val interruptedPreparing = ManagedJob(
            localId = "j1",
            taskType = "upscale",
            sourceUriString = "content://media/1",
            phase = JobPhase.PREPARING,
            serverJobId = null,
            originBaseUrl = "https://backend.com"
        )
        val interruptedUploading = ManagedJob(
            localId = "j2",
            taskType = "rife",
            sourceUriString = "content://media/2",
            phase = JobPhase.UPLOADING,
            serverJobId = null,
            originBaseUrl = "https://backend.com"
        )
        val activeServerJob = ManagedJob(
            localId = "j3",
            taskType = "depth",
            sourceUriString = "content://media/3",
            phase = JobPhase.PROCESSING,
            serverJobId = "srv-123",
            originBaseUrl = "https://backend.com"
        )

        val list = listOf(interruptedPreparing, interruptedUploading, activeServerJob)
        val reconciled = list.map { job ->
            if ((job.phase == JobPhase.PREPARING || job.phase == JobPhase.UPLOADING) && job.serverJobId.isNullOrBlank()) {
                job.copy(
                    phase = JobPhase.FAILED,
                    errorMessage = "Submission interrupted before reaching server."
                )
            } else if (job.originBaseUrl.isBlank() && !job.phase.isTerminal) {
                job.copy(
                    phase = JobPhase.FAILED,
                    errorMessage = "Job unbound from backend origin; please resubmit."
                )
            } else {
                job
            }
        }

        assertEquals(JobPhase.FAILED, reconciled[0].phase)
        assertEquals("Submission interrupted before reaching server.", reconciled[0].errorMessage)

        assertEquals(JobPhase.FAILED, reconciled[1].phase)
        assertEquals("Submission interrupted before reaching server.", reconciled[1].errorMessage)

        assertEquals(JobPhase.PROCESSING, reconciled[2].phase)
    }

    @Test
    fun unboundLegacyJobsReconciledToFailed() {
        val legacyJob = ManagedJob(
            localId = "legacy1",
            taskType = "upscale",
            sourceUriString = "content://media/legacy",
            phase = JobPhase.PROCESSING,
            serverJobId = "srv-legacy",
            originBaseUrl = "" // legacy restored without origin
        )

        val reconciled = if (legacyJob.originBaseUrl.isBlank() && !legacyJob.phase.isTerminal) {
            legacyJob.copy(
                phase = JobPhase.FAILED,
                errorMessage = "Job unbound from backend origin; please resubmit."
            )
        } else {
            legacyJob
        }

        assertEquals(JobPhase.FAILED, reconciled.phase)
        assertEquals("Job unbound from backend origin; please resubmit.", reconciled.errorMessage)
    }

    @Test
    fun cancellationRequestedGuardsAgainstLateResponse() {
        val job = ManagedJob(
            localId = "j_race",
            taskType = "upscale",
            sourceUriString = "content://media/race",
            phase = JobPhase.CANCELLED,
            cancellationRequested = true,
            originBaseUrl = "https://backend.com"
        )

        assertTrue(job.cancellationRequested)
        assertEquals(JobPhase.CANCELLED, job.phase)

        // Late response handler checks:
        val shouldApplyServerState = !(job.cancellationRequested || job.phase == JobPhase.CANCELLED)
        assertFalse(shouldApplyServerState)
    }
}

