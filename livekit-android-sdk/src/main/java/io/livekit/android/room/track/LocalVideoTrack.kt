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
import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.video.*
import io.livekit.android.room.util.EncodingUtils
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import livekit.org.webrtc.*
import java.util.UUID
import livekit.LivekitModels.VideoQuality as ProtoVideoQuality

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
) : io.livekit.android.room.track.VideoTrack(name, rtcTrack, rtcThreadToken) {

    var capturer = capturer
        private set

    override var rtcTrack: VideoTrack = rtcTrack
        internal set

    private val closeableManager = CloseableManager()

    @FlowObservable
    @get:FlowObservable
    var options: LocalVideoTrackOptions by flowDelegate(options)

    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps
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

        /**
         * 🔥 SAFE API
         * Custom capturer (DeepAR) → LocalVideoTrack
         * ❌ No extra SurfaceTextureHelper
         * ✅ Low latency
         */
        @JvmStatic
        fun createFromCustomCapturer(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            capturer: VideoCapturer,
            rootEglBase: EglBase,
            trackFactory: Factory,
            name: String = "camera",
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {

            val source = peerConnectionFactory.createVideoSource(options.isScreencast)

            if (videoProcessor != null) {
                source.setVideoProcessor(videoProcessor)
            }

            val rtcTrack =
                peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = rtcTrack,
                dispatchObserver = null
            )
        }

        internal fun createCameraTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permission required")
            }

            val (capturer, newOptions) =
                CameraCapturerUtils.createCameraCapturer(context, options)
                    ?: error("No camera found")

            val source = peerConnectionFactory.createVideoSource(false)

            val finalProcessor =
                if (options.captureParams.adaptOutputToDimensions) {
                    ScaleCropVideoProcessor(
                        options.captureParams.width,
                        options.captureParams.height
                    ).apply { childVideoProcessor = videoProcessor }
                } else videoProcessor

            source.setVideoProcessor(finalProcessor)

            val surfaceTextureHelper =
                SurfaceTextureHelper.create("CameraCapture", rootEglBase.eglBaseContext)

            val observer =
                if (videoProcessor == null) {
                    CaptureDispatchObserver().apply {
                        registerObserver(source.capturerObserver)
                    }
                } else null

            capturer.initialize(
                surfaceTextureHelper,
                context,
                observer ?: source.capturerObserver
            )

            val rtcTrack =
                peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer,
                source,
                name,
                newOptions,
                rtcTrack,
                observer
            )

            track.closeableManager.registerResource(
                rtcTrack,
                io.livekit.android.memory.SurfaceTextureHelperCloser(surfaceTextureHelper)
            )

            return track
        }
    }
}
