/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0
 */
package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.dagger.RTCThreadToken
import io.livekit.android.room.DefaultsManager
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import livekit.org.webrtc.*
import java.util.UUID

open class LocalVideoTrack
@AssistedInject
constructor(
    @Assisted capturer: VideoCapturer,
    @Assisted private var source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: VideoTrack,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val defaultsManager: DefaultsManager,
    private val trackFactory: Factory,
    @Assisted private var dispatchObserver: CaptureDispatchObserver? = null,
    rtcThreadToken: RTCThreadToken,
) : VideoTrack(name, rtcTrack, rtcThreadToken) {

    var capturer = capturer
        private set

    override var rtcTrack: VideoTrack = rtcTrack
        internal set

    internal var codec: String? = null
    private var subscribedCodecs: List<SubscribedCodec>? = null
    private val simulcastCodecs = mutableMapOf<VideoCodec, SimulcastTrackInfo>()

    @FlowObservable
    @get:FlowObservable
    var options: LocalVideoTrackOptions by flowDelegate(options)

    val dimensions: Dimensions
        get() {
            (capturer as? VideoCapturerWithSize)?.let { capturerWithSize ->
                val size = capturerWithSize.findCaptureFormat(
                    options.captureParams.width,
                    options.captureParams.height,
                )
                return Dimensions(size.width, size.height)
            }
            return Dimensions(options.captureParams.width, options.captureParams.height)
        }

    internal var transceiver: RtpTransceiver? = null
    internal val sender: RtpSender?
        get() = transceiver?.sender

    private val closeableManager = CloseableManager()

    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps,
        )
    }

    open fun stopCapture() {
        capturer.stopCapture()
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    override fun dispose() {
        super.dispose()
        capturer.dispose()
        closeableManager.close()
    }

    override fun addRenderer(renderer: VideoSink) {
        if (dispatchObserver != null) {
            dispatchObserver?.registerSink(renderer)
        } else {
            super.addRenderer(renderer)
        }
    }

    override fun removeRenderer(renderer: VideoSink) {
        if (dispatchObserver != null) {
            dispatchObserver?.unregisterSink(renderer)
        } else {
            super.removeRenderer(renderer)
        }
    }

    @Deprecated("Use LocalVideoTrack.switchCamera instead.", ReplaceWith("switchCamera(deviceId = deviceId)"))
    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {
        val cameraCapturer = capturer as? CameraVideoCapturer ?: run {
            LKLog.w { "Attempting to switch camera on a non-camera video track!" }
            return
        }

        var targetDevice: CameraDeviceInfo? = null
        val enumerator = createCameraEnumerator(context)
        if (deviceId != null || position != null) {
            targetDevice = enumerator.findCamera(deviceId, position, fallback = false)
        }

        if (targetDevice == null) {
            val deviceNames = enumerator.deviceNames
            if (deviceNames.size < 2) {
                LKLog.w { "No available cameras to switch to!" }
                return
            }
            val currentIndex = deviceNames.indexOf(options.deviceId)
            val targetDeviceId = deviceNames[(currentIndex + 1) % deviceNames.size]
            targetDevice = enumerator.findCamera(targetDeviceId, fallback = false)
        }

        val targetDeviceId = targetDevice?.deviceId
        fun updateCameraOptions() {
            val newOptions = options.copy(
                deviceId = targetDeviceId,
                position = targetDevice?.position,
            )
            options = newOptions
        }

        val cameraSwitchHandler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                if (cameraCapturer is CameraCapturerWithSize) {
                    cameraCapturer.cameraEventsDispatchHandler
                        .registerHandler(
                            object : CameraEventsHandler {
                                override fun onFirstFrameAvailable() {
                                    updateCameraOptions()
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraError(p0: String?) {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraDisconnected() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraFreezed(p0: String?) {
                                }

                                override fun onCameraOpening(p0: String?) {
                                }

                                override fun onCameraClosed() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }
                            },
                        )
                } else {
                    updateCameraOptions()
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                LKLog.w { "switching camera failed: $errorDescription" }
            }
        }
        if (targetDevice == null) {
            LKLog.w { "No target camera found!" }
            return
        } else {
            cameraCapturer.switchCamera(cameraSwitchHandler, targetDeviceId)
        }
    }

    fun restartTrack(
        options: LocalVideoTrackOptions = defaultsManager.videoTrackCaptureDefaults.copy(),
        videoProcessor: VideoProcessor? = null
    ) {
        if (isDisposed) {
            LKLog.e { "Attempting to restart track that was already disposed, aborting." }
            return
        }

        val oldCapturer = capturer
        val oldSource = source
        val oldRtcTrack = rtcTrack

        oldCapturer.stopCapture()
        oldCapturer.dispose()
        oldSource.dispose()

        oldRtcTrack.setEnabled(false)
        oldRtcTrack.dispose()

        val oldCloseable = closeableManager.unregisterResource(oldRtcTrack)
        oldCloseable?.close()

        val newTrack = createCameraTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            trackFactory,
            videoProcessor
        )

        for (sink in sinks) {
            oldRtcTrack.removeSink(sink)
            newTrack.addRenderer(sink)
        }

        capturer = newTrack.capturer
        source = newTrack.source
        rtcTrack = newTrack.rtcTrack
        this.options = options
        startCapture()
        sender?.setTrack(newTrack.rtcTrack, false)
    }

    internal fun setPublishingLayers(
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        val sender = transceiver?.sender ?: return
        setPublishingLayersForSender(sender, qualities)
    }

    private fun setPublishingLayersForSender(
        sender: RtpSender,
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        if (isDisposed) {
            LKLog.i { "attempted to set publishing layer for disposed video track." }
            return
        }
        try {
            val parameters = sender.parameters ?: return
            val encodings = parameters.encodings ?: return
            var hasChanged = false

            if (encodings.firstOrNull()?.scalabilityMode != null) {
                val encoding = encodings.first()
                var maxQuality = ProtoVideoQuality.OFF
                for (quality in qualities) {
                    if (quality.enabled && (maxQuality == ProtoVideoQuality.OFF || quality.quality.number > maxQuality.number)) {
                        maxQuality = quality.quality
                    }
                }

                if (maxQuality == ProtoVideoQuality.OFF) {
                    if (encoding.active) {
                        encoding.active = false
                        hasChanged = true
                    }
                } else if (!encoding.active) {
                    encoding.active = true
                    hasChanged = true
                }
            } else {
                for (quality in qualities) {
                    val rid = EncodingUtils.ridForVideoQuality(quality.quality) ?: continue
                    val encoding = encodings.firstOrNull { it.rid == rid }
                        ?: encodings.takeIf { it.size == 1 && quality.quality == ProtoVideoQuality.LOW }?.first()
                        ?: continue
                    if (encoding.active != quality.enabled) {
                        hasChanged = true
                        encoding.active = quality.enabled
                    }
                }
            }

            if (hasChanged) {
                sender.parameters = parameters
            }
        } catch (e: Exception) {
            LKLog.w(e) { "Exception caught while setting publishing layers." }
            return
        }
    }

    internal fun setPublishingCodecs(codecs: List<SubscribedCodec>): List<VideoCodec> {
        if (this.codec == null && codecs.isNotEmpty()) {
            setPublishingLayers(codecs.first().qualitiesList)
            return emptyList()
        }

        this.subscribedCodecs = codecs
        val newCodecs = mutableListOf<VideoCodec>()

        for (codec in codecs) {
            if (this.codec == codec.codec) {
                setPublishingLayers(codec.qualitiesList)
            } else {
                val videoCodec = try {
                    VideoCodec.fromCodecName(codec.codec)
                } catch (e: Exception) {
                    continue
                }

                val simulcastInfo = this.simulcastCodecs[videoCodec]
                if (simulcastInfo?.sender == null) {
                    for (q in codec.qualitiesList) {
                        if (q.enabled) {
                            newCodecs.add(videoCodec)
                            break
                        }
                    }
                } else {
                    setPublishingLayersForSender(
                        simulcastInfo.sender!!,
                        codec.qualitiesList,
                    )
                }
            }
        }
        return newCodecs
    }

    internal fun addSimulcastTrack(codec: VideoCodec, encodings: List<RtpParameters.Encoding>): SimulcastTrackInfo {
        if (this.simulcastCodecs.containsKey(codec)) {
            throw IllegalStateException("$codec already added!")
        }
        val simulcastTrackInfo = SimulcastTrackInfo(
            codec = codec.codecName,
            rtcTrack = rtcTrack,
            encodings = encodings,
        )
        simulcastCodecs[codec] = simulcastTrackInfo
        return simulcastTrackInfo
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: VideoTrack,
            dispatchObserver: CaptureDispatchObserver?,
        ): LocalVideoTrack
    }

    companion object {

        internal fun createCameraTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a camera video track.")
            }

            val (capturer, newOptions) = CameraCapturerUtils.createCameraCapturer(context, options) ?: TODO()

            return createTrack(
                peerConnectionFactory = peerConnectionFactory,
                context = context,
                name = name,
                capturer = capturer,
                options = newOptions,
                rootEglBase = rootEglBase,
                trackFactory = trackFactory,
                videoProcessor = videoProcessor,
            )
        }

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            capturer: VideoCapturer,
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(options.isScreencast)

            val finalVideoProcessor = if (options.captureParams.adaptOutputToDimensions) {
                ScaleCropVideoProcessor(
                    targetWidth = options.captureParams.width,
                    targetHeight = options.captureParams.height,
                ).apply {
                    childVideoProcessor = videoProcessor
                }
            } else {
                videoProcessor
            }
            source.setVideoProcessor(finalVideoProcessor)

            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)

            val dispatchObserver = if (videoProcessor == null) {
                CaptureDispatchObserver().apply {
                    registerObserver(source.capturerObserver)
                }
            } else {
                null
            }

            capturer.initialize(
                surfaceTextureHelper,
                context,
                dispatchObserver ?: source.capturerObserver,
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = rtcTrack,
                dispatchObserver = dispatchObserver,
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper),
            )
            return track
        }

        fun createFromCapturer(
            context: Context,
            capturer: VideoCapturer,
            name: String = "camera",
            options: LocalVideoTrackOptions = LocalVideoTrackOptions()
        ): LocalVideoTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a video track.")
            }

            val component = io.livekit.android.LiveKit.component
                ?: throw IllegalStateException("LiveKit must be initialized. Call LiveKit.create(context) first.")

            val track = createTrack(
                peerConnectionFactory = component.peerConnectionFactory(),
                context = context,
                name = name,
                capturer = capturer,
                options = options,
                rootEglBase = component.eglBase(),
                trackFactory = component.localVideoTrackFactory()
            )
            
            track.startCapture()
            return track
        }
    }
}