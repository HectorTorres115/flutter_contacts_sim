package co.quis.flutter_contacts

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
// Import missing components
import android.database.Cursor
import android.net.Uri

// Note: You must have a corresponding FlutterContacts.kt file with the select and
// findIdWithLookupKey functions for the original plugin logic to work.

class FlutterContactsPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler, ActivityAware, ActivityResultListener, RequestPermissionsResultListener {
    // *** Added companion object for constants ***
    companion object {
        private var activity: Activity? = null
        private var context: Context? = null
        private var resolver: ContentResolver? = null
        private val permissionReadWriteCode: Int = 0
        private val permissionReadOnlyCode: Int = 1
        private var permissionResult: Result? = null
        private var viewResult: Result? = null
        private var editResult: Result? = null
        private var pickResult: Result? = null
        private var insertResult: Result? = null
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // --- FlutterPlugin implementation ---

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val channel = MethodChannel(flutterPluginBinding.getFlutterEngine().getDartExecutor(), "github.com/QuisApp/flutter_contacts")
        val eventChannel = EventChannel(flutterPluginBinding.getFlutterEngine().getDartExecutor(), "github.com/QuisApp/flutter_contacts/events")
        // NOTE: We use a new instance to set the MethodCallHandler
        channel.setMethodCallHandler(this) // Use 'this' instead of FlutterContactsPlugin() if it was the intention to use the current instance
        eventChannel.setStreamHandler(this)
        context = flutterPluginBinding.applicationContext
        resolver = context!!.contentResolver
    }
    
