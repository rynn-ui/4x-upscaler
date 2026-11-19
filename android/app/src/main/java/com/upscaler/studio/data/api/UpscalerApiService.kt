package com.upscaler.studio.data.api

import com.upscaler.studio.data.model.CapabilitiesResponse
import com.upscaler.studio.data.model.HealthResponse
import com.upscaler.studio.data.model.JobStatusResponse
import com.upscaler.studio.data.model.JobSubmitResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface UpscalerApiService {

    @GET("/api/v1/health")
    suspend fun getHealth(): HealthResponse

    @GET("/api/v1/capabilities")
    suspend fun getCapabilities(): CapabilitiesResponse

    @Multipart
    @POST("/api/v1/jobs/submit")
    suspend fun submitJob(
        @Part file: MultipartBody.Part,
        @Part("task_type") taskType: RequestBody,
        @Part("params_json") paramsJson: RequestBody? = null,
        @Part("client_submission_id") clientSubmissionId: RequestBody? = null
    ): JobSubmitResponse

    @POST("/api/v1/jobs/cancel-client-submission")
    suspend fun cancelClientSubmission(
        @Query("client_submission_id") clientSubmissionId: String
    ): Response<Unit>

    @GET("/api/v1/jobs/{job_id}/status")
    suspend fun getJobStatus(@Path("job_id") jobId: String): JobStatusResponse

    @POST("/api/v1/jobs/{job_id}/pause")
    suspend fun pauseJob(@Path("job_id") jobId: String): Response<Unit>

    @POST("/api/v1/jobs/{job_id}/resume")
    suspend fun resumeJob(@Path("job_id") jobId: String): Response<Unit>

    @POST("/api/v1/jobs/{job_id}/cancel")
    suspend fun cancelJob(@Path("job_id") jobId: String): Response<Unit>

    @Streaming
    @GET("/api/v1/jobs/{job_id}/download")
    suspend fun downloadOutput(@Path("job_id") jobId: String): Response<ResponseBody>
}

