package com.ynk.virtualdisplay.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

object VideoDecoderTuning {

    private const val TAG = "VideoDecoderTuning"

    private val SOFTWARE_PREFIXES = listOf(
        "omx.google.",
        "c2.android.",
        "omx.ffmpeg.",
    )

    private val HARDWARE_VENDOR_PREFIXES = listOf(
        "omx.qcom.",
        "c2.qti.",
        "c2.qcom.",
        "omx.exynos.",
        "c2.exynos.",
        "omx.mtk.",
        "c2.mtk.",
        "omx.hisi.",
        "c2.hisi.",
        "omx.nvidia.",
        "c2.nvidia.",
        "omx.rk.",
        "c2.rk.",
        "omx.amlogic.",
        "c2.amlogic.",
    )

    data class DecoderResult(
        val codec: MediaCodec,
        val decoderName: String,
        val appliedOptions: List<String>,
    )

    fun createConfiguredDecoder(
        mimeType: String,
        width: Int,
        height: Int,
        surface: Surface,
    ): DecoderResult {
        val candidates = buildCandidateList(mimeType)
        val errors = mutableListOf<String>()

        for (info in candidates) {
            val name = info.name
            val format = buildFormat(mimeType, width, height, info)
            val opts = mutableListOf<String>()
            applyLowLatency(format, info, opts)

            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(name)
                codec.configure(format, surface, null, 0)
                codec.start()
                Log.i(TAG, "Selected decoder: $name (${width}x${height})")
                return DecoderResult(codec, name, opts)
            } catch (e: Exception) {
                runCatching { codec?.release() }
                errors.add("$name: ${e.message}")
                Log.w(TAG, "Failed with $name: ${e.message}")
            }
        }

        Log.w(TAG, "All named decoders failed, falling back to createDecoderByType. Errors: $errors")
        val fallback = MediaCodec.createDecoderByType(mimeType)
        val format = buildFormat(mimeType, width, height, null)
        val opts = mutableListOf<String>()
        applyLowLatency(format, null, opts)
        fallback.configure(format, surface, null, 0)
        fallback.start()
        return DecoderResult(fallback, fallback.name, opts)
    }

    private fun buildCandidateList(mimeType: String): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder }
            .filter { info ->
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }

        val software = mutableListOf<MediaCodecInfo>()
        val hardware = mutableListOf<MediaCodecInfo>()

        for (info in all) {
            val n = info.name.lowercase()
            if (SOFTWARE_PREFIXES.any { n.startsWith(it) }) {
                software.add(info)
            } else if (HARDWARE_VENDOR_PREFIXES.any { n.startsWith(it) }) {
                hardware.add(info)
            } else {
                hardware.add(info)
            }
        }

        val hwWithLowLatency = mutableListOf<MediaCodecInfo>()
        val hwWithout = mutableListOf<MediaCodecInfo>()
        for (info in hardware) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val caps = info.getCapabilitiesForType(mimeType)
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    hwWithLowLatency.add(info)
                    continue
                }
            }
            hwWithout.add(info)
        }

        return hwWithLowLatency + hwWithout + software
    }

    private fun buildFormat(
        mimeType: String,
        width: Int,
        height: Int,
        decoderInfo: MediaCodecInfo?,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)

        try {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
        } catch (_: Exception) {
        }

        return format
    }

    private fun applyLowLatency(format: MediaFormat, decoderInfo: MediaCodecInfo?, opts: MutableList<String>) {
        runCatching {
            format.setInteger("low-latency", 1)
            opts.add("low-latency")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                opts.add("priority=0")
            }
            runCatching {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                opts.add("operating-rate=32767")
            }
        }

        if (decoderInfo != null) {
            val name = decoderInfo.name.lowercase()
            applyVendorTuning(format, name, opts)
        }
    }

    private fun applyVendorTuning(format: MediaFormat, codecName: String, opts: MutableList<String>) {
        when {
            codecName.startsWith("omx.qcom") || codecName.startsWith("c2.qti") || codecName.startsWith("c2.qcom") -> {
                runCatching {
                    format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    opts.add("qti-low-latency")
                }
                runCatching {
                    format.setInteger("vendor.qti-ext-dec-linear-transform.enable", 1)
                    opts.add("qti-linear-transform")
                }
            }
            codecName.startsWith("omx.mtk") || codecName.startsWith("c2.mtk") -> {
                runCatching {
                    format.setInteger("vendor.mtk.vdec.low-latency.mode", 1)
                    opts.add("mtk-low-latency")
                }
                runCatching {
                    format.setInteger("vendor.mtk.vdec.preload.frame.count", 1)
                    opts.add("mtk-preload-1")
                }
            }
            codecName.startsWith("omx.exynos") || codecName.startsWith("c2.exynos") -> {
                runCatching {
                    format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    opts.add("exynos-low-latency")
                }
            }
            codecName.startsWith("omx.hisi") || codecName.startsWith("c2.hisi") -> {
                runCatching {
                    format.setInteger("vendor.hisi-ext-dec-low-latency.enable", 1)
                    opts.add("hisi-low-latency")
                }
            }
        }
    }
}
