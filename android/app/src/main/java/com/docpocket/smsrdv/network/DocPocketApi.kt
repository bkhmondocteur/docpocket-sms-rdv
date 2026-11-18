package com.docpocket.smsrdv.network

import com.docpocket.smsrdv.data.local.AppointmentEntity
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface DocPocketApi {

    // On the Express server, GET returns a Map<String, AppointmentEntity> (json object with keys)
    @GET("api/appointments")
    suspend fun getAppointments(): Response<Map<String, AppointmentEntity>>

    @POST("api/appointments")
    suspend fun createAppointment(@Body appointment: AppointmentEntity): Response<AppointmentResponse>

    @PUT("api/appointments/{id}")
    suspend fun updateAppointment(
        @Path("id") id: String,
        @Body appointment: AppointmentEntity
    ): Response<AppointmentEntity>

    @DELETE("api/appointments/{id}")
    suspend fun deleteAppointment(@Path("id") id: String): Response<Unit>
}

data class AppointmentResponse(
    val id: String,
    val data: AppointmentEntity
)
