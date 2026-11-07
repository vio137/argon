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
        "Automatic WARP account setup is not implemented; importing a profile does not complete zero-setup WARP."
    fun show(context:Context,state:WaxManagementState,onAction:(WaxAction)->Unit):AlertDialog {
        val density=context.resources.displayMetrics.density
        fun dp(n:Int)=(density*n).toInt()
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16))}
        body.addView(TextView(context).apply{text=WaxManagement.status(state);textSize=18f;setPadding(0,0,0,dp(16))})
        body.addView(TextView(context).apply{text=if(state.hasProfile)"Profile stored privately" else "No profile stored";textSize=15f})
        val actions=listOf(
            "Review privacy and setup" to WaxAction.REVIEW_NOTICE,
            "Import WireGuard profile" to WaxAction.IMPORT_PROFILE,
            "Create WARP account (not available yet)" to WaxAction.CREATE_WARP_ACCOUNT,
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
    fun explainRegistration(context:Context)=AlertDialog.Builder(context).setTitle("WARP setup is unfinished")
        .setMessage("An officially supported way to register WARP inside Argon has not been established. No account will be created and no terms accepted by this control. Profile import is a separate, partial option.")
        .setPositiveButton("Close",null).show()
}
