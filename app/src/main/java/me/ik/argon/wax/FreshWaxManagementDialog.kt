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
    fun show(context:Context,state:WaxManagementState,onAction:(WaxAction)->Unit):AlertDialog {
        val density=context.resources.displayMetrics.density
        fun dp(n:Int)=(density*n).toInt()
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16))}
        body.addView(TextView(context).apply{text=WaxManagement.status(state);textSize=18f;setPadding(0,0,0,dp(16))})
        body.addView(TextView(context).apply{text=if(state.hasProfile)"Profile stored privately" else "No profile stored";textSize=15f})
        val actions=listOf(
            "Review privacy and setup" to WaxAction.REVIEW_NOTICE,
            "Import WireGuard profile" to WaxAction.IMPORT_PROFILE,
            (if(state.hasAccount)"Resume WARP account setup" else "Create free WARP account") to WaxAction.CREATE_WARP_ACCOUNT,
            (if(state.processEnabled)"Connect" else "Enable after restart") to
                (if(state.processEnabled)WaxAction.CONNECT else WaxAction.REQUEST_ENABLE),
            "Disconnect and block" to WaxAction.BLOCK,
            "Turn off after restart" to WaxAction.REQUEST_DISABLE,
            "Delete stored profile" to WaxAction.DELETE_PROFILE,
        )
        val dialog=AlertDialog.Builder(context).setTitle("waX").setView(ScrollView(context).apply{addView(body)})
            .setNegativeButton("Close",null).create()
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
                        .setMessage("Current waX connections will be blocked before the encrypted profile is removed. The routing preference stays unchanged.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->onAction(action)}.show()
                }else onAction(action)
            }
        },ViewGroup.LayoutParams(-1,-2))
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
    fun showRegistrationDisclosure(context:Context,resume:Boolean,onAgree:()->Unit):AlertDialog {
        val text=StringBuilder()
        if(resume)text.append("An account was already created on this device but setup did not finish. Continuing completes setup with the SAME account - no new account is created.\n\n")
        text.append("Argon is not affiliated with Cloudflare, and Cloudflare does not officially support unofficial WARP apps. Cloudflare has blocked such apps before and may decline or block this setup; if that happens it fails and Argon does not try to work around it.\n\n")
        text.append("Tapping \"Agree and create account\" contacts Cloudflare's service, accepts Cloudflare's Application Terms of Service and Privacy Policy, and creates a free WARP account for this device:\n\n")
        text.append("https://www.cloudflare.com/application/terms/\nhttps://www.cloudflare.com/application/privacypolicy/\n\n")
        text.append("The account key, token and profile are stored encrypted on this device and never shown. This step only creates the account: routing stays off until you separately enable and connect, and WARP is not designed to make you anonymous.")
        return AlertDialog.Builder(context).setTitle(if(resume)"Finish WARP account setup" else "Create a WARP account")
            .setMessage(text.toString()).setNegativeButton("Not now",null)
            .setPositiveButton(if(resume)"Finish setup" else "Agree and create account"){_,_->onAgree()}.show()
    }
}
