package com.yy.realx

import android.Manifest
import android.annotation.TargetApi
import android.app.Service
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.ycloud.utils.FileUtils
import com.yy.realx.objectbox.MyObjectBox
import io.objectbox.Box
import io.objectbox.BoxStore
import java.io.File
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

class ContainerActivity : AppCompatActivity() {
    companion object {
        private var TAG = ContainerActivity::class.java.simpleName
        private const val PERMISSION_CODE = 0x00001
    }

    private val objectbox: BoxStore by lazy {
        MyObjectBox.builder()
            .androidContext(this)
            .baseDirectory(this.filesDir)
            .build()
    }

    /**
     * 返回指定数据库实例
     */
    fun <T> boxFor(clazz: Class<T>): Box<T> {
        return objectbox.boxFor(clazz)
    }

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contaner)
        mModel.stage.observe(this, Observer {
            transitWithStage(it!!)
        })
        //权限请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
                ), PERMISSION_CODE
            )
        } else {
            onPermissionGranted()
        }
        val manager = getSystemService(Service.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "${TAG}_Lock")
    }

    private val mModel: RealXViewModel by lazy {
        ViewModelProviders.of(this@ContainerActivity).get(RealXViewModel::class.java)
    }

    /**
     * 权限授予回调
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Log.d(TAG, "onRequestPermissionsResult():$requestCode")
        if (grantResults.none {
                it != PackageManager.PERMISSION_GRANTED
            }) {
            onPermissionGranted()
        }
    }

    /**
     * 获取权限后回调
     */
    private fun onPermissionGranted() {
        Log.d(TAG, "onPermissionGranted()")
        mModel.transitTo(Stage.RECORD)
        //请求权限
        setBrightnessAuto(false)
    }

    private val mTimer: Timer by lazy {
        Timer("Verify_Timer", false)
    }

    /**
     * 设置亮度模式
     */
    private fun setBrightnessAuto(auto: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                //循环检查是否授权
                loopVerifySettings()
            } else {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun loopVerifySettings() {
        mTimer.scheduleAtFixedRate(0, 200) {
            if (!Settings.System.canWrite(this@ContainerActivity)) {
                return@scheduleAtFixedRate
            }
            val intent = Intent()
            intent.setClass(this@ContainerActivity, ContainerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            //取消schedule
            cancel()
        }
    }

    /**
     * 根据stage切换fragment
     * @param stage
     */
    private fun transitWithStage(stage: Stage) {
        Log.d(TAG, "transitWithStage():$stage")
        when (stage) {
            Stage.PERMISSION -> {
                transaction(PermissionFragment(), PermissionFragment::class.java.simpleName)
            }
            Stage.RECORD -> {
                transaction(RecordFragment(), RecordFragment::class.java.simpleName)
            }
            Stage.EDIT -> {
                transaction(EditFragment(), EditFragment::class.java.simpleName)
            }
            Stage.SHARE -> {
                transaction(ShareFragment(), ShareFragment::class.java.simpleName)
            }
        }
    }

    /**
     * 切换fragment
     */
    private fun transaction(fragment: Fragment, tag: String) {
        Log.d(TAG, "transaction():$tag, $fragment")
        var target = supportFragmentManager.findFragmentByTag(tag)
        if (null == target) {
            target = fragment
        }
        supportFragmentManager.beginTransaction().replace(R.id.container, target, tag).commitAllowingStateLoss()
    }

    /**
     * 按返回按键处理
     */
    override fun onBackPressed() {
        Log.d(TAG, "transitWithStage():${mModel.stage.value}")
        when (mModel.stage.value) {
            Stage.RECORD -> {
                super.onBackPressed()
            }
            Stage.EDIT -> {
                mModel.transitTo(Stage.RECORD)
            }
            Stage.SHARE -> {
                release()
                mModel.video.value = null
                mModel.transitTo(Stage.RECORD)
            }
            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setBrightnessAuto(false)
        wakeLock.acquire()
    }

    override fun onPause() {
        mTimer.cancel()
        setBrightnessAuto(true)
        wakeLock.release()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        objectbox.close()
        release()
    }

    /**
     * 删除临时文件
     */
    internal fun release() {
        val video = mModel.video.value ?: return
        FileUtils.deleteFileSafely(File(video.audio.path))
        FileUtils.deleteFileSafely(File(video.audio.tuner))
        FileUtils.deleteFileSafely(File(video.audio.mixer))
        video.segments.forEach {
            FileUtils.deleteFileSafely(File(it.path))
        }
        FileUtils.renameFileSafely(File(video.export), File(video.path))
    }
}
