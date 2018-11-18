package com.yy.realx

import android.app.ProgressDialog
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.ycloud.api.process.IMediaListener
import com.ycloud.api.process.VideoExport
import com.ycloud.mediaprocess.VideoFilter
import com.ycloud.player.widget.MediaPlayerListener
import com.ycloud.svplayer.SvVideoViewInternal
import com.ycloud.utils.FileUtils
import com.yy.android.ai.audiodsp.IOneKeyTunerApi
import com.yy.audioengine.AudioUtils
import com.yy.audioengine.IAudioLibJniInit
import com.yy.audioengine.IKaraokeFileMixerNotity
import com.yy.audioengine.KaraokeFileMixer
import kotlinx.android.synthetic.main.fragment_edit.*
import kotlinx.android.synthetic.main.fragment_mixer_item.view.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

class EditFragment : Fragment() {
    companion object {
        private val TAG = EditFragment::class.java.simpleName
        private const val PERIOD: Long = 32
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_edit, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        music = VideoFilter(context)
        prepareEditView()
    }

    private val mModel: RealXViewModel by lazy {
        ViewModelProviders.of(activity!!).get(RealXViewModel::class.java)
    }

    private lateinit var mViewInternal: SvVideoViewInternal

    /**
     * 初始化播放器
     */
    private fun prepareEditView() {
        Log.d(TAG, "prepareEditView()")
        if (video_view.videoViewInternal !is SvVideoViewInternal) {
            throw IllegalArgumentException("Only support SvVideoViewInternal, please check.")
        }
        mViewInternal = video_view.videoViewInternal as SvVideoViewInternal
        //设定播放器
        mViewInternal.setLayoutMode(SvVideoViewInternal.LAYOUT_SCALE_FIT)
        mViewInternal.setMediaPlayerListener {
            Log.d(TAG, "setMediaPlayerListener():${it.what}")
            when (it.what) {
                MediaPlayerListener.MSG_PLAY_PREPARED -> {
                    //todo: 准备完成，开始播放
                }
                MediaPlayerListener.MSG_PLAY_COMPLETED -> {
                    mViewInternal.start()
                }
                MediaPlayerListener.MSG_PLAY_SEEK_COMPLETED -> {
                    //todo: seek完成，准备播放
                }
            }
        }
        //设定数据
        val video = mModel.video.value
        checkNotNull(video)
        val path = video.path
        Log.d(TAG, "VideoPath():$path, ${video.hashCode()}")
        mViewInternal.setVideoPath(path)
        val audio = video.audio
        Log.d(TAG, "AudioPath():${audio.path}, ${audio.hashCode()}")
        music.setBackgroundMusic(audio.path, 0.0f, 1.0f)
        mViewInternal.setVFilters(music)
        edit_back.setOnClickListener {
            activity!!.onBackPressed()
        }
        //功能
        export_video.setOnClickListener {
            exportVideoWithParams(video)
        }
        tunerWithModes(video)
        //原声切换
        toggle_music.setOnCheckedChangeListener { view, isChecked ->
            Log.d(TAG, "Toggle.OnCheckedChangeListener():$isChecked")
            switchVolume(isChecked, audio)
        }
        //混声切换
        toggle_mixer.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        val adapter = MixerAdapter(context!!)
        adapter.setOnItemClickListener {
            Log.d(TAG, "setOnItemClickListener():${it.name}")
            if (mixing.get()) {
                return@setOnItemClickListener false
            } else {
                applyMixer(audio, it)
                return@setOnItemClickListener true
            }
        }
        toggle_mixer.adapter = adapter
    }

