package me.ik.argon.wax
import android.content.Context
class FreshWaxManagedStore(context:Context):WaxManagedStore {
    private val intent=FreshWaxIntent(context)
    private val profile=WaxSecureProfile(FreshWaxKey(),FreshWaxBlob(context,"wax-fresh-profile.enc"))
    // Same Keystore key + GCM mechanism as the profile, purpose-separated blob and AAD.
    private val account=WaxSecureProfile(FreshWaxKey(),FreshWaxBlob(context,"wax-fresh-account.enc"),"Argon.waX.fresh-account.v1")
    override fun consented()=intent.hasAcceptedNotice()
    override fun hasProfile():Boolean = try {profile.load()?.let{it.fill(0);true}?:false}catch(_:Exception){false}
    override fun load()=profile.load()
    override fun saveProfile(bytes:ByteArray){
        require(FreshWaxNative.validate(bytes)==0){"Unsupported profile format"}
        profile.save(bytes)
    }
    override fun deleteProfile(){profile.delete()}
    override fun saveMode(enabled:Boolean)=intent.saveExplicitMode(enabled)
    override fun hasAccount():Boolean = try {account.load()?.let{it.fill(0);true}?:false}catch(_:Exception){false}
    override fun loadAccountJson()=account.load()
    override fun saveAccountJson(bytes:ByteArray){account.save(bytes)}
    override fun deleteAccount(){account.delete()}
}
class FreshWaxManagedSession(private val session:FreshWaxSession):WaxManagedSession {
    override fun connect(profile:ByteArray)=session.connect(profile)
    override fun block(){session.block()}
}
