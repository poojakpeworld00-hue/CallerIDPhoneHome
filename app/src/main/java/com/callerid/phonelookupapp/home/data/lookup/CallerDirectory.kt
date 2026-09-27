package com.callerid.phonelookupapp.home.data.lookup

import android.util.Log
import com.callerid.phonelookupapp.home.models.DialData
import com.callerid.phonelookupapp.home.models.DialResponse
import com.callerid.phonelookupapp.home.services.RetrofitClient
import com.callerid.phonelookupapp.home.services.ServiceCredentials

/**
 * The caller-ID network lookup, in one place.
 *
 * The Lookup screen and the incoming-call card ask the same question of the same endpoint,
 * so they ask it through here. A second copy of the credential guard and the response
 * unwrapping is how two surfaces start disagreeing about who a number belongs to.
 *
 * Never throws: an unreachable server, a missing API key or a malformed body all come back as
 * an empty list from [lookup] (null from [lookupResponse]), and every caller already has
 * something to show without it.
 */
object CallerDirectory {

    private const val TAG = "CallerDirectory"

    /**
     * Records the network holds for [phone], newest-first as the server returns them.
     * Empty when the number is unknown to it — or when there was no way to ask.
     *
     * Call from a coroutine; the underlying Retrofit call is `suspend` and runs on OkHttp's
     * own dispatcher.
     */
    suspend fun lookup(phone: String): List<DialData> = lookupResponse(phone)?.data.orEmpty()

    /**
     * The whole `GET similar-phone-number` response — the per-name records plus the number-level
     * facts (ISO country, location, spam flag) contact-saver keeps at the top level. Null when
     * there was no way to ask or the call failed; the `x-api-key` header is added by AuthInterceptor.
     */
    suspend fun lookupResponse(phone: String): DialResponse? = runCatching {
        if (!ServiceCredentials.isConfigured) {
            Log.w(TAG, "lookup skipped: no API key configured")
            return@runCatching null
        }
        val response = RetrofitClient.api.checkPhoneNumber(phone = phone)
        if (response.isSuccessful) {
            response.body()
        } else {
            Log.e(TAG, "lookup failed (${response.code()})")
            null
        }
    }.onFailure { Log.e(TAG, "lookup error: ${it.message}") }.getOrNull()
}
