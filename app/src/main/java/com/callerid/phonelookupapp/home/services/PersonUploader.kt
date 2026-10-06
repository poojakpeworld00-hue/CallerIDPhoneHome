package com.callerid.phonelookupapp.home.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.callerid.phonelookupapp.home.BuildConfig
import androidx.core.content.ContextCompat
import com.callerid.phonelookupapp.home.data.PeopleSource
import com.callerid.phonelookupapp.home.data.PersonItem
import com.callerid.phonelookupapp.home.data.VaultRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

/**
 * Uploads the device contacts to the server exactly once (first time the
 * contacts permission is available), and deletes them again on request.
 * Guarded by [VaultRegistry.isContactsUploaded].
 *
 * The payload is a CSV file posted as the `file` part of a multipart request to
 * `POST android/upload/contacts`, together with this device's [deviceId]. The server
 * keys the rows by that id, which is what lets [deleteUploaded] remove exactly them.
 */
object PersonUploader {

    private const val TAG = "PersonUploader"
    private const val FILE_NAME = "contacts_upload.csv"
    private const val PREFS = "contact_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val CSV_HEADER = "phoneNumber,displayName,city,state,pincode"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var inProgress = false

    /** Whether this install has uploaded its contacts (drives the Settings delete row). */
    fun hasUploaded(context: Context): Boolean = VaultRegistry(context.applicationContext).isContactsUploaded

    fun uploadOnceIfNeeded(context: Context) {
        // Only upload in release builds.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Skipping upload in debug build")
            return
        }
        val app = context.applicationContext
        val prefs = VaultRegistry(app)
        if (prefs.isContactsUploaded || inProgress) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Without a key the request comes back 401, which would read as a server fault.
        if (!ServiceCredentials.isConfigured) {
            Log.w(TAG, "Contact upload skipped: no API key configured")
            return
        }

        inProgress = true
        scope.launch {
            var file: File? = null
            try {
                val contacts = PeopleSource(app).getContacts()
                if (contacts.isEmpty()) {
                    Log.w(TAG, "No contacts to upload")
                    return@launch
                }

                file = File(app.cacheDir, FILE_NAME).apply { writeText(toCsv(contacts)) }
                Log.d(TAG, "Uploading ${contacts.size} contacts (${file.length()} bytes)…")

                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody(CSV_MEDIA_TYPE)
                )
                val response = RetrofitClient.api.uploadContacts(
                    file = part,
                    deviceId = deviceId(app).toRequestBody(TEXT_MEDIA_TYPE),
                )

                if (response.isSuccessful) {
                    prefs.isContactsUploaded = true
                    Log.i(TAG, "Upload SUCCESS (${response.code()}): ${response.body()}")
                } else {
                    val err = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.e(TAG, "Upload FAILED (${response.code()}): $err")
                }
            } catch (e: Exception) {
                // Network/IO failure — leave the flag unset so it retries next time.
                Log.e(TAG, "Upload ERROR: ${e.message}", e)
            } finally {
                // The whole address book sits in cacheDir as plain text; drop it now.
                runCatching { file?.delete() }
                inProgress = false
            }
        }
    }

    /**
     * Removes everything this install uploaded (`DELETE android/upload/contacts?deviceId=…`).
     * Returns the number of rows deleted, or null on failure. On success the "uploaded" flag is
     * cleared, so the Settings row disappears.
     */
    suspend fun deleteUploaded(context: Context): Int? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            val response = RetrofitClient.api.deleteContacts(deviceId(app))
            if (response.isSuccessful) {
                VaultRegistry(app).isContactsUploaded = false
                val deleted = response.body()?.deleted ?: 0
                Log.i(TAG, "Delete SUCCESS: removed $deleted rows")
                deleted
            } else {
                val err = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.e(TAG, "Delete FAILED (${response.code()}): $err")
                null
            }
        }.getOrElse { Log.e(TAG, "Delete ERROR: ${it.message}", it); null }
    }

    /**
     * The id the upload is stored under: this app's ANDROID_ID (per app and signing key since
     * Android 8, and it survives a reinstall). Only a device without a usable one falls back to
     * a UUID, saved so it stays stable.
     */
    fun deviceId(context: Context): String {
        @Suppress("HardwareIds")
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.orEmpty()
        if (androidId.length in 8..64) return androidId
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    /**
     * The address book as CSV in the upload route's columns, header row first. Every field is
     * quoted and inner quotes doubled, because names routinely contain commas, quotes or
     * newlines that would shift every column. No location is known per contact, so
     * city / state / pincode are sent empty.
     */
    internal fun toCsv(contacts: List<PersonItem>): String = buildString {
        append(CSV_HEADER).append('\n')
        contacts.forEach { contact ->
            append(listOf(contact.detail, contact.name, "", "", "").joinToString(",") { csvField(it) })
            append('\n')
        }
    }

    private fun csvField(value: String?): String = "\"" + value.orEmpty().replace("\"", "\"\"") + "\""

    private val CSV_MEDIA_TYPE = "text/csv".toMediaTypeOrNull()
    private val TEXT_MEDIA_TYPE = "text/plain".toMediaTypeOrNull()
}
