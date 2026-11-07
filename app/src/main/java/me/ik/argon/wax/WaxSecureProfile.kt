package me.ik.argon.wax
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface WaxPrivateBlob {
    /** null only for a genuinely absent file. Bounds/read failures must throw. */
    fun read(limit:Int):ByteArray?
    /** Must copy synchronously and replace atomically in private no-backup storage. */
    fun replace(bytes:ByteArray)
    fun delete()
}
fun interface WaxSecretKey {fun get():SecretKey}
/** NEW encryption store. No registration/export/share/enable behavior. aadLabel binds a
 * blob to its purpose: ciphertext written under one label cannot be loaded as another. */
class WaxSecureProfile(private val key:WaxSecretKey,private val blob:WaxPrivateBlob,aadLabel:String="Argon.waX.fresh-profile.v1"){
    private val aad=aadLabel.toByteArray(Charsets.US_ASCII)
    fun save(profile:ByteArray){
        require(profile.size in 1..16384)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key.get());c.updateAAD(aad)
        val iv=c.iv;require(iv.size==12)
        val ciphertext=c.doFinal(profile)
        val packet=byteArrayOf(1)+iv+ciphertext
        try{blob.replace(packet)}finally{packet.fill(0);ciphertext.fill(0);iv.fill(0)}
    }
    fun load():ByteArray?{
        val packet=blob.read(16413)?:return null
        try{
            require(packet.size in 30..16413 && packet[0]==1.toByte())
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,key.get(),GCMParameterSpec(128,packet,1,12));c.updateAAD(aad)
            return c.doFinal(packet,13,packet.size-13)
        }finally{packet.fill(0)}
    }
    fun delete(){blob.delete()}
}
/** Format parsing does not authenticate who wrote a preference. Private storage is required.
 * Missing after marker, corrupt/unreadable/unknown record all stop startup. */
object WaxIntentCodec {
    private val on="WAX-FRESH-1\nON\nCONSENT-1\n".toByteArray(Charsets.US_ASCII)
    private val off="WAX-FRESH-1\nOFF\nCONSENT-1\n".toByteArray(Charsets.US_ASCII)
    fun encode(enabled:Boolean)=if(enabled)on.copyOf() else off.copyOf()
    fun decode(data:ByteArray?,marker:Boolean):WaxStoredIntent=when{
        data==null -> if(marker)WaxStoredIntent.Invalid else WaxStoredIntent.Off
        data.contentEquals(on)->WaxStoredIntent.Enabled
        data.contentEquals(off)->WaxStoredIntent.Off
        else->WaxStoredIntent.Invalid
    }
}
