package me.ik.argon.wax

import android.app.Dialog
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.ik.argon.ArgonTheme

/** Production and host renders share these themed, scrollable dialog contents. */
object FreshWaxManagementDialog {
    private const val notice = "When enabled, page connections and DNS use your profile's provider. For WARP profiles this is Cloudflare. Local/private-network sites are blocked. Disconnected means browser connections stay blocked, not a direct-network fallback. Turning waX on or off needs Argon to close and reopen. Peer connection is not verified internet access. WARP accounts are unofficial and unsupported by Cloudflare; importing a profile is a separate option."

    private fun host(context: Context, content: @Composable (() -> Unit) -> Unit): Dialog {
        val activity = context as ComponentActivity
        val dialog = Dialog(context)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ArgonTheme { content { dialog.dismiss() } } }
        }
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .92f).toInt(), -2)
        return dialog
    }

    @Composable
    private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }

    @Composable
    fun ManagementContent(state: WaxManagementState, onAction: (WaxAction) -> Unit, onClose: () -> Unit) {
        Panel("waX") {
            Text(WaxManagement.status(state), fontSize = 18.sp)
            Text((if (state.hasProfile) "Profile stored privately" else "No profile stored") +
                (if (state.hasAccount) ", account stored privately" else "") +
                (if (state.setupUnknown) ", previous setup outcome unknown" else ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val actions = listOf(
                "Review privacy and setup" to WaxAction.REVIEW_NOTICE,
                "Import WireGuard profile" to WaxAction.IMPORT_PROFILE,
                (if (state.setupUnknown) "Review WARP account setup" else if (state.hasAccount) "Resume WARP account setup" else "Create free WARP account") to WaxAction.CREATE_WARP_ACCOUNT,
                (if (state.processEnabled) "Connect" else "Enable after restart") to (if (state.processEnabled) WaxAction.CONNECT else WaxAction.REQUEST_ENABLE),
                "Disconnect and block" to WaxAction.BLOCK,
                "Turn off after restart" to WaxAction.REQUEST_DISABLE,
                "Delete stored profile" to WaxAction.DELETE_PROFILE,
            )
            for ((label, action) in actions) {
                val enabled = when (action) {
                    WaxAction.CONNECT -> state.hasProfile && state.consentAccepted && !state.restartRequired && state.connection != WaxConnection.CONNECTING
                    WaxAction.BLOCK -> state.processEnabled
                    WaxAction.DELETE_PROFILE -> state.hasProfile
                    WaxAction.REQUEST_DISABLE -> state.processEnabled || state.wantedEnabled
                    WaxAction.CREATE_WARP_ACCOUNT -> !state.hasProfile
                    else -> true
                }
                OutlinedButton(onClick = { onAction(action) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }

    fun show(context: Context, state: WaxManagementState, onAction: (WaxAction) -> Unit): Dialog = host(context) { close ->
        ManagementContent(state, { action ->
            if (action == WaxAction.DELETE_PROFILE) {
                host(context) { dismiss -> ConfirmationContent("Delete waX profile?", "Current waX connections will be blocked, then the encrypted profile and any stored account credentials are removed from THIS device only. A WARP account created earlier stays on Cloudflare (remote account deletion is not supported). The routing preference stays unchanged.", "Delete", { dismiss(); close(); onAction(action) }, dismiss) }
            } else { close(); onAction(action) }
        }, close)
    }

    @Composable
    fun ConfirmationContent(title: String, text: String, agree: String, onAgree: () -> Unit, onCancel: () -> Unit) {
        Panel(title) {
            Text(text, fontSize = 15.sp, lineHeight = 21.sp)
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(agree) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }

    fun showNotice(context: Context, onAccept: () -> Unit): Dialog = host(context) { close ->
        ConfirmationContent("Set up waX", notice, "Accept notice", { close(); onAccept() }, close)
    }

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
        if(setupUnknown)text.append("An earlier setup attempt ended with an unknown result: an account may remain on Cloudflare that Argon cannot recover or delete. Continuing may create a duplicate account.\n\n")
        else if(resume)text.append("An account was already created on this device but setup did not finish. Continuing completes setup with the SAME account - no new account is created.\n\n")
        text.append("Argon is not affiliated with Cloudflare, and Cloudflare does not officially support unofficial WARP apps. Cloudflare has blocked such apps before and may decline or block this setup; if that happens it fails and Argon does not try to work around it.\n\n")
        if(resume && !setupUnknown) text.append("Finishing setup contacts Cloudflare to retrieve configuration for the stored account. No new account is created and no new terms-acceptance request is sent. The provider's Application Terms and Privacy Policy are linked below.\n\n")
        else text.append("Tapping the agree button contacts Cloudflare's service, accepts Cloudflare's Application Terms of Service and Privacy Policy, and requests a free WARP account for this device. Read both linked policies below.\n\n")
        text.append("The account key, token and profile are stored encrypted on this device and never shown. Routing stays off until you separately enable and connect. An account or stored profile is not verified internet access, and WARP is not designed to make you anonymous.")
        return text.toString()
    }
    fun registrationDisclosureTitle(resume:Boolean,setupUnknown:Boolean):String=
        if(setupUnknown)"Previous setup outcome unknown" else if(resume)"Finish WARP account setup" else "Create a WARP account"

    @Composable
    fun RegistrationContent(resume: Boolean, setupUnknown: Boolean, onAgree: () -> Unit, onCancel: () -> Unit, onOpenLink: (String) -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(registrationDisclosureTitle(resume, setupUnknown), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(registrationDisclosureText(resume, setupUnknown), fontSize = 15.sp, lineHeight = 21.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onOpenLink("https://www.cloudflare.com/application/terms/") }) { Text("Application Terms") }
                    TextButton(onClick = { onOpenLink("https://www.cloudflare.com/application/privacypolicy/") }) { Text("Privacy Policy") }
                }
                Text("Scroll to review the disclosure and policy links", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (setupUnknown) "Agree and create a new account" else if (resume) "Finish setup" else "Agree and create account")
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }

    fun showRegistrationDisclosure(context: Context, resume: Boolean, setupUnknown: Boolean, onAgree: () -> Unit): Dialog = host(context) { close ->
        RegistrationContent(resume, setupUnknown, { close(); onAgree() }, close, { url ->
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        })
    }
}