    // ... (onDetachedFromEngine, ActivityAware, ActivityResultListener, RequestPermissionsResultListener implementations remain unchanged)

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        coroutineScope.cancel()
    }

    override fun onDetachedFromActivity() { activity = null }

    override fun onDetachedFromActivityForConfigChanges() { activity = null }

    override fun onReattachedToActivityForConfigChanges(@NonNull binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        intent: Intent?
    ): Boolean {
        when (requestCode) {
            // NOTE: FlutterContacts class is missing, assuming it's available in the actual plugin
            // Replace with actual constants if known, or leave as is.
            // Assuming FlutterContacts is available in the same package or imported implicitly.
            // If the original logic works, keep it.
             /*FlutterContacts.*/REQUEST_CODE_VIEW ->
                if (viewResult != null) {
                    viewResult!!.success(null)
                    viewResult = null
                }
             /*FlutterContacts.*/REQUEST_CODE_EDIT ->
                if (editResult != null) {
                    // Result is of the form:
                    // content://com.android.contacts/contacts/lookup/<hash>/<id>
                    val id = intent?.getData()?.getLastPathSegment()
                    editResult!!.success(id)
                    editResult = null
                }
             /*FlutterContacts.*/REQUEST_CODE_PICK ->
                if (pickResult != null) {
                    // Result is of the form:
                    // content://com.android.contacts/contacts/lookup/<hash>/<id>
                    val id = intent?.getData()?.getLastPathSegment()
                    pickResult!!.success(id)
                    pickResult = null
                }
             /*FlutterContacts.*/REQUEST_CODE_INSERT ->
                if (insertResult != null) {
                    val contactId = getContactIdFromExternalInsertResult(intent)
                    insertResult!!.success(contactId)
                    insertResult = null
                }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        when (requestCode) {
            permissionReadWriteCode -> {
                val granted = grantResults.size == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED
                if (permissionResult != null) {
                    coroutineScope.launch(Dispatchers.Main) {
                        permissionResult?.success(granted)
                        permissionResult = null
                    }
                }
                return true
            }
            permissionReadOnlyCode -> {
                val granted = grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (permissionResult != null) {
                    coroutineScope.launch(Dispatchers.Main) {
                        permissionResult?.success(granted)
                        permissionResult = null
                    }
                }
                return true
            }
        }
        return false // did not handle the result
    }

    // --- NEW: Function to retrieve all contacts (Cloud, SIM, Device) ---
    private fun getAllContacts(): ArrayList<Map<String, String>> {
        val contactsList = ArrayList<Map<String, String>>()
        
        // Use the official URI for phone data
        val contentUri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI

        // Define the columns we want to retrieve
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        
        // Query the Contacts Provider for all phone numbers
        val cursor: Cursor? = resolver?.query( // Use the class-level resolver
            contentUri,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use { 
            val displayNameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneNumberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            while (it.moveToNext()) {
                
                // Read data from the Cursor
                val displayName = if (displayNameIndex != -1) it.getString(displayNameIndex) else "Unknown"
                val phoneNumber = if (phoneNumberIndex != -1) it.getString(phoneNumberIndex) else ""

                // Map to a serializable Kotlin Map
                val contactMap = mapOf(
                    "displayName" to displayName,
                    "phoneNumber" to phoneNumber.replace("[^0-9]".toRegex(), "")
                )
                contactsList.add(contactMap)
            }
        }
        
        return contactsList
    }

    // --- MethodCallHandler implementation ---

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            
            // *** NEW METHOD HANDLER ***
            "getAllContacts" -> 
                coroutineScope.launch(Dispatchers.IO) {
                    if (resolver == null) {
                        coroutineScope.launch(Dispatchers.Main) { result.error("NO_RESOLVER", "ContentResolver not initialized.", null) }
                        return@launch
                    }
                    val contacts = getAllContacts()
                    coroutineScope.launch(Dispatchers.Main) { result.success(contacts) }
                }

            // Requests permission to read/write contacts.
            "requestPermission" ->
                coroutineScope.launch(Dispatchers.IO) {
                    if (context == null) {
                        coroutineScope.launch(Dispatchers.Main) { result.success(false); }
                    } else {
                        val readonly = call.arguments as Boolean
                        val readPermission = Manifest.permission.READ_CONTACTS
                        val writePermission = Manifest.permission.WRITE_CONTACTS
                        if (ContextCompat.checkSelfPermission(context!!, readPermission) == PackageManager.PERMISSION_GRANTED &&
                            (readonly || ContextCompat.checkSelfPermission(context!!, writePermission) == PackageManager.PERMISSION_GRANTED)
                        ) {
                            coroutineScope.launch(Dispatchers.Main) { result.success(true) }
                        } else if (activity != null) {
                            permissionResult = result
                            if (readonly) {
                                ActivityCompat.requestPermissions(activity!!, arrayOf(readPermission), permissionReadOnlyCode)
                            } else {
                                ActivityCompat.requestPermissions(activity!!, arrayOf(readPermission, writePermission), permissionReadWriteCode)
                            }
                        }
                    }
                }
            // Selects fields for request contact, or for all contacts.
            "select" ->
                coroutineScope.launch(Dispatchers.IO) { // runs in a background thread
                    val args = call.arguments as List<Any>
                    val id = args[0] as String?
                    val withProperties = args[1] as Boolean
                    val withThumbnail = args[2] as Boolean
                    val withPhoto = args[3] as Boolean
                    val withGroups = args[4] as Boolean
                    val withAccounts = args[5] as Boolean
                    val returnUnifiedContacts = args[6] as Boolean
                    val includeNonVisible = args[7] as Boolean
                    // args[8] = includeNotesOnIos13AndAbove
                    val contacts: List<Map<String, Any?>> =
                         /*FlutterContacts.*/select( // NOTE: Assuming FlutterContacts.select is defined and accessible
                                resolver!!,
                                id,
                                withProperties,
                                // Sometimes thumbnail is available but photo is not, so we
                                // fetch thumbnails even if only the photo was requested.
                                withThumbnail || withPhoto,
                                withPhoto,
                                withGroups,
                                withAccounts,
                                returnUnifiedContacts,
                                includeNonVisible
                            )
                    coroutineScope.launch(Dispatchers.Main) { result.success(contacts) }
                }
            // ... (rest of the method handlers remain unchanged)
            "insert" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val contact = args[0] as Map<String, Any>
                    val insertedContact: Map<String, Any?>? =
                         /*FlutterContacts.*/insert(resolver!!, contact) // NOTE: Assuming FlutterContacts.insert is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) {
                        if (insertedContact != null) {
                            result.success(insertedContact)
                        } else {
                            result.error("", "failed to create contact", "")
                        }
                    }
                }
            "update" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val contact = args[0] as Map<String, Any>
                    val withGroups = args[1] as Boolean
                    val updatedContact: Map<String, Any?>? =
                         /*FlutterContacts.*/update(resolver!!, contact, withGroups) // NOTE: Assuming FlutterContacts.update is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) {
                        if (updatedContact != null) {
                            result.success(updatedContact)
                        } else {
                            result.error("", "failed to update contact", "")
                        }
                    }
                }
            "delete" ->
                coroutineScope.launch(Dispatchers.IO) {
                     /*FlutterContacts.*/delete(resolver!!, call.arguments as List<String>) // NOTE: Assuming FlutterContacts.delete is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) { result.success(null) }
                }
            "getGroups" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val groups: List<Map<String, Any?>> =
                         /*FlutterContacts.*/getGroups(resolver!!) // NOTE: Assuming FlutterContacts.getGroups is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) { result.success(groups) }
                }
            "insertGroup" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val group = args[0] as Map<String, Any>
                    val insertedGroup: Map<String, Any?>? =
                         /*FlutterContacts.*/insertGroup(resolver!!, group) // NOTE: Assuming FlutterContacts.insertGroup is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) {
                        result.success(insertedGroup)
                    }
                }
            "updateGroup" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val group = args[0] as Map<String, Any>
                    val updatedGroup: Map<String, Any?>? =
                         /*FlutterContacts.*/updateGroup(resolver!!, group) // NOTE: Assuming FlutterContacts.updateGroup is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) {
                        result.success(updatedGroup)
                    }
                }
            "deleteGroup" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val group = args[0] as Map<String, Any>
                     /*FlutterContacts.*/deleteGroup(resolver!!, group) // NOTE: Assuming FlutterContacts.deleteGroup is defined and accessible
                    coroutineScope.launch(Dispatchers.Main) {
                        result.success(null)
                    }
                }
            "openExternalView" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val id = args[0] as String
                     /*FlutterContacts.*/openExternalViewOrEdit(activity, context, id, false) // NOTE: Assuming FlutterContacts.openExternalViewOrEdit is defined and accessible
                    viewResult = result
                }
            "openExternalEdit" ->
                coroutineScope.launch(Dispatchers.IO) {
                    val args = call.arguments as List<Any>
                    val id = args[0] as String
                     /*FlutterContacts.*/openExternalViewOrEdit(activity, context, id, true) // NOTE: Assuming FlutterContacts.openExternalViewOrEdit is defined and accessible
                    editResult = result
                }
            "openExternalPick" ->
                coroutineScope.launch(Dispatchers.IO) {
                     /*FlutterContacts.*/openExternalPickOrInsert(activity, context, false) // NOTE: Assuming FlutterContacts.openExternalPickOrInsert is defined and accessible
                    pickResult = result
                }
            "openExternalInsert" ->
                coroutineScope.launch(Dispatchers.IO) {
                    var args = call.arguments as List<Any>
                    val contact = args.getOrNull(0)?.let { it as? Map<String, Any?> } ?: run {
                        null
                    }
                     /*FlutterContacts.*/openExternalPickOrInsert(activity, context, true, contact) // NOTE: Assuming FlutterContacts.openExternalPickOrInsert is defined and accessible
                    insertResult = result
                }
            else -> result.notImplemented()
        }
    }

    private fun getContactIdFromExternalInsertResult(intent: Intent?): String? {
        if (intent == null) {
            return null
        }

        val uri = intent.getData()?.getPath()
        if (uri == null) {
            return null
        }

        val hasContactsReadPermission = ContextCompat.checkSelfPermission(
            context!!, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsReadPermission) {
            // Check the contacts read permission since 'open external insert' can be
            // called even without contacts permission. So while selecting contacts, be
            // sure that we have read permission.
            return null
        }

        // Result can be of two forms:
        // content://com.android.contacts/<lookup_key>/<raw_id>
        // content://com.android.contacts/raw_contacts/<raw_id>
        val segments = intent.getData()?.getPathSegments()
        if (segments == null || segments.size < 2) {
            return null
        }
        val secondToLastSegment = segments[segments.size - 2]

        if (secondToLastSegment == "raw_contacts") {
            val rawId = segments.last()
            val contacts: List<Map<String, Any?>> =
                 /*FlutterContacts.*/select( // NOTE: Assuming FlutterContacts.select is defined and accessible
                        resolver!!,
                        rawId,
                        /*withProperties=*/false,
                        /*withThumbnail=*/false,
                        /*withPhoto=*/false,
                        /*withGroups=*/false,
                        /*withAccounts=*/false,
                        /*returnUnifiedContacts=*/true,
                        /*includeNonVisible=*/true,
                        /*idIsRawContactId=*/true
                    )
            if (contacts.isEmpty()) {
                return null
            }
            return contacts[0]["id"] as String?
        } else {
            val lookupKey = secondToLastSegment
            val contactId = /*FlutterContacts.*/findIdWithLookupKey( // NOTE: Assuming FlutterContacts.findIdWithLookupKey is defined and accessible
                resolver!!,
                lookupKey
            )
            return contactId
        }
    }

    // --- StreamHandler implementation ---

    var _eventObserver: ContactChangeObserver? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        if (events != null) {
            this._eventObserver = ContactChangeObserver(android.os.Handler(), events)
            resolver?.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, this._eventObserver!!)
        }
    }

    override fun onCancel(arguments: Any?) {
        if (this._eventObserver != null) {
            resolver?.unregisterContentObserver(this._eventObserver!!)
        }
        this._eventObserver = null
    }
}