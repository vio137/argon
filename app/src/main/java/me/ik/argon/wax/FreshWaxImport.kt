package me.ik.argon.wax
import android.content.Context
import android.content.Intent
import android.net.Uri
/** Import only. Does not accept notice, change routing intent, or start native networking. */
object FreshWaxImport {
    fun picker()=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
        addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    /** Only call for the URI returned by the app's own picker result. No arbitrary intent extras.
     * No persistent URI grant needed; no profile values in exception messages/logs. */
    fun read(context:Context,uri:Uri):ByteArray {
        require(uri.scheme=="content") {"Use the document picker"}
        return context.contentResolver.openInputStream(uri)?.use{input->
            val storage=ByteArray(16385)
            try{
                var used=0
                while(used<storage.size){val n=input.read(storage,used,storage.size-used);if(n<0)break;used+=n}
                require(used in 1..16384){"Unsupported profile size"}
                storage.copyOf(used)
            }finally{storage.fill(0)}
        }?:error("Couldn't read selected profile")
    }
}
