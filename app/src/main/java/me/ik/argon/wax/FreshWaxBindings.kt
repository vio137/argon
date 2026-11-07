package me.ik.argon.wax
import android.content.Context
class FreshWaxManagedStore(context:Context):WaxManagedStore {
    private val intent=FreshWaxIntent(context)
    private val profile=WaxSecureProfile(FreshWaxKey(),FreshWaxBlob(context,"wax-fresh-profile.enc"))
    override fun consented()=intent.hasAcceptedNotice()
    override fun hasProfile():Boolean = try {profile.load()?.let{it.fill(0);true}?:false}catch(_:Exception){false}
    override fun load()=profile.load()
    override fun saveProfile(bytes:ByteArray){
        require(FreshWaxNative.validate(bytes)==0){"Unsupported profile format"}
        profile.save(bytes)
    }
    override fun deleteProfile(){profile.delete()}
    override fun saveMode(enabled:Boolean)=intent.saveExplicitMode(enabled)
}
class FreshWaxManagedSession(private val session:FreshWaxSession):WaxManagedSession {
    override fun connect(profile:ByteArray)=session.connect(profile)
    override fun block(){session.block()}
}
