package com.utaloom.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object PlaylistDocuments {
    private var open: ActivityResultLauncher<Array<String>>? = null
    private var create: ActivityResultLauncher<String>? = null
    private var pending: CancellableContinuation<Uri?>? = null

    fun attach(activity: ComponentActivity) {
        fun finish(uri: Uri?) { pending?.let { if (it.isActive) it.resume(uri) }; pending = null }
        open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { finish(it) }
        create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { finish(it) }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                pending?.cancel(); pending = null
                open = null; create = null
            }
        })
    }

    suspend fun choose(name: String?): Uri? = withContext(Dispatchers.Main) {
        check(pending == null) { "A file picker is already open" }
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            try {
                if (name == null) checkNotNull(open).launch(arrayOf("application/json", "text/plain"))
                else checkNotNull(create).launch(name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "playlist" } + ".json")
            } catch (e: Exception) { pending = null; continuation.cancel(e) }
        }
    }
}
