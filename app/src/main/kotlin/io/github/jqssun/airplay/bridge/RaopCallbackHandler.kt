package io.github.jqssun.airplay.bridge

// every method here is looked up by name + JNI signature in android_raop_callbacks.c
// (GetMethodID at init): keep both sides in sync; a lookup without a matching method
// here makes the server fail to start. AirPlay Video (HLS) callbacks are inert C stubs with no
// Kotlin counterpart.
interface RaopCallbackHandler {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    // airplay volume in dB: -30..0, -144 = mute
    fun onClientVolume(db: Float)
    // /info initialVolume
    fun onClientVolumeQuery(): Float
    fun onAudioTeardown()
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onDisplayPin(pin: String)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onMirrorRunning(running: Boolean)
    fun onClientRequest(deviceId: ByteArray, model: ByteArray, name: ByteArray): Boolean
}
