package com.xyoye.dandanplay.ui.activities.play;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.StringUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xyoye.dandanplay.bean.PlayHistoryBean;
import com.xyoye.dandanplay.bean.UploadDanmuBean;
import com.xyoye.dandanplay.bean.event.SaveCurrentEvent;
import com.xyoye.dandanplay.bean.event.UpdateFragmentEvent;
import com.xyoye.dandanplay.bean.params.DanmuUploadParam;
import com.xyoye.dandanplay.bean.params.PlayParam;
import com.xyoye.dandanplay.ui.activities.setting.PlayerSettingActivity;
import com.xyoye.dandanplay.ui.fragment.PlayFragment;
import com.xyoye.dandanplay.ui.weight.dialog.CommonDialog;
import com.xyoye.dandanplay.ui.weight.dialog.FileManagerDialog;
import com.xyoye.dandanplay.ui.weight.dialog.SelectSubtitleDialog;
import com.xyoye.dandanplay.utils.AppConfig;
import com.xyoye.dandanplay.utils.Constants.DefaultConfig;
import com.xyoye.dandanplay.utils.DanmuFilterUtils;
import com.xyoye.dandanplay.utils.DanmuUtils;
import com.xyoye.dandanplay.utils.HashUtils;
import com.xyoye.dandanplay.utils.Lifeful;
import com.xyoye.dandanplay.utils.SubtitleConverter;
import com.xyoye.dandanplay.utils.database.DataBaseManager;
import com.xyoye.dandanplay.utils.net.CommJsonEntity;
import com.xyoye.dandanplay.utils.net.CommJsonObserver;
import com.xyoye.dandanplay.utils.net.CommOtherDataObserver;
import com.xyoye.dandanplay.utils.net.NetworkConsumer;
import com.xyoye.dandanplay.utils.net.RetroFactory;
import com.xyoye.dandanplay.utils.net.service.RetrofitService;
import com.xyoye.dandanplay.utils.net.okhttp.OkHttpEngine;
import com.xyoye.dandanplay.utils.net.service.SubtitleRetrofitService;
import com.xyoye.player.commom.bean.SubtitleBean;
import com.xyoye.player.commom.listener.OnDanmakuListener;
import com.xyoye.player.commom.listener.PlayerViewListener;
import com.xyoye.player.commom.receiver.BatteryBroadcastReceiver;
import com.xyoye.player.commom.receiver.HeadsetBroadcastReceiver;
import com.xyoye.player.commom.receiver.PlayerReceiverListener;
import com.xyoye.player.commom.receiver.ScreenBroadcastReceiver;
import com.xyoye.player.commom.utils.Constants;
import com.xyoye.player.danmaku.danmaku.model.BaseDanmaku;
import com.xyoye.player.exoplayer.ExoPlayerView;
import com.xyoye.player.ijkplayer.IjkPlayerView;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/**
 * Created by xyoye on 2018/7/4 0004.
 * <p>
 * 播放器页面（不需支持换肤）
 */

public class PlayerActivity extends AppCompatActivity implements Lifeful, PlayerReceiverListener {
    PlayerViewListener mPlayer;

    private boolean isInit = false;
    private String videoPath;
    private String videoTitle;
    private String danmuPath;
    private String zimuPath;
    private long currentPosition;
    private int episodeId;
    private long thunderTaskId;
    private int sourceOrigin;

    private List<SubtitleBean> subtitleList;

    //电源广播
    private BatteryBroadcastReceiver batteryReceiver;
    //锁屏广播
    private ScreenBroadcastReceiver screenReceiver;
    //耳机设备广播
    private HeadsetBroadcastReceiver headsetReceiver;
    //弹幕回调
    private OnDanmakuListener onDanmakuListener;
    //内部事件回调
    private IMediaPlayer.OnOutsideListener onOutsideListener;
    //字幕查询观察者
    CommOtherDataObserver<List<SubtitleBean>> subtitleObserver;

    //系统字幕下载路径
    public String subtitleDownloadPath;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //播放器类型
        if (AppConfig.getInstance().getPlayerType() == Constants.EXO_PLAYER) {
            mPlayer = new ExoPlayerView(this);
            setContentView((ExoPlayerView) mPlayer);
        } else {
            mPlayer = new IjkPlayerView(this);
            setContentView((IjkPlayerView) mPlayer);
        }

