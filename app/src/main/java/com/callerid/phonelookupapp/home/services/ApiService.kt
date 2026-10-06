package com.callerid.phonelookupapp.home.services

import com.google.gson.JsonObject
import com.callerid.phonelookupapp.home.models.DialResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * The contact-saver API. The `x-api-key` credential is added by [AuthInterceptor],
 * never passed here, so it cannot drift back into a query parameter.
 *
 * Paths are relative (no leading `/`) so they resolve against the base URL's path.
 */
interface ApiService {

    /** `GET similar-phone-number?phone=…` */
    @GET("similar-phone-number")
    suspend fun checkPhoneNumber(
        @Query("phone") phone: String
    ): Response<DialResponse>

    /**
     * `POST android/upload/contacts` — multipart: the CSV as `file`, plus the `deviceId` the rows
     * are stored under, so [deleteContacts] can remove exactly this device's upload later.
     */
    @Multipart
    @POST("android/upload/contacts")
    suspend fun uploadContacts(
        @Part file: MultipartBody.Part,
        @Part("deviceId") deviceId: RequestBody,
    ): Response<JsonObject>

    /** `DELETE android/upload/contacts?deviceId=…` — removes everything uploaded under [deviceId]. */
    @DELETE("android/upload/contacts")
    suspend fun deleteContacts(
        @Query("deviceId") deviceId: String,
    ): Response<DeleteContactsResult>
}

/** The delete route's answer: how many rows were removed for [deviceId]. */
data class DeleteContactsResult(
    val success: Boolean = false,
    val deviceId: String = "",
    val deleted: Int = 0,
)