    /**
     * 获取伴奏文件
     */
    private fun getAccompanyBy(key: String): String {
        Log.d(TAG, "getAccompanyBy():$key")
        if (key.isBlank()) {
            return ""
        }
        val dir = File(context!!.filesDir, "accompany")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "$key.wav")
        if (file.exists()) {
            return file.absolutePath
        }
        val id = resources.getIdentifier(key, "raw", context!!.packageName)
        if (id <= 0) {
            return ""
        }
        var count: Int
        var buffer = ByteArray(4 * 1024)
        val input = resources.openRawResource(id)
        val output = FileOutputStream(file)
        while (true) {
            count = input.read(buffer)
            if (count <= 0) {
                break
            } else {
                output.write(buffer, 0, count)
            }
        }
        input.close()
        output.flush()
        output.close()
        return file.absolutePath
    }

    val mixing = AtomicBoolean(false)

    /**
     * 音调处理
     */
    private fun applyMixer(audio: AudioSettings, mixer: MixerItem) {
        Log.d(TAG, "applyMixer():${mixer.name}, ${audio.hashCode()}")
        if (mixing.get()) {
            return
        }
        mixing.set(true)
        mTimer.schedule(0) {
            audio.accompany = mixer.key
            val path = if (toggle_music.isChecked) audio.tuner else audio.path
            val accompany = getAccompanyBy(mixer.key)
            Log.d(TAG, "getAccompanyBy():$accompany")
            val engine = KaraokeFileMixer()
            engine.Init()
            if (!engine.Open(path, accompany)) {
                return@schedule
            }
            engine.EnableEqualizer(mixer.eqEnable)
            if (mixer.eqEnable) {
                engine.SetEqGains(mixer.eq)
            }
            engine.EnableCompressor(mixer.compressEnable)
            if (mixer.compressEnable) {
                engine.SetCompressorParam(mixer.compressor)
            }
            engine.EnableReverbNew(mixer.reverbEnable)
            if (mixer.reverbEnable) {
                engine.SetReverbNewParam(mixer.reverb)
            }
            engine.EnableLimiter(mixer.limiterEnable)
            if (mixer.limiterEnable) {
                engine.SetLimiterParam(mixer.limiter)
            }
            engine.SetAccompanyVolume(100)
            engine.SetVoiceVolume(100)
            engine.SetKaraokeFileMixerNotify(object : IKaraokeFileMixerNotity {
                var duration: Long = 0

                override fun OnFileMixerState(progress: Long, total: Long) {
                    val percent = Math.min(progress * 100 / total, 99)
                    Log.d(TAG, "OnFileMixerState():$percent")
                    duration = total
                }

                override fun OnFinishMixer() {
                    Log.d(TAG, "OnFinishMixer():${audio.mixer}")
                    engine.Stop()
                    engine.Destroy()
                    val aac = audio.mixer.replace(AudioSettings.EXT, ".aac")
                    AudioUtils.TransAudioFileToWav(aac, audio.mixer, duration)
                    updateMixerByNow(audio, accompany)
                    mixing.set(false)
                }
            })
            FileUtils.deleteFileSafely(File(audio.mixer))
            val aac = audio.mixer.replace(AudioSettings.EXT, ".aac")
            if (!engine.Start(aac)) {
                return@schedule
            }
        }
    }

    /**
     * 使用音效
     */
    private fun updateMixerByNow(audio: AudioSettings, accompany: String) {
        Log.d(TAG, "updateMixerByNow():${audio.mixer}, ${audio.hashCode()}, $accompany")
        music.setBackgroundMusic(audio.mixer, 0.0f, 1.0f)
        mViewInternal.setVFilters(music)
        seekTo(0)
    }

    /**
     * 原声变声切换
     */
    private fun switchVolume(checked: Boolean, audio: AudioSettings) {
        Log.d(TAG, "switchVolume():$checked, ${audio.hashCode()}")
        if (checked) {
            music.setBackgroundMusic(audio.tuner, 0.0f, 1.0f)
        } else {
            music.setBackgroundMusic(audio.path, 0.0f, 1.0f)
        }
        activity!!.runOnUiThread {
            val adapter = (toggle_mixer.adapter as MixerAdapter)
            adapter.selected = null
            adapter.notifyDataSetChanged()
        }
        mViewInternal.setVFilters(music)
        seekTo(0)
    }

    private fun tunerWithModes(video: VideoSettings) {
        Log.d(TAG, "tunerWithModes()")
        val dialog = ProgressDialog.show(context, "", "变声中...", false)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { dialog, keyCode, event -> true }
        val log = video.path.replace(".mp4", ".log")
        Log.d(TAG, "LogPath():$log")
        IOneKeyTunerApi.CreateOneKeyTuner(log)
        var current = 0
        val list = mutableListOf<IOneKeyTunerApi.ModeAndPosInfo>()
        video.segments.forEach {
            val mode = IOneKeyTunerApi.ModeAndPosInfo()
            mode.mode = IOneKeyTunerApi.GetTunerModeVal(it.tuner)
            mode.startPos = current
            current += it.duration
            mode.endPos = current
            list.add(mode)
        }
        IOneKeyTunerApi.OneKeyTuneWithListStartThread(list, video.audio.path, video.audio.tuner)
        mTimer.scheduleAtFixedRate(0, 100) {
            val progress = IOneKeyTunerApi.GetProgress()
            when (progress) {
                -1 -> {
                    Log.d(TAG, "VolProcessError()$progress")
                    IOneKeyTunerApi.Destroy()
                    cancel()
                    dialog.dismiss()
                }
                100 -> {
                    Log.d(TAG, "VolProcessFinish()$progress")
                    IOneKeyTunerApi.Destroy()
                    cancel()
                    dialog.dismiss()
                    //设置背景音乐
                    updateTunerByNow(video.audio)
                }
                else -> {
                    Log.d(TAG, "VolProcessProcess():$progress")
                    dialog.progress = progress
                }
            }
        }
    }

    /**
     * 导出视频
     */
    private fun exportVideoWithParams(video: VideoSettings) {
        val out = video.export
        val audio = video.audio
        val dialog = ProgressDialog.show(context, "", "导出中...", false)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { dialog, keyCode, event -> true }
        val filter = VideoFilter(context)
        if (File(audio.mixer).exists()) {
            filter.exportBgm = audio.mixer
        } else {
            filter.exportBgm = audio.tuner
        }
        filter.setBackgroundMusic(filter.exportBgm, 0.0f, 1.0f)
        val export = VideoExport(context, video.path, out, filter)
        export.setMediaListener(object : IMediaListener {
            override fun onProgress(progress: Float) {
                Log.d(TAG, "VideoExport.onProgress():$progress")
                dialog.progress = (100 * progress).toInt()
            }

            override fun onError(errType: Int, errMsg: String?) {
                Log.d(TAG, "VideoExport.onError():$errMsg, $errMsg")
                export.cancel()
                export.release()
                dialog.dismiss()
            }

            override fun onEnd() {
                Log.d(TAG, "VideoExport.onEnd()")
                export.cancel()
                export.release()
                dialog.dismiss()
                //进入分享页面
                activity!!.runOnUiThread {
                    mModel.transitTo(Stage.SHARE)
                }
            }
        })
        val config = mViewInternal.playerFilterSessionWrapper.filterConfig
        Log.d(TAG, "exportVideoWithParams():$config")
        export.fFmpegFilterSessionWrapper.setFilterJson(config)
        mTimer.schedule(0) {
            export.export()
        }
    }

    private lateinit var music: VideoFilter

    /**
     * 变更背景音乐
     */
    private fun updateTunerByNow(audio: AudioSettings) {
        if (null == music) {
            music = VideoFilter(context)
        }
        val path = audio.tuner
        music.setBackgroundMusic(path, 0.0f, 1.0f, audio.start)
        mViewInternal.setVFilters(music)
        //重新开始
        seekTo(0)
        //菜单显示
        activity!!.runOnUiThread {
            edit_menu.visibility = View.VISIBLE
        }
    }

    private var listener: TimerTask? = null

    private val mTimer: Timer by lazy {
        Timer("Edit_Timer", false)
    }

    /**
     * 开始播放
     */
    private fun start() {
        Log.d(TAG, "start()")
        mViewInternal.start()
        listener = mTimer.scheduleAtFixedRate(0, PERIOD) {
            val position = mViewInternal.currentVideoPostion
            val duration = mViewInternal.duration
            Log.d(TAG, "onProgress():$position, $duration")
            //todo: 播放进度，回调通知
        }
    }

    /**
     * 在指定位置播放
     *
     * @param
     */
    private fun seekTo(position: Int) {
        mViewInternal.pause()
        mViewInternal.seekTo(position)
        start()
    }

    /**
     * 暂停播放
     */
    private fun pause() {
        Log.d(TAG, "pause()")
        listener?.cancel()
        mViewInternal.pause()
    }

    /**
     * 释放资源
     */
    private fun release() {
        Log.d(TAG, "release()")
        pause()
        mViewInternal.stopPlayback()
    }

    data class MixerItem(
        @SerializedName("Reverb")
        var reverb: FloatArray? = null,
        @SerializedName("limiterEnable")
        var limiterEnable: Boolean = false,
        @SerializedName("reverbEnable")
        var reverbEnable: Boolean = false,
        @SerializedName("name")
        var name: String = "",
        @SerializedName("icon")
        var icon: String = "",
        @SerializedName("Compressor")
        var compressor: IntArray? = null,
        @SerializedName("EQ")
        var eq: FloatArray? = null,
        @SerializedName("compressEnable")
        var compressEnable: Boolean = false,
        @SerializedName("key")
        var key: String = "",
        @SerializedName("Limiter")
        var limiter: FloatArray? = null,
        @SerializedName("eqEnable")
        var eqEnable: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            return super.equals(other)
        }

        override fun hashCode(): Int {
            return super.hashCode()
        }
    }

    class MixerViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    class MixerAdapter(val context: Context) : RecyclerView.Adapter<MixerViewHolder>() {
        var selected: MixerItem? = null
        val list: Array<MixerItem>

        init {
            val stream = context.assets.open("mixer.json")
            val reader = InputStreamReader(stream, Charset.forName("utf-8"))
            val type = object : TypeToken<Array<MixerItem>>() {}.type
            list = Gson().fromJson<Array<MixerItem>>(reader, type)
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): MixerViewHolder {
            Log.d(TAG, "onCreateViewHolder()")
            val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.fragment_mixer_item, viewGroup, false)
            return MixerViewHolder(view)
        }

        override fun getItemCount(): Int {
            Log.d(TAG, "getItemCount()")
            return list.size
        }

        override fun onBindViewHolder(holder: MixerViewHolder, position: Int) {
            Log.d(TAG, "onBindViewHolder()")
            val item = list[position]
            holder.view.mixer_name.text = item.name
            holder.view.setOnClickListener {
                Log.d(TAG, "setOnClickListener():${item.name}")
                if (selected != item) {
                    if (listener?.invoke(item) == true) {
                        selected = item
                        notifyDataSetChanged()
                    }
                }
            }
            holder.view.mixer_name.isSelected = (selected == item)
        }

        private var listener: ((MixerItem) -> Boolean)? = null

        /**
         * 监听器
         */
        fun setOnItemClickListener(function: (MixerItem) -> Boolean) {
            this.listener = function
        }
    }

    //----------------------生命周期------------------------//

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IAudioLibJniInit.InitLib(context)
    }

    override fun onResume() {
        super.onResume()
        start()
    }

    override fun onPause() {
        pause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
        mTimer.cancel()
    }
}