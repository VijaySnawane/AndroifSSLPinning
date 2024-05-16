package com.example.androidsslpinning

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.randstadrisesmart.talentmobility.R
import com.randstadrisesmart.talentmobility.data.local.pref.AppSharedPref
import com.randstadrisesmart.talentmobility.data.model.videoCall.VideoCallTimerUpdate
import com.randstadrisesmart.talentmobility.domain.model.callfeedback.VideoCallsDetailsWithService
import com.randstadrisesmart.talentmobility.presentation.main.video100ms.Video100MSActivity.Companion.NOTIFICATION_ID_100MS_VIDEO_CALL
import com.randstadrisesmart.talentmobility.presentation.main.video100ms.Video100MSActivity.Companion.callDetails
import com.randstadrisesmart.talentmobility.presentation.main.video100ms.Video100MSActivity.Companion.vidActionStart
import com.randstadrisesmart.talentmobility.presentation.main.video100ms.Video100MSActivity.Companion.vidActionStop
import com.randstadrisesmart.talentmobility.presentation.main.videomeeting.CoachVideoCallActivity
import com.randstadrisesmart.talentmobility.utils.AppLogger
import com.randstadrisesmart.talentmobility.utils.CommonUtils
import com.randstadrisesmart.uicomponents.tray.TrayDataClass
import com.randstadrisesmart.uicomponents.utils.AppConstant
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import live.hms.video.audio.HMSAudioManager
import live.hms.video.error.HMSException
import live.hms.video.media.settings.HMSAudioTrackSettings
import live.hms.video.media.settings.HMSTrackSettings
import live.hms.video.media.settings.HMSVideoTrackSettings
import live.hms.video.media.tracks.HMSTrack
import live.hms.video.sdk.HMSSDK
import live.hms.video.sdk.HMSUpdateListener
import live.hms.video.sdk.models.*
import live.hms.video.sdk.models.enums.HMSPeerUpdate
import live.hms.video.sdk.models.enums.HMSRoomUpdate
import live.hms.video.sdk.models.enums.HMSTrackUpdate
import live.hms.video.sdk.models.trackchangerequest.HMSChangeTrackStateRequest
import javax.inject.Inject

@Suppress("unused")
@AndroidEntryPoint
class Video100MSService : Service(), HMSUpdateListener {

  private var mAudioOutputListDevice: MutableList<TrayDataClass> = mutableListOf()

  var isVideoMuteWhileGoingBackground: Boolean = false

  private var _audioOutputListDeviceLiveData = MutableLiveData<MutableList<TrayDataClass>>()
  var audioOutputListDeviceLiveData: LiveData<MutableList<TrayDataClass>> =
    _audioOutputListDeviceLiveData

  companion object {
    const val VIDEO_SECONDS_DELAY = 1000L
    const val logTag = "Video100MSService"
    const val chatGroup="chats_group"
    const val video="Video"
  }

  @Inject
  lateinit var mAppSharedPref: AppSharedPref

  private var mVideoHMSSDK: HMSSDK? = null

  private var videoCallTimeInSeconds = 0L
  private var videoCallTimerJob: Job? = null
  private val videoCallTimerJobScope = CoroutineScope(Dispatchers.Main + Job())
  private val mVideo100MSServiceBinder: VideoCallServiceBinder = VideoCallServiceBinder()
  private var mNotificationManager: NotificationManager? = null
  private var notification: Notification? = null
  private var mVideoCallsDetailsWithService: VideoCallsDetailsWithService? = null

  private var mVideo100MsCallBacks: Video100MsCallBacks? = null

  private val _mVideoCallDuration = MutableStateFlow(VideoCallTimerUpdate())
  val mVideoCallDuration: StateFlow<VideoCallTimerUpdate> = _mVideoCallDuration

  fun setCallbacks(callbacks: Video100MsCallBacks) {
    mVideo100MsCallBacks = callbacks
  }


  inner class VideoCallServiceBinder : Binder() {
    fun getVideo100MSService(): Video100MSService {
      return this@Video100MSService
    }
  }

  override fun onBind(p0: Intent?): IBinder {
    return mVideo100MSServiceBinder
  }

