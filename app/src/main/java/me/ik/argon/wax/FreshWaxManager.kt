package me.ik.argon.wax

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Store/native interfaces keep management testable. All I/O off the UI thread. */
interface WaxManagedStore {
    fun consented():Boolean
    fun hasProfile():Boolean
    /** Owned mutable bytes; caller must wipe. */
    fun load():ByteArray?
    fun saveProfile(bytes:ByteArray)
    fun deleteProfile()
    /** Explicit mode intent only. Must commit durably or return false. */
    fun saveMode(enabled:Boolean):Boolean
}
interface WaxManagedSession {
    fun connect(profile:ByteArray):Int
    fun block()
}
sealed interface WaxManagerResult {
    data object Done:WaxManagerResult
    data object NeedsConsent:WaxManagerResult
    data object NeedsProfile:WaxManagerResult
    data object NeedsColdReopen:WaxManagerResult
    data object Error:WaxManagerResult
    /** Code only, not arbitrary native exception messages. */
    data class NativeStatus(val code:Int):WaxManagerResult
}

/**
 * Manager for an enabled-process session ONLY, not a replacement for startup gate.
 * Does not own/destroy process listener; Activity lifetimes must not close it.
 * UI observes coarse state only, no profile/key/URI fields. Observer executes on work executor.
 * Turning routing mode on/off is a cold-process request, never applied silently to live Servo.
 */
class FreshWaxManager(
    private val store:WaxManagedStore,
    private val session:WaxManagedSession?,
    private val enabledProcess:Boolean,
    private val notify:(WaxConnection)->Unit={},
    private val worker:ExecutorService=Executors.newSingleThreadExecutor(),
) {
    private val generation=AtomicLong()
    @Volatile var connection:WaxConnection=if(enabledProcess)WaxConnection.BLOCKED else WaxConnection.OFF
        private set
    @Volatile private var pendingModeChange=false

    /** Profile import stores only. It does NOT grant notice consent or activate routing.
     * Transfer array ownership; validation/preview should run before user confirms import. */
    fun importProfile(profile:ByteArray):Future<WaxManagerResult> = worker.submit<WaxManagerResult> {
        try { store.saveProfile(profile);WaxManagerResult.Done }
        catch(_:Exception){WaxManagerResult.Error}finally{profile.fill(0)}
    }

    fun connect():Future<WaxManagerResult> {
        val ticket=generation.get()
        return worker.submit<WaxManagerResult> {
            if(pendingModeChange||!enabledProcess||session==null)return@submit WaxManagerResult.NeedsColdReopen
            if(!store.consented())return@submit WaxManagerResult.NeedsConsent
            if(ticket!=generation.get())return@submit WaxManagerResult.Done
            val bytes=try{store.load()}catch(_:Exception){return@submit WaxManagerResult.Error}
                ?:return@submit WaxManagerResult.NeedsProfile
            try {
                connection=WaxConnection.CONNECTING;notify(connection)
                val status=session.connect(bytes)
                if(ticket!=generation.get()) {
                    session.block();connection=WaxConnection.BLOCKED;notify(connection)
                    return@submit WaxManagerResult.Done
                }
                connection=if(status==0)WaxConnection.PEER_CONNECTED else WaxConnection.BLOCKED
                notify(connection)
                if(status==0)WaxManagerResult.Done else WaxManagerResult.NativeStatus(status)
            }catch(_:Exception){
                runCatching{session?.block()};connection=WaxConnection.BLOCKED;notify(connection)
                WaxManagerResult.Error
            }finally{bytes.fill(0)}
        }
    }

    /** User disconnect = block, not direct fallback. Cancellation happens outside worker
     * so it can interrupt the native handshake waiting on that worker. */
    fun disconnect():Future<WaxManagerResult> {
        generation.incrementAndGet()
        val blocked=runCatching{session?.block()}.isSuccess
        return worker.submit<WaxManagerResult>{
            connection=if(enabledProcess)WaxConnection.BLOCKED else WaxConnection.OFF
            notify(connection)
            if(blocked)WaxManagerResult.Done else WaxManagerResult.Error
        }
    }

    /** Caller MUST first show explicit confirmation. Does not delete consent/mode intent. */
    fun deleteConfirmedProfile():Future<WaxManagerResult> {
        generation.incrementAndGet();val blocked=runCatching{session?.block()}.isSuccess
        return worker.submit<WaxManagerResult>{
            if(!blocked)return@submit WaxManagerResult.Error
            connection=if(enabledProcess)WaxConnection.BLOCKED else WaxConnection.OFF;notify(connection)
            try{store.deleteProfile();WaxManagerResult.Done}catch(_:Exception){WaxManagerResult.Error}
        }
    }

    /** Does NOT stop an existing OFF Servo engine. UI must say "restart pending", not ON.
     * Core owns close/reopen; no activity-recreate or guaranteed auto-relaunch promise. */
    fun requestExplicitMode(enabled:Boolean):Future<WaxManagerResult> {
        generation.incrementAndGet();runCatching{session?.block()}
        return worker.submit<WaxManagerResult>{
            if(enabled&&!store.consented())return@submit WaxManagerResult.NeedsConsent
            if(enabled&&!store.hasProfile())return@submit WaxManagerResult.NeedsProfile
            val saved=try{store.saveMode(enabled)}catch(_:Exception){false}
            if(!saved)return@submit WaxManagerResult.Error
            pendingModeChange=true
            if(enabledProcess){connection=WaxConnection.BLOCKED;notify(connection)}
            WaxManagerResult.NeedsColdReopen
        }
    }
}
