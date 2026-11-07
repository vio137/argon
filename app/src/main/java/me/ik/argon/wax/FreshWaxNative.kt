package me.ik.argon.wax
/** NEW ABI/library name. Never load the unmatched old V10/V11 binary with this class. */
object FreshWaxNative {
    init { System.loadLibrary("waxfresh") }
    @JvmStatic external fun validate(profile:ByteArray):Int
    @JvmStatic external fun create(output:ByteArray):Long
    @JvmStatic external fun connect(id:Long,profile:ByteArray,millis:Int):Int
    @JvmStatic external fun block(id:Long):Int
    @JvmStatic external fun close(id:Long):Int
}
class FreshWaxSession private constructor(private val id:Long,
    override val address:String,override val user:String,override val password:String):WaxEnabledSession {
    override fun toString()="FreshWaxSession(redacted)"
    override fun block(){check(FreshWaxNative.block(id)==0){"waX block failed"}}
    /** Call on background thread, transferred buffer ownership; code0 means peer authenticated. */
    fun connect(profile:ByteArray):Int=try{FreshWaxNative.connect(id,profile,15000)}finally{profile.fill(0)}
    fun close(){FreshWaxNative.close(id)}
    companion object {
        fun createBlocked():FreshWaxSession {
            val output=ByteArray(256);var id=0L
            try {
                id=FreshWaxNative.create(output);check(id>0){"waX session creation failed"}
                val end=output.indexOf(0);check(end>0)
                val p=output.decodeToString(0,end).split('\n');require(p.size==4)
                val port=Regex("127\\.0\\.0\\.1:([0-9]{1,5})").matchEntire(p[0])?.groupValues?.get(1)?.toIntOrNull()
                require(port in 1..65535&&p[1]=="argon"&&Regex("[0-9a-f]{64}").matches(p[2]))
                return FreshWaxSession(id,p[0],p[1],p[2])
            }catch(e:Exception){if(id>0)FreshWaxNative.close(id);throw e}finally{output.fill(0)}
        }
    }
}