        //隐藏toolbar
        ActionBar supportActionBar = getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.hide();
        }

        subtitleList = new ArrayList<>();

        //注册监听广播
        batteryReceiver = new BatteryBroadcastReceiver(this);
        screenReceiver = new ScreenBroadcastReceiver(this);
        headsetReceiver = new HeadsetBroadcastReceiver(this);
        PlayerActivity.this.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        PlayerActivity.this.registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PlayerActivity.this.registerReceiver(headsetReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        //获取播放参数
        PlayParam playParam = getIntent().getParcelableExtra("video_data");

        videoPath = playParam.getVideoPath();
        videoTitle = playParam.getVideoTitle();
        danmuPath = playParam.getDanmuPath();
        zimuPath = playParam.getZimuPath();
        currentPosition = playParam.getCurrentPosition();
        episodeId = playParam.getEpisodeId();
        sourceOrigin = playParam.getSourceOrigin();
        thunderTaskId = playParam.getThunderTaskId();

        subtitleDownloadPath = AppConfig.getInstance().getDownloadFolder()
                + com.xyoye.dandanplay.utils.Constants.DefaultConfig.subtitleFolder;

        //初始化接口
        initListener();

        //初始化播放器
        initPlayer();
    }

    //初始化播放器
    private void initPlayer() {
        //初始化不同的播放器
        if (AppConfig.getInstance().getPlayerType() == Constants.EXO_PLAYER) {
            initExoPlayer();
        } else {
            initIjkPlayer();
        }

        isInit = true;
        //添加播放历史
        if (episodeId > 0 && AppConfig.getInstance().isLogin())
            addPlayHistory(episodeId);
    }

    private void initListener() {
        onDanmakuListener = new OnDanmakuListener() {
            @Override
            public boolean isValid() {
                //是否可发送弹幕
                if (!AppConfig.getInstance().isLogin()) {
                    ToastUtils.showShort("当前未登陆，不能发送弹幕");
                    return false;
                }
                if (!StringUtils.isEmpty(danmuPath)) {
                    File danmuFile = new File(danmuPath);
                    if (!danmuFile.exists()) {
                        ToastUtils.showShort("未加载弹幕文件");
                        return false;
                    }
                } else {
                    ToastUtils.showShort("未加载弹幕文件");
                    return false;
                }
                return true;
            }

            @Override
            public void onDataObtain(BaseDanmaku data) {
                if (episodeId == 0) {
                    writeDanmu(data, danmuPath);
                } else {
                    uploadDanmu(data);
                }
            }

            @Override
            public void setCloudFilter(boolean isOpen) {
                //启用或关闭云屏蔽
                AppConfig.getInstance().setCloudDanmuFilter(isOpen);
            }

            @Override
            public void deleteBlock(String text) {
                DanmuFilterUtils.getInstance().removeLocalFilter(text);
            }

            @Override
            public void addBlock(String text) {
                DanmuFilterUtils.getInstance().addLocalFilter(text);
            }
        };

        onOutsideListener = (what, extra) -> {
            switch (what) {
                //选择本地弹幕
                case Constants.INTENT_OPEN_DANMU: {
                    String folderPath = videoPath.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())
                            ? videoPath
                            : DefaultConfig.downloadPath;
                    new FileManagerDialog(PlayerActivity.this, folderPath, FileManagerDialog.SELECT_DANMU,
                            path -> {
                                DataBaseManager.getInstance()
                                        .selectTable("file")
                                        .update()
                                        .param("danmu_path", path)
                                        .where("file_path", videoPath)
                                        .postExecute();
                                EventBus.getDefault().post(UpdateFragmentEvent.updatePlay(PlayFragment.UPDATE_DATABASE_DATA));
                                mPlayer.changeDanmuSource(path);
                            }
                    ).show();
                }
                break;
                //选择本地字幕
                case Constants.INTENT_OPEN_SUBTITLE: {
                    String folderPath = videoPath.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())
                            ? videoPath
                            : DefaultConfig.downloadPath;
                    new FileManagerDialog(
                            PlayerActivity.this,
                            folderPath,
                            FileManagerDialog.SELECT_SUBTITLE,
                            path -> {
                                DataBaseManager.getInstance()
                                        .selectTable("file")
                                        .update()
                                        .param("zimu_path", path)
                                        .where("file_path", videoPath)
                                        .postExecute();
                                EventBus.getDefault().post(UpdateFragmentEvent.updatePlay(PlayFragment.UPDATE_DATABASE_DATA));
                                mPlayer.setSubtitlePath(path);
                            }
                    ).show();
                }
                break;
                //查询网络字幕
                case Constants.INTENT_QUERY_SUBTITLE:
                    querySubtitle(videoPath);
                    break;
                //选择网络字幕
                case Constants.INTENT_SELECT_SUBTITLE:
                    new SelectSubtitleDialog(
                            PlayerActivity.this,
                            subtitleList,
                            PlayerActivity.this::downloadSubtitle
                    ).show();
                    break;
                //自动选择网络字幕
                case Constants.INTENT_AUTO_SUBTITLE:
                    ToastUtils.showShort("自动加载字幕中");
                    SubtitleBean subtitleBean = subtitleList.get(0);
                    downloadSubtitle(subtitleBean.getName(), subtitleBean.getUrl());
                    break;
                //保存进度
                case Constants.INTENT_SAVE_CURRENT:
                    //更新内存中进度
                    SaveCurrentEvent event = new SaveCurrentEvent();
                    event.setCurrentPosition(extra);
                    event.setFolderPath(FileUtils.getDirName(videoPath));
                    event.setVideoPath(videoPath);
                    EventBus.getDefault().post(event);
                    //更新数据库中进度
                    DataBaseManager.getInstance()
                            .selectTable("file")
                            .update()
                            .param("current_position", event.getCurrentPosition())
                            .where("folder_path", event.getFolderPath())
                            .where("file_path", event.getVideoPath())
                            .postExecute();
                    break;
                //设置全屏
                case Constants.INTENT_RESET_FULL_SCREEN:
                    setFullScreen();
                    break;
                //播放失败
                case Constants.INTENT_PLAY_FAILED:
                    CommonDialog.Builder builder = new CommonDialog
                            .Builder(this)
                            .setDismissListener(dialog -> {
                                onOutsideListener.onAction(Constants.INTENT_PLAY_END, 0);
                                PlayerActivity.this.finish();
                            })
                            .setOkListener(dialog ->
                                    startActivity(new Intent(this, PlayerSettingActivity.class)))
                            .setAutoDismiss()
                            .setNightSkin()
                            .setTouchNotCancel();
                    if (sourceOrigin == PlayerManagerActivity.SOURCE_ONLINE_PREVIEW) {
                        builder.setHideOk(false).build()
                                .show("播放失败，资源文件无法下载，请重试或切换其它资源", "", "退出播放");
                    } else {
                        builder.setHideOk(true).build()
                                .show("播放失败，请尝试更改播放器设置，或者切换其它播放内核", "播放器设置", "退出播放");
                    }
                    break;
                //视频播放结束
                case Constants.INTENT_PLAY_END:
                    if (sourceOrigin == PlayerManagerActivity.SOURCE_ONLINE_PREVIEW && thunderTaskId > 0) {
                        XLTaskHelper.getInstance().stopTask(thunderTaskId);
                        FileUtils.deleteAllInDir(DefaultConfig.cacheFolderPath);
                    }
                    break;
            }
        };

        //网络字幕请求回调
        subtitleObserver = new CommOtherDataObserver<List<SubtitleBean>>() {
            @Override
            public void onSuccess(List<SubtitleBean> subtitleList) {
                if (subtitleList.size() > 0) {
                    //按评分排序
                    Collections.sort(subtitleList, (o1, o2) -> o2.getRank() - o1.getRank());
                    PlayerActivity.this.subtitleList.clear();
                    PlayerActivity.this.subtitleList.addAll(subtitleList);
                    mPlayer.onSubtitleQuery(subtitleList.size());
                    LogUtils.e("获取字幕成功，共" + subtitleList.size() + "条");
                }
            }

            @Override
            public void onError(int errorCode, String message) {

            }
        };
    }

    private void initIjkPlayer() {
        IjkPlayerView ijkPlayerView = (IjkPlayerView) mPlayer;

        boolean autoLoadLocalSubtitle = AppConfig.getInstance().isAutoLoadLocalSubtitle()
                && TextUtils.isEmpty(zimuPath);
        boolean autoLoadNetworkSubtitle = AppConfig.getInstance().isAutoLoadNetworkSubtitle()
                && TextUtils.isEmpty(zimuPath);

        ijkPlayerView
                //设置弹幕事件回调，要在初始化弹幕之前完成
                .setDanmakuListener(onDanmakuListener)
                //内部事件回调
                .setOnInfoListener(onOutsideListener)
                //设置普通屏蔽弹幕
                .setNormalFilterData(DanmuFilterUtils.getInstance().getLocalFilter())
                //设置云屏蔽数据
                .setCloudFilterData(DanmuFilterUtils.getInstance().getCloudFilter(),
                        AppConfig.getInstance().isCloudDanmuFilter())
                //设置弹幕数据源
                .setDanmakuSource(danmuPath)
                //默认展示弹幕
                .showOrHideDanmaku(true)
                //跳转至上一次播放进度
                .setSkipTip(currentPosition)
                //是否开启网络字幕
                .setNetworkSubtitle(AppConfig.getInstance().isUseNetWorkSubtitle())
                //是否自动加载同名字幕
                .setAutoLoadLocalSubtitle(autoLoadLocalSubtitle)
                //是否自动加载网络字幕
                .setAutoLoadNetworkSubtitle(autoLoadNetworkSubtitle)
                //设置标题
                .setTitle(videoTitle)
                //设置字幕下载路径
                .setSubtitleFolder(subtitleDownloadPath)
                //设置视频路径
                .setVideoPath(videoPath)
                .start();

        mPlayer.setSubtitlePath(zimuPath);
    }

    private void initExoPlayer() {
        ExoPlayerView exoPlayerView = (ExoPlayerView) mPlayer;

        boolean autoLoadLocalSubtitle = AppConfig.getInstance().isAutoLoadLocalSubtitle()
                && TextUtils.isEmpty(zimuPath);
        boolean autoLoadNetworkSubtitle = AppConfig.getInstance().isAutoLoadNetworkSubtitle()
                && TextUtils.isEmpty(zimuPath);

        exoPlayerView
                //设置弹幕事件回调，要在初始化弹幕之前完成
                .setDanmakuListener(onDanmakuListener)
                //内部事件回调
                .setOnInfoListener(onOutsideListener)
                //设置普通屏蔽弹幕
                .setNormalFilterData(DanmuFilterUtils.getInstance().getLocalFilter())
                //设置云屏蔽数据
                .setCloudFilterData(DanmuFilterUtils.getInstance().getCloudFilter(),
                        AppConfig.getInstance().isCloudDanmuFilter())
                //设置弹幕数据源
                .setDanmakuSource(danmuPath)
                //默认展示弹幕
                .showOrHideDanmaku(true)
                //跳转至上一次播放进度
                .setSkipTip(currentPosition)
                //是否开启网络字幕
                .setNetworkSubtitle(AppConfig.getInstance().isUseNetWorkSubtitle())
                //是否自动加载同名字幕
                .setAutoLoadLocalSubtitle(autoLoadLocalSubtitle)
                //是否自动加载网络字幕
                .setAutoLoadNetworkSubtitle(autoLoadNetworkSubtitle)
                //设置标题
                .setTitle(videoTitle)
                //设置字幕下载路径
                .setSubtitleFolder(subtitleDownloadPath)
                //设置视频路径
                .setVideoPath(videoPath)
                .start();

        mPlayer.setSubtitlePath(zimuPath);
    }

    @Override
    protected void onDestroy() {
        mPlayer.onDestroy();
        this.unregisterReceiver(batteryReceiver);
        this.unregisterReceiver(screenReceiver);
        this.unregisterReceiver(headsetReceiver);
        if (subtitleObserver != null)
            subtitleObserver.dispose();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (isInit)
            mPlayer.onResume();
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (isInit)
            mPlayer.onPause();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mPlayer.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mPlayer.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPlayer.configurationChanged(newConfig);
    }

    @Override
    public void onBatteryChanged(int status, int progress) {
        mPlayer.setBatteryChanged(status, progress);
    }

    @Override
    public void onScreenLocked() {
        mPlayer.onScreenLocked();
    }

    @Override
    public void onHeadsetRemoved(){
        mPlayer.onPause();
    }

    //设置全屏
    private void setFullScreen() {
        //沉浸式状态栏
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //开启屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    //上传一条弹幕到弹弹
    private void uploadDanmu(BaseDanmaku data) {
        BigDecimal bigDecimal = new BigDecimal(data.getTime() / 1000.00)
                .setScale(2, BigDecimal.ROUND_HALF_UP);
        int type = data.getType();
        if (type != 1 && type != 4 && type != 5) {
            type = 1;
        }
        String time = bigDecimal.toString();
        String mode = type + "";
        String color = (data.textColor & 0x00FFFFFF) + "";
        String comment = String.valueOf(data.text);
        DanmuUploadParam uploadParam = new DanmuUploadParam(time, mode, color, comment);
        UploadDanmuBean.uploadDanmu(uploadParam, episodeId + "", new CommJsonObserver<UploadDanmuBean>(this) {
            @Override
            public void onSuccess(UploadDanmuBean bean) {
                LogUtils.d("upload danmu success: text：" + data.text + "  cid：" + bean.getCid());
            }

            @Override
            public void onError(int errorCode, String message) {
                ToastUtils.showShort(message);
            }
        }, new NetworkConsumer());
    }

    //写入一条弹幕到本地
    private void writeDanmu(BaseDanmaku data, String danmuPath) {
        BigDecimal bigDecimal = new BigDecimal(data.getTime() / 1000.00)
                .setScale(2, BigDecimal.ROUND_HALF_UP);
        int type = data.getType();
        if (type != 1 && type != 4 && type != 5) {
            type = 1;
        }
        String time = bigDecimal.toString();
        String mode = String.valueOf(type);
        String color = String.valueOf(data.textColor & 0x00FFFFFF);
        color = "16777215".equals(color) ? "0" : color;
        String comment = String.valueOf(data.text);
        String unixTime = String.valueOf(System.currentTimeMillis() / 1000);
        String danmuText = "<d p=\"" + time + "," + mode + ",25" + "," + color + "," + unixTime + ",0,0,0\">" + comment + "</d>";
        DanmuUtils.insertOneDanmu(danmuText, danmuPath);
    }

    //增加播放历史
    private void addPlayHistory(int episodeId) {
        if (episodeId > 0) {
            PlayHistoryBean.addPlayHistory(episodeId, new CommJsonObserver<CommJsonEntity>(this) {
                @Override
                public void onSuccess(CommJsonEntity commJsonEntity) {
                    LogUtils.d("add history success: episodeId：" + episodeId);
                }

                @Override
                public void onError(int errorCode, String message) {
                    LogUtils.e("add history fail: episodeId：" + episodeId + "  message：" + message);
                }
            }, new NetworkConsumer());
        }
    }

    //查询字幕
    private void querySubtitle(String videoPath) {
        //只查找本地视频字幕
        String localPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (!videoPath.startsWith(localPath))
            return;
        String thunderHash = HashUtils.getFileSHA1(videoPath);
        String shooterHash = HashUtils.getFileHash(videoPath);
        if (!StringUtils.isEmpty(thunderHash) && !StringUtils.isEmpty(shooterHash)) {
            Map<String, String> shooterParams = new HashMap<>();
            shooterParams.put("filehash", shooterHash);
            shooterParams.put("pathinfo", FileUtils.getFileName(videoPath));
            shooterParams.put("format", "json");
            shooterParams.put("lang", "Chn");
            SubtitleRetrofitService service = RetroFactory.getSubtitleInstance();

            Observable<List<SubtitleBean.Shooter>> shooterObservable = service.queryShooter(shooterParams)
                    .onErrorReturnItem(new ArrayList<>());

            service.queryThunder(thunderHash)
                    .onErrorReturnItem(new SubtitleBean.Thunder())
                    .zipWith(shooterObservable, (thunder, shooters) ->
                            SubtitleConverter.transform(thunder, shooters, videoPath))
                    .doOnSubscribe(new NetworkConsumer())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(subtitleObserver);
        }
    }

    //下载字幕
    private void downloadSubtitle(String fileName, String link) {
        Request request = new Request.Builder().url(link).build();
        Call call = OkHttpEngine.getInstance().getOkHttpClient().newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                ToastUtils.showShort("下载字幕文件失败");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.body() != null) {
                    String folderPath = AppConfig.getInstance().getDownloadFolder() + DefaultConfig.subtitleFolder;
                    File folder = new File(folderPath);
                    if (!folder.exists()) {
                        if (!folder.mkdirs()) {
                            ToastUtils.showShort("创建字幕文件夹失败");
                            return;
                        }
                    }
                    String filePath = folderPath + "/" + fileName;
                    boolean isSaveFile = FileIOUtils.writeFileFromIS(filePath, response.body().byteStream());
                    if (isSaveFile) {
                        runOnUiThread(() -> {
                            DataBaseManager.getInstance()
                                    .selectTable("file")
                                    .update()
                                    .param("zimu_path", filePath)
                                    .where("file_path", videoPath)
                                    .postExecute();
                            EventBus.getDefault().post(UpdateFragmentEvent.updatePlay(PlayFragment.UPDATE_DATABASE_DATA));
                            mPlayer.setSubtitlePath(filePath);
                        });
                    } else {
                        ToastUtils.showShort("保存字幕文件失败");
                    }
                }
            }
        });
    }

    @Override
    public boolean isAlive() {
        return getBaseContext() != null && !(this.isDestroyed() || this.isFinishing());
    }
}
