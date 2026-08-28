package com.ynk.virtualdisplay.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * 视频解码器调优工具：优先选择支持低延迟的硬件解码器，
 * 按「硬件低延迟 > 硬件 > 软件」顺序逐一尝试，并为解码器应用低延迟与厂商定制参数。
 */
object VideoDecoderTuning {

    private const val TAG = "VideoDecoderTuning"

    /** 解码器创建结果：已启动的 [MediaCodec]、解码器名称、是否硬件解码及成功应用的调优选项。 */
    data class DecoderResult(
        val codec: MediaCodec,
        val decoderName: String,
        val isHardware: Boolean,
        val appliedOptions: List<String>,
    )

    /**
     * 创建并启动一个已配置的低延迟解码器。
     *
     * 按「硬件低延迟 > 硬件 > 软件 > createDecoderByType 兜底」的顺序尝试；
     * 任一解码器配置失败会释放并尝试下一个，全部失败时抛出运行时异常。
     *
     * @param mimeType 视频 MIME 类型（如 video/avc）
     * @param width 视频宽
     * @param height 视频高
     * @param surface 解码输出 Surface
     * @return 配置并启动成功的解码器及已应用选项
     * @throws RuntimeException 所有候选解码器均创建失败时抛出
     */
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
                return DecoderResult(codec, name, isHardwareDecoder(name), opts)
            } catch (e: Exception) {
                runCatching { codec?.release() }
                errors.add("$name: ${e.message}")
                Log.w(TAG, "Failed with $name: ${e.message}")
            }
        }

        Log.w(TAG, "All named decoders failed, falling back to createDecoderByType. Errors: $errors")
        try {
            val fallback = MediaCodec.createDecoderByType(mimeType)
            val format = buildFormat(mimeType, width, height, null)
            val opts = mutableListOf<String>()
            applyLowLatency(format, null, opts)
            fallback.configure(format, surface, null, 0)
            fallback.start()
            Log.i(TAG, "Selected fallback decoder: ${fallback.name}")
            return DecoderResult(fallback, fallback.name, isHardwareDecoder(fallback.name), opts)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback decoder creation also failed. All decoders exhausted.", e)
            throw RuntimeException("Failed to create decoder for $mimeType (${width}x${height}). Tried ${candidates.size} named decoders and fallback. Errors: $errors", e)
        }
    }

    private fun buildCandidateList(mimeType: String): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder }
            .filter { info ->
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }

        val software = mutableListOf<MediaCodecInfo>()
        val hardware = mutableListOf<MediaCodecInfo>()

        // minSdk >= 29：直接用官方能力 API 判定软硬实现，比名字前缀可靠得多。
        // isSoftwareOnly()==true 的纯软件解码器明确排除；其余（硬件加速或未标记
        // 的 vendor 实现）统一归为硬件路径优先尝试 —— 与「硬件优先、软件兜底」语义一致，
        // 且不会把第三方软件解码器误判为硬件。
        for (info in all) {
            if (info.isSoftwareOnly()) {
                software.add(info)
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

    /** 根据解码器名称判定是否为硬件解码。优先用官方能力 API，查询不到时按名字前缀兜底。 */
    private fun isHardwareDecoder(codecName: String): Boolean {
        val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name.equals(codecName, ignoreCase = true) }
        if (info != null) {
            return !info.isSoftwareOnly()
        }
        val n = codecName.lowercase()
        // 兜底：Google/FFmpeg 软件解码器名带这些前缀，其余按硬件处理。
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg."))
    }

    private fun buildFormat(
        mimeType: String,
        width: Int,
        height: Int,
        decoderInfo: MediaCodecInfo?,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)

        runCatching {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
        }.onFailure { Log.d(TAG, "KEY_MAX_INPUT_SIZE not supported by this device", it) }

        return format
    }

    private fun applyLowLatency(format: MediaFormat, decoderInfo: MediaCodecInfo?, opts: MutableList<String>) {
        runCatching {
            format.setInteger("low-latency", 1)
            opts.add("low-latency")
        }.onFailure { Log.d(TAG, "Key 'low-latency' not supported", it) }
        runCatching {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            opts.add("priority=0")
        }.onFailure { Log.d(TAG, "KEY_PRIORITY not supported", it) }
        runCatching {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            opts.add("operating-rate=32767")
        }.onFailure { Log.d(TAG, "KEY_OPERATING_RATE not supported", it) }

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
                }.onFailure { Log.d(TAG, "QTI low-latency key not supported", it) }
                runCatching {
                    format.setInteger("vendor.qti-ext-dec-linear-transform.enable", 1)
                    opts.add("qti-linear-transform")
                }.onFailure { Log.d(TAG, "QTI linear-transform key not supported", it) }
            }
            codecName.startsWith("omx.mtk") || codecName.startsWith("c2.mtk") -> {
                runCatching {
                    format.setInteger("vendor.mtk.vdec.low-latency.mode", 1)
                    opts.add("mtk-low-latency")
                }.onFailure { Log.d(TAG, "MTK low-latency key not supported", it) }
                runCatching {
                    format.setInteger("vendor.mtk.vdec.preload.frame.count", 1)
                    opts.add("mtk-preload-1")
                }.onFailure { Log.d(TAG, "MTK preload key not supported", it) }
            }
            codecName.startsWith("omx.exynos") || codecName.startsWith("c2.exynos") -> {
                runCatching {
                    format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    opts.add("exynos-low-latency")
                }.onFailure { Log.d(TAG, "Exynos low-latency key not supported", it) }
            }
            codecName.startsWith("omx.hisi") || codecName.startsWith("c2.hisi") -> {
                runCatching {
                    format.setInteger("vendor.hisi-ext-dec-low-latency.enable", 1)
                    opts.add("hisi-low-latency")
                }.onFailure { Log.d(TAG, "HiSilicon low-latency key not supported", it) }
            }
        }
    }
}
