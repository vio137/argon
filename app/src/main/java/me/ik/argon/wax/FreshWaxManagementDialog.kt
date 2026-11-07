package me.ik.argon.wax

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import android.view.ViewGroup

/** NEW isolated settings surface, not wired/rendered. Adapt visuals to Argon's current
 * Vivaldi-inspired design system before shipping. Callbacks must marshal I/O off UI thread.
 * No URI/profile/key fields are exposed in this screen or error text. */
object FreshWaxManagementDialog {
    private const val notice="When enabled, page connections and DNS use your profile's provider. " +
        "For WARP profiles this is Cloudflare. Local/private-network sites are blocked. " +
        "Disconnected means browser connections stay blocked, not a direct-network fallback. " +
        "Turning waX on or off needs Argon to close and reopen. Peer connection is not verified internet access. " +
        "WARP accounts are unofficial and unsupported by Cloudflare; importing a profile is a separate option. "
    /** Body content, extracted so host rendering (Paparazzi) inspects the exact shipped UI. */
    fun buildBody(context:Context,state:WaxManagementState,onAction:(WaxAction)->Unit):LinearLayout {
        val density=context.resources.displayMetrics.density
        fun dp(n:Int)=(density*n).toInt()
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16))}
        body.addView(TextView(context).apply{text=WaxManagement.status(state);textSize=18f;setPadding(0,0,0,dp(16))})
        body.addView(TextView(context).apply{
            text=(if(state.hasProfile)"Profile stored privately" else "No profile stored")+
                (if(state.hasAccount)", account stored privately" else"")+
                (if(state.setupUnknown)", previous setup outcome unknown" else"")
            textSize=15f
        })
        val actions=listOf(
            "Review privacy and setup" to WaxAction.REVIEW_NOTICE,
            "Import WireGuard profile" to WaxAction.IMPORT_PROFILE,
            (if(state.setupUnknown)"Review WARP account setup" else if(state.hasAccount)"Resume WARP account setup" else "Create free WARP account") to WaxAction.CREATE_WARP_ACCOUNT,
            (if(state.processEnabled)"Connect" else "Enable after restart") to
                (if(state.processEnabled)WaxAction.CONNECT else WaxAction.REQUEST_ENABLE),
            "Disconnect and block" to WaxAction.BLOCK,
            "Turn off after restart" to WaxAction.REQUEST_DISABLE,
            "Delete stored profile" to WaxAction.DELETE_PROFILE,
        )
        for((label,action)in actions)body.addView(Button(context).apply{
            text=label;isAllCaps=false;minHeight=dp(48)
            isEnabled=when(action){
                WaxAction.CONNECT->state.hasProfile&&state.consentAccepted&&!state.restartRequired&&state.connection!=WaxConnection.CONNECTING
                WaxAction.BLOCK->state.processEnabled
                WaxAction.DELETE_PROFILE->state.hasProfile
                WaxAction.REQUEST_DISABLE->state.processEnabled||state.wantedEnabled
                WaxAction.CREATE_WARP_ACCOUNT->!state.hasProfile
                else->true
            }
            setOnClickListener{
                if(action==WaxAction.DELETE_PROFILE){
                    AlertDialog.Builder(context).setTitle("Delete waX profile?")
                        .setMessage("Current waX connections will be blocked, then the encrypted profile and any stored account credentials are removed from THIS device only. A WARP account created earlier stays on Cloudflare (remote account deletion is not supported). The routing preference stays unchanged.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->onAction(action)}.show()
                }else onAction(action)
            }
        },ViewGroup.LayoutParams(-1,-2))
        return body
    }
    fun show(context:Context,state:WaxManagementState,onAction:(WaxAction)->Unit):AlertDialog {
        val body=buildBody(context,state,onAction)
        val dialog=AlertDialog.Builder(context).setTitle("waX").setView(ScrollView(context).apply{addView(body)})
            .setNegativeButton("Close",null).create()
        dialog.show();return dialog
    }
    /** The host must durably save explicit notice acceptance, not activate traffic here. */
    fun showNotice(context:Context,onAccept:()->Unit)=AlertDialog.Builder(context)
        .setTitle("Set up waX").setMessage(notice).setNegativeButton("Not now",null)
        .setPositiveButton("Accept notice"){_,_->onAccept()}.show()

    /**
     * Provider disclosure shown BEFORE the account-creation network call. The positive
     * button is the one and only consent: the registration request itself carries the
     * terms-acceptance timestamp, so this tap is what accepts the terms. Nothing is sent
     * when the dialog is dismissed. Sources for the support status: Cloudflare Application
     * Terms (cloudflare.com/application/terms/, updated 2024-10-18), wgcf project docs
     * (github.com/ViRb3/wgcf - unofficial client), and Cloudflare's 2022 server-side block
     * of unofficial clients (github.com/ViRb3/wgcf/issues/158).
     */
    /** Disclosure body text, extracted so host renders inspect the exact shipped copy. */
    fun registrationDisclosureText(resume:Boolean,setupUnknown:Boolean):String {
        val text=StringBuilder()
        if(setupUnknown)text.append("An earlier setup attempt ended with an unknown result: an account MAY have been created on Cloudflare that Argon cannot recover. Continuing creates a NEW account; the unrecoverable one, if it exists, simply stays unused on the free tier.\n\n")
        else if(resume)text.append("An account was already created on this device but setup did not finish. Continuing completes setup with the SAME account - no new account is created.\n\n")
        text.append("Argon is not affiliated with Cloudflare, and Cloudflare does not officially support unofficial WARP apps. Cloudflare has blocked such apps before and may decline or block this setup; if that happens it fails and Argon does not try to work around it.\n\n")
        text.append("Tapping the agree button contacts Cloudflare's service, accepts Cloudflare's Application Terms of Service and Privacy Policy, and creates a free WARP account for this device:\n\n")
        text.append("https://www.cloudflare.com/application/terms/\nhttps://www.cloudflare.com/application/privacypolicy/\n\n")
        text.append("The account key, token and profile are stored encrypted on this device and never shown. This step only creates the account: routing stays off until you separately enable and connect, the connection itself is not verified internet access, and WARP is not designed to make you anonymous.")
        return text.toString()
    }
    fun registrationDisclosureTitle(resume:Boolean,setupUnknown:Boolean):String=
        if(setupUnknown)"Previous setup outcome unknown" else if(resume)"Finish WARP account setup" else "Create a WARP account"

    fun showRegistrationDisclosure(context:Context,resume:Boolean,setupUnknown:Boolean,onAgree:()->Unit):AlertDialog {
        return AlertDialog.Builder(context).setTitle(registrationDisclosureTitle(resume,setupUnknown))
            .setMessage(registrationDisclosureText(resume,setupUnknown)).setNegativeButton("Not now",null)
            .setPositiveButton(if(setupUnknown)"Agree and create a new account" else if(resume)"Finish setup" else "Agree and create account"){_,_->onAgree()}.show()
    }
}
