package me.ik.argon.wax
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Fresh app-private, excluded-from-backup storage. Compile/device checks pending. */
class FreshWaxBlob(context:Context,name:String):WaxPrivateBlob {
    private val dir=context.noBackupFilesDir
    private val file=AtomicFile(File(dir,name))
    @Synchronized override fun read(limit:Int):ByteArray?=try{
        file.openRead().use{input->
            val out=ByteArrayOutputStream();val block=ByteArray(1024)
            try{while(out.size()<=limit){val n=input.read(block,0,minOf(1024,limit+1-out.size()));if(n<0)break;out.write(block,0,n)}
                require(out.size()<=limit);out.toByteArray()
            }finally{block.fill(0)}
        }
    }catch(e:FileNotFoundException){if(file.baseFile.exists())throw e else null}
    @Synchronized override fun replace(bytes:ByteArray){
        val stream=file.startWrite()
        try{stream.write(bytes);stream.fd.sync();file.finishWrite(stream);syncDir()}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
    @Synchronized override fun delete(){file.delete();syncDir()}
    private fun syncDir(){val fd=Os.open(dir.absolutePath,OsConstants.O_RDONLY,0);try{Os.fsync(fd)}finally{Os.close(fd)}}
}
class FreshWaxKey:WaxSecretKey {
    @Synchronized override fun get():SecretKey{
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val alias="argon.wax.fresh.profile.v1"
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").run{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());generateKey()
        }
    }
}
class FreshWaxIntent(context:Context):WaxIntentReader {
    private val dir=context.noBackupFilesDir
    private val marker=File(dir,"wax-fresh-initialized")
    private val blob=FreshWaxBlob(context,"wax-fresh-intent")
    @Synchronized override fun read():WaxStoredIntent=try{WaxIntentCodec.decode(blob.read(64),marker.exists())}catch(_:Exception){WaxStoredIntent.Invalid}
    @Synchronized fun hasAcceptedNotice():Boolean=try{
        val bytes=blob.read(64)?:return false
        WaxIntentCodec.decode(bytes,marker.exists())!=WaxStoredIntent.Invalid
    }catch(_:Exception){false}
    /** Notice accepted while still OFF. Never silently disables enabled/corrupt mode. */
    @Synchronized fun acceptNoticeOff():Boolean {
        if(read()!=WaxStoredIntent.Off)return false
        return saveExplicitMode(false)
    }
    /** Call only after an explicit app notice/mode action. Import alone must NOT call this. */
    @Synchronized fun saveExplicitMode(enabled:Boolean):Boolean=try{
        FileOutputStream(marker,true).use{it.fd.sync()}
        val fd=Os.open(dir.absolutePath,OsConstants.O_RDONLY,0);try{Os.fsync(fd)}finally{Os.close(fd)}
        blob.replace(WaxIntentCodec.encode(enabled));true
    }catch(_:Exception){false}
}