  override fun onCreate() {
    super.onCreate()
    mVideoHMSSDK = HMSSDK
      .Builder(this)
      .setTrackSettings(getHMSTrackSettings())
      .build()
    mVideoHMSSDK?.let {
      prepareAudioOutputList(
        it.getAudioDevicesList(),
        it.getAudioOutputRouteType()
      )
    }
    mVideoHMSSDK?.setAudioDeviceChangeListener(object :
      HMSAudioManager.AudioManagerDeviceChangeListener {
      override fun onAudioDeviceChanged(
        selectedAudioDevice: HMSAudioManager.AudioDevice,
        availableAudioDevices: Set<HMSAudioManager.AudioDevice>
      ) {
        prepareAudioOutputList(
          availableAudioDevices.toList(),
          selectedAudioDevice
        )
      }

      override fun onError(e: HMSException) {
        mVideo100MsCallBacks?.handleException(Exception())
      }
    })
  }

  private fun getHMSTrackSettings(): HMSTrackSettings {
    return HMSTrackSettings.Builder()
      .audio(
        HMSAudioTrackSettings.Builder()
          .setUseHardwareAcousticEchoCanceler(true)
          .enableAutomaticGainControl(true)
          .initialState(HMSTrackSettings.InitState.UNMUTED)
          .build()
      )
      .video(
        HMSVideoTrackSettings.Builder().disableAutoResize(false)
          .forceSoftwareDecoder(true)
          .disableAutoResize(true)
          .initialState(HMSTrackSettings.InitState.UNMUTED)
          .build()
      )
      .build()
  }

  private fun prepareAudioOutputList(
    audioDevicesList: List<HMSAudioManager.AudioDevice>,
    audioOutputRouteType: HMSAudioManager.AudioDevice?
  ) {
    AppLogger.i("$logTag $audioOutputRouteType")
    synchronized(this)
    {
      mAudioOutputListDevice.clear()
      audioDevicesList.forEach { audioDevice ->
        when (audioDevice) {
          HMSAudioManager.AudioDevice.SPEAKER_PHONE -> {
            mAudioOutputListDevice.add(
              TrayDataClass(
                mAppSharedPref.getLocalString(R.string.text_speaker_on),
                CoachVideoCallActivity.AUDIO_PHONE_SPEAKER,
                mDrawable = R.drawable.ic_speaker,
                mActiveState = audioOutputRouteType == HMSAudioManager.AudioDevice.SPEAKER_PHONE
              )
            )
          }
          HMSAudioManager.AudioDevice.EARPIECE -> {
            mAudioOutputListDevice.add(
              TrayDataClass(
                mAppSharedPref.getLocalString(R.string.text_phone),
                CoachVideoCallActivity.AUDIO_PHONE_INTERNAL_SPEAKER,
                mDrawable = R.drawable.ic_phone,
                mActiveState = audioOutputRouteType == HMSAudioManager.AudioDevice.EARPIECE
              )
            )
          }
          HMSAudioManager.AudioDevice.BLUETOOTH -> {
            mAudioOutputListDevice.add(
              TrayDataClass(
                mAppSharedPref.getLocalString(R.string.bluetooth),
                CoachVideoCallActivity.AUDIO_BLUE_TOOTH,
                mDrawable = R.drawable.ic_bluetooth,
                mActiveState = audioOutputRouteType == HMSAudioManager.AudioDevice.BLUETOOTH
              )
            )
          }
          HMSAudioManager.AudioDevice.WIRED_HEADSET -> {
            mAudioOutputListDevice.add(
              TrayDataClass(
                mAppSharedPref.getLocalString(R.string.wired_headset),
                CoachVideoCallActivity.AUDIO_WIRED_HEADSET,
                mDrawable = R.drawable.ic_headphone,
                mActiveState = audioOutputRouteType == HMSAudioManager.AudioDevice.WIRED_HEADSET
              )
            )
          }
          else -> {}
        }
      }
      mAudioOutputListDevice.add(
        TrayDataClass(
          mAppSharedPref.getLocalString(R.string.text_cancel),
          CoachVideoCallActivity.AUDIO_CANCEL,
          mDrawable = R.drawable.ic_cancel,
          mIgnoreSelection = true
        )
      )
      AppLogger.i("$logTag Devices Found ========Start")
      mAudioOutputListDevice.map {
        AppLogger.i("$logTag ${it.getTitle()} ${it.getState()}")
      }
      AppLogger.i("$logTag Devices Found ========End============")
      AppLogger.i("$logTag current active device ${mVideoHMSSDK?.getAudioOutputRouteType()} ")
      _audioOutputListDeviceLiveData.value = mAudioOutputListDevice
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == vidActionStart) {
      AppLogger.i("$logTag onStartCommand")
      mVideo100MsCallBacks?.showLocalActivityProgress()
      getCallDetailsParameter(intent)
      showNotification()
      connectTo100MsVideoCall()
    } else if (intent?.action == vidActionStop) {
      stopVideoService()
    }
    return START_NOT_STICKY
  }

