package me.ik.argon.wax

/** State is read from private durable storage, never from intent extras. */
sealed interface WaxStoredIntent {
    data object Off : WaxStoredIntent
    data object Enabled : WaxStoredIntent
    data object Invalid : WaxStoredIntent
}
fun interface WaxIntentReader { fun read(): WaxStoredIntent }

/** Stable native-session contract. Production binding must use session_create/connect/block,
 * not per-tunnel proxy handles. No direct dial exists in an enabled session. */
interface WaxEnabledSession {
    val address: String
    val user: String
    val password: String
    fun block()
}
fun interface WaxSessionFactory { fun createBlocked(): WaxEnabledSession }

sealed interface WaxStartupPlan {
    /** No native library was loaded. Original engine preference list stays unchanged. */
    data object Stock : WaxStartupPlan
    /** Caller may initialize Servo with these additional prefs. Session remains blocked. */
    class Enabled internal constructor(internal val session: WaxEnabledSession) : WaxStartupPlan {
        fun additionalPrefValues(): List<String> {
            // Native credentials are generated unreserved ASCII. Never log these strings.
            val uri="http://${session.user}:${session.password}@${session.address}"
            return listOf("network_http_proxy_uri=$uri", "network_https_proxy_uri=$uri",
                "network_http_no_proxy=", "network_require_proxy=true")
        }
        override fun toString()="WaxStartupPlan.Enabled(credentials redacted, initially blocked)"
    }
    /** Do NOT initialize Servo, auto-open a page or retry with Stock. No fallback. */
    data class Stop(val reason: Reason) : WaxStartupPlan
    enum class Reason { INVALID_INTENT, INTENT_READ_FAILED, NATIVE_UNAVAILABLE, INVALID_LISTENER, PROCESS_STATE_CHANGED }
}

/** Process-owned startup holder. Construct once at Application/process scope, NOT per Activity.
 * It creates a native listener only for Enabled. All returned plans are immutable per process.
 * MainActivity must obtain this BEFORE constructing any Servo JNI wrapper or auto-loading URLs.
 * This is not wired to Argon yet and does not prove Android lifecycle enforcement.
 */
class WaxStartup(private val reader: WaxIntentReader, private val nativeFactory: WaxSessionFactory) {
    private var plan: WaxStartupPlan?=null
    private var original: WaxStoredIntent?=null

    @Synchronized fun beforeEngineInit(): WaxStartupPlan {
        val intent=try { reader.read() } catch(_:Exception) {
            return WaxStartupPlan.Stop(WaxStartupPlan.Reason.INTENT_READ_FAILED).also{plan=it}
        }
        val existing=plan
        if(existing!=null) {
            if(original!=null && intent!=original) {
                runCatching { (existing as? WaxStartupPlan.Enabled)?.session?.block() }
                // Active stock engine cannot be safely torn down by this helper. App must
                // terminate/close the process before claiming a new routing mode applied.
                return WaxStartupPlan.Stop(WaxStartupPlan.Reason.PROCESS_STATE_CHANGED).also { plan=it }
            }
            return existing
        }
        original=intent
        val result=when(intent) {
            WaxStoredIntent.Off -> WaxStartupPlan.Stock
            WaxStoredIntent.Invalid -> WaxStartupPlan.Stop(WaxStartupPlan.Reason.INVALID_INTENT)
            WaxStoredIntent.Enabled -> {
                val session=try { nativeFactory.createBlocked() } catch(_:LinkageError) { null }
                    catch(_:Exception) { null }
                if(session==null) WaxStartupPlan.Stop(WaxStartupPlan.Reason.NATIVE_UNAVAILABLE)
                else if(!valid(session)) {
                    runCatching { session.block() }
                    WaxStartupPlan.Stop(WaxStartupPlan.Reason.INVALID_LISTENER)
                } else WaxStartupPlan.Enabled(session)
            }
        }
        plan=result
        return result
    }
    private fun valid(s:WaxEnabledSession):Boolean {
        val m=Regex("127\\.0\\.0\\.1:([0-9]{1,5})").matchEntire(s.address) ?: return false
        return m.groupValues[1].toIntOrNull() in 1..65535 && s.user=="argon" &&
            Regex("[0-9a-f]{64}").matches(s.password)
    }
}
