package com.callerid.phonelookupapp.home.data.lookup

import android.util.Log
import com.callerid.phonelookupapp.home.models.DialData
import com.callerid.phonelookupapp.home.services.RetrofitClient
import com.callerid.phonelookupapp.home.services.ServiceCredentials

/**
 * The caller-ID network lookup, in one place.
 *
 * The Lookup screen and the incoming-call card ask the same question of the same endpoint,
 * so they ask it through here. A second copy of the credential guard and the response
 * unwrapping is how two surfaces start disagreeing about who a number belongs to.
 *
 * Never throws and never returns null: an unreachable server, a placeholder credential set
 * or a malformed body all come back as an empty list, and every caller already has
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
    suspend fun lookup(phone: String): List<DialData> = runCatching {
        if (!ServiceCredentials.isConfigured) {
            Log.w(TAG, "lookup skipped: API credentials are placeholders")
            return@runCatching emptyList()
        }
        val response = RetrofitClient.api.checkPhoneNumber(
            id = ServiceCredentials.API_ID,
            phone = phone,
            hashKey = ServiceCredentials.API_HASH,
            token = ServiceCredentials.API_TOKEN
        )
        if (response.isSuccessful) {
            response.body()?.data.orEmpty()
        } else {
            Log.e(TAG, "lookup failed (${response.code()})")
            emptyList()
        }
    }.onFailure { Log.e(TAG, "lookup error: ${it.message}") }.getOrDefault(emptyList())
}