  private fun getCallDetailsParameter(intent: Intent?) {
    mVideoCallsDetailsWithService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent?.getParcelableExtra(callDetails, VideoCallsDetailsWithService::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent?.getParcelableExtra(callDetails)
    }
  }

  private fun connectTo100MsVideoCall() {
    AppLogger.i("$logTag connectTo100MsVideoCall")
    mVideoHMSSDK?.join(
      HMSConfig(
        userName = mAppSharedPref.getUserFirstName()
          .padEnd(mAppSharedPref.getUserFirstName().length + 1)
          .plus(mAppSharedPref.getUserLastName())
          .trim(),
        authtoken = mVideoCallsDetailsWithService?.twilioAccessToken ?: ""
      ), this
    )
  }

  private fun showNotification() {
    val intentMainLanding = Intent(this, Video100MSActivity::class.java)
    intentMainLanding.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        intentMainLanding,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
    if (mNotificationManager == null) {
      mNotificationManager =
        this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    mNotificationManager?.createNotificationChannelGroup(
      NotificationChannelGroup(chatGroup, video)
    )
    val notificationChannel =
      NotificationChannel(
        AppConstant.VIDEO_SERVICE_CHANNEL, mAppSharedPref.getLocalString(R.string.app_name),
        NotificationManager.IMPORTANCE_MIN
      )
    notificationChannel.enableLights(false)
    notificationChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
    mNotificationManager?.createNotificationChannel(notificationChannel)
    val builder = NotificationCompat.Builder(this, AppConstant.VIDEO_SERVICE_CHANNEL)
    builder.setContentTitle(mAppSharedPref.getLocalString(R.string.coach_session))
      .setContentText(mAppSharedPref.getLocalString(R.string.ongoing_video_call))
      .setSmallIcon(R.drawable.ic_push_icon)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setWhen(0)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
    builder.color = ContextCompat.getColor(this@Video100MSService, R.color.brand_blue)
    notification = builder.build()
    notification?.flags = notification?.flags?.or(Notification.FLAG_NO_CLEAR)
    startForeground(NOTIFICATION_ID_100MS_VIDEO_CALL, notification)
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    stopVideoService()
    super.onTaskRemoved(rootIntent)
  }

  private fun stopVideoService() {
    leaveMeeting()
    stopVideoCallTimerUpdates()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun startVideoCallTimerUpdates() {
    videoCallTimerJob = videoCallTimerJobScope.launch {
      while (true) {
        if (!isActive)
          return@launch
        withContext(Dispatchers.IO) {
          val formattedTime =
            CommonUtils.getFormattedStopWatch(videoCallTimeInSeconds * VIDEO_SECONDS_DELAY)
          videoCallTimeInSeconds = videoCallTimeInSeconds.inc()
          withContext(Dispatchers.Main)
          {
            _mVideoCallDuration.emit(
              VideoCallTimerUpdate(
                showTimerText = true,
                timerDuration = formattedTime
              )
            )
          }
          delay(VIDEO_SECONDS_DELAY)
        }
      }
    }
  }

  private fun stopVideoCallTimerUpdates() {
    videoCallTimerJobScope.launch {
      _mVideoCallDuration.emit(
        VideoCallTimerUpdate()
      )
    }
    videoCallTimeInSeconds = 0
    videoCallTimerJob?.cancel()
    videoCallTimerJob = null
  }

  override fun onDestroy() {
    super.onDestroy()
    stopVideoService()
    videoCallTimerJobScope.cancel()
  }

  override fun onChangeTrackStateRequest(details: HMSChangeTrackStateRequest) {
    //Np Op
  }

  override fun onError(error: HMSException) {
    AppLogger.i("$logTag  777  ${error.description} ${error.code}")
    mVideo100MsCallBacks?.hideLocalActivityProgress()
    stopVideoService()
    mVideo100MsCallBacks?.handleException(error)
  }

  override fun onJoin(room: HMSRoom) {
    AppLogger.i("$logTag onJoin $mVideo100MsCallBacks")
    mVideo100MsCallBacks?.hideLocalActivityProgress()
    mVideo100MsCallBacks?.updateParticipantVideo()
    mVideo100MsCallBacks?.updateCoachVideo()
    getVideoCallRemoteParticipant()?.let {
      if (videoCallTimerJob == null) {
        startVideoCallTimerUpdates()
      }
      mVideo100MsCallBacks?.showCoachOnTheCall()
    }
  }

  override fun onMessageReceived(message: HMSMessage) {
    //Np Op
  }

  override fun onPeerUpdate(type: HMSPeerUpdate, peer: HMSPeer) {
    when (type) {
      HMSPeerUpdate.PEER_JOINED -> {
        if (peer.isLocal) {
          mVideo100MsCallBacks?.updateParticipantVideo()
        } else {
          if (videoCallTimerJob == null) {
            startVideoCallTimerUpdates()
          }
          mVideo100MsCallBacks?.updateCoachVideo()
        }
      }
      HMSPeerUpdate.PEER_LEFT -> {
        if (peer.isLocal) {
          mVideo100MsCallBacks?.participantLeftMeeting()
        } else {
          stopVideoCallTimerUpdates()
          mVideo100MsCallBacks?.coachLeftMeeting()
        }
      }
      else -> {}
    }
  }

  override fun onRoleChangeRequest(request: HMSRoleChangeRequest) {
    //Np Op
  }

  override fun onRoomUpdate(type: HMSRoomUpdate, hmsRoom: HMSRoom) {
    //Np Op
  }

  override fun onTrackUpdate(type: HMSTrackUpdate, track: HMSTrack, peer: HMSPeer) {
    when (type) {
      HMSTrackUpdate.TRACK_UNMUTED, HMSTrackUpdate.TRACK_MUTED, HMSTrackUpdate.TRACK_ADDED, HMSTrackUpdate.TRACK_REMOVED -> {
        if (peer.isLocal) {
          mVideo100MsCallBacks?.updateParticipantVideo()
        } else {
          mVideo100MsCallBacks?.updateCoachVideo()
        }
      }
      else -> {}
    }
  }

  fun getVideoCallLocalParticipant(): HMSLocalPeer? {
    return mVideoHMSSDK?.getLocalPeer()
  }

  fun getVideoCallRemoteParticipant(): HMSRemotePeer? {
    return if (mVideoHMSSDK?.getRemotePeers()?.isNotEmpty() == true) {
      mVideoHMSSDK?.getRemotePeers()?.first()
    } else {
      null
    }
  }

  fun onOffParticipantVideo() {
    mVideoHMSSDK?.getLocalPeer()?.let { peer ->
      if (peer.videoTrack?.isMute == true) {
        peer.videoTrack?.setMute(false)
        mVideo100MsCallBacks?.onParticipantVideo(on = true)
      } else {
        peer.videoTrack?.setMute(true)
        mVideo100MsCallBacks?.onParticipantVideo(on = false)
      }
    }
  }

  fun onOffParticipantAudio() {
    mVideoHMSSDK?.getLocalPeer()?.let { peer ->
      if (peer.audioTrack?.isMute == true) {
        peer.audioTrack?.setMute(false)
        mVideo100MsCallBacks?.onParticipantAudio(on = true)
      } else {
        peer.audioTrack?.setMute(true)
        mVideo100MsCallBacks?.onParticipantAudio(on = false)
      }
    }
  }

  private fun leaveMeeting() {
    mVideoHMSSDK?.leave()
  }

  fun switchAudioOutputFromUi(id: Int) {
    when (id) {
      CoachVideoCallActivity.AUDIO_PHONE_SPEAKER -> {
        mVideoHMSSDK?.switchAudioOutput(HMSAudioManager.AudioDevice.SPEAKER_PHONE)
      }
      CoachVideoCallActivity.AUDIO_BLUE_TOOTH -> {
        mVideoHMSSDK?.switchAudioOutput(HMSAudioManager.AudioDevice.BLUETOOTH)
      }
      CoachVideoCallActivity.AUDIO_WIRED_HEADSET -> {
        mVideoHMSSDK?.switchAudioOutput(HMSAudioManager.AudioDevice.WIRED_HEADSET)
      }
      CoachVideoCallActivity.AUDIO_PHONE_INTERNAL_SPEAKER -> {
        mVideoHMSSDK?.switchAudioOutput(HMSAudioManager.AudioDevice.EARPIECE)
      }
      else -> {
        mVideoHMSSDK?.switchAudioOutput(HMSAudioManager.AudioDevice.AUTOMATIC)
      }
    }
  }

  fun restoreParameters(): VideoCallsDetailsWithService? {
    return mVideoCallsDetailsWithService
  }
}


