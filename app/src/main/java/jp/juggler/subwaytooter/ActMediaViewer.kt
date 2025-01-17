package jp.juggler.subwaytooter

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.view.Window
import android.widget.TextView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MediaSourceEventListener
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import it.sephiroth.android.library.exif2.ExifInterface
import jp.juggler.subwaytooter.api.TootApiClient
import jp.juggler.subwaytooter.api.TootApiResult
import jp.juggler.subwaytooter.api.TootTask
import jp.juggler.subwaytooter.api.TootTaskRunner
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.dialog.ActionsDialog
import jp.juggler.subwaytooter.util.ProgressResponseBody
import jp.juggler.subwaytooter.view.PinchBitmapView
import jp.juggler.util.*
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.*
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max

class ActMediaViewer : AppCompatActivity(), View.OnClickListener {
	
	companion object {
		
		internal val log = LogCategory("ActMediaViewer")
		
		internal val download_history_list = LinkedList<DownloadHistory>()
		internal const val DOWNLOAD_REPEAT_EXPIRE = 3000L
		internal const val short_limit = 5000L
		
		private const val PERMISSION_REQUEST_CODE = 1
		
		internal const val EXTRA_IDX = "idx"
		internal const val EXTRA_DATA = "data"
		internal const val EXTRA_SERVICE_TYPE = "serviceType"
		
		internal const val STATE_PLAYER_POS = "playerPos"
		internal const val STATE_PLAYER_PLAY_WHEN_READY = "playerPlayWhenReady"
		
		internal fun <T : TootAttachmentLike> encodeMediaList(list : ArrayList<T>?) =
			list?.encodeJson()?.toString() ?: "[]"
		
		internal fun decodeMediaList(src : String?) =
			ArrayList<TootAttachment>().apply {
				src?.toJsonArray()?.forEach {
					if(it !is JSONObject) return@forEach
					add(TootAttachment.decodeJson(it))
				}
			}
		
		fun open(
			activity : ActMain,
			serviceType : ServiceType,
			list : ArrayList<TootAttachmentLike>,
			idx : Int
		) {
			val intent = Intent(activity, ActMediaViewer::class.java)
			intent.putExtra(EXTRA_IDX, idx)
			intent.putExtra(EXTRA_SERVICE_TYPE, serviceType.ordinal)
			intent.putExtra(EXTRA_DATA, encodeMediaList(list))
			activity.startActivity(intent)
			activity.overridePendingTransition(R.anim.slide_from_bottom, android.R.anim.fade_out)
		}
	}
	
	internal var idx : Int = 0
	private lateinit var media_list : ArrayList<TootAttachment>
	private lateinit var serviceType : ServiceType
	
	private lateinit var pbvImage : PinchBitmapView
	private lateinit var btnPrevious : View
	private lateinit var btnNext : View
	private lateinit var tvError : TextView
	private lateinit var exoPlayer : SimpleExoPlayer
	private lateinit var exoView : PlayerView
	private lateinit var svDescription : View
	private lateinit var tvDescription : TextView
	private lateinit var tvStatus : TextView
	
	internal var buffering_last_shown : Long = 0
	
	private val player_listener = object : Player.EventListener {
		
		override fun onTimelineChanged(
			timeline : Timeline?,
			manifest : Any?,
			reason : Int
		) {
			log.d("exoPlayer onTimelineChanged manifest=$manifest reason=$reason")
		}
		
		override fun onSeekProcessed() {
		}
		
		override fun onShuffleModeEnabledChanged(shuffleModeEnabled : Boolean) {
		}
		
		override fun onTracksChanged(
			trackGroups : TrackGroupArray?,
			trackSelections : TrackSelectionArray?
		) {
			log.d("exoPlayer onTracksChanged")
			
		}
		
		override fun onLoadingChanged(isLoading : Boolean) {
			// かなり頻繁に呼ばれる
			// warning.d( "exoPlayer onLoadingChanged %s" ,isLoading );
		}
		
		override fun onPlayerStateChanged(playWhenReady : Boolean, playbackState : Int) {
			// かなり頻繁に呼ばれる
			// warning.d( "exoPlayer onPlayerStateChanged %s %s", playWhenReady, playbackState );
			if(playWhenReady && playbackState == Player.STATE_BUFFERING) {
				val now = SystemClock.elapsedRealtime()
				if(now - buffering_last_shown >= short_limit && exoPlayer.duration >= short_limit) {
					buffering_last_shown = now
					showToast(this@ActMediaViewer, false, R.string.video_buffering)
				}
				/*
					exoPlayer.getDuration() may returns negative value (TIME_UNSET ,same as Long.MIN_VALUE + 1).
				*/
			}
		}
		
		override fun onRepeatModeChanged(repeatMode : Int) {
			log.d("exoPlayer onRepeatModeChanged %d", repeatMode)
		}
		
		override fun onPlayerError(error : ExoPlaybackException) {
			log.d("exoPlayer onPlayerError")
			showToast(this@ActMediaViewer, error, "player error.")
		}
		
		override fun onPositionDiscontinuity(reason : Int) {
			log.d("exoPlayer onPositionDiscontinuity reason=$reason")
		}
		
		override fun onPlaybackParametersChanged(playbackParameters : PlaybackParameters?) {
			log.d("exoPlayer onPlaybackParametersChanged")
			
		}
	}
	
	override fun onSaveInstanceState(outState : Bundle?) {
		super.onSaveInstanceState(outState)
		
		outState ?: return
		
		outState.putInt(EXTRA_IDX, idx)
		outState.putInt(EXTRA_SERVICE_TYPE, serviceType.ordinal)
		outState.putString(EXTRA_DATA, encodeMediaList(media_list))
		
		outState.putLong(STATE_PLAYER_POS, exoPlayer.currentPosition)
		outState.putBoolean(STATE_PLAYER_PLAY_WHEN_READY, exoPlayer.playWhenReady)
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		App1.setActivityTheme(this, true, R.style.AppTheme_Dark_NoActionBar)
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		
		val intent = intent
		
		this.idx = savedInstanceState?.getInt(EXTRA_IDX) ?: intent.getIntExtra(EXTRA_IDX, idx)
		
		this.serviceType = ServiceType.values()[
			savedInstanceState?.getInt(EXTRA_SERVICE_TYPE)
				?: intent.getIntExtra(EXTRA_SERVICE_TYPE, 0)
		]
		
		this.media_list = decodeMediaList(
			savedInstanceState?.getString(EXTRA_DATA)
				?: intent.getStringExtra(EXTRA_DATA)
		)
		
		if(idx < 0 || idx >= media_list.size) idx = 0
		
		initUI()
		
		load(savedInstanceState)
	}
	
	override fun onDestroy() {
		super.onDestroy()
		pbvImage.setBitmap(null)
		exoPlayer.release()
	}
	
	override fun finish() {
		super.finish()
		overridePendingTransition(R.anim.fade_in, R.anim.slide_to_bottom)
	}
	
	internal fun initUI() {
		setContentView(R.layout.act_media_viewer)
		pbvImage = findViewById(R.id.pbvImage)
		btnPrevious = findViewById(R.id.btnPrevious)
		btnNext = findViewById(R.id.btnNext)
		exoView = findViewById(R.id.exoView)
		tvError = findViewById(R.id.tvError)
		svDescription = findViewById(R.id.svDescription)
		tvDescription = findViewById(R.id.tvDescription)
		tvStatus = findViewById(R.id.tvStatus)
		
		val enablePaging = media_list.size > 1
		btnPrevious.isEnabled = enablePaging
		btnNext.isEnabled = enablePaging
		btnPrevious.alpha = if(enablePaging) 1f else 0.3f
		btnNext.alpha = if(enablePaging) 1f else 0.3f
		
		btnPrevious.setOnClickListener(this)
		btnNext.setOnClickListener(this)
		findViewById<View>(R.id.btnDownload).setOnClickListener(this)
		findViewById<View>(R.id.btnMore).setOnClickListener(this)
		
		pbvImage.setCallback(object : PinchBitmapView.Callback {
			override fun onSwipe(deltaX : Int, deltaY : Int) {
				if(isDestroyed) return
				if(deltaX != 0) {
					loadDelta(deltaX)
				} else {
					log.d("finish by vertical swipe")
					finish()
				}
			}
			
			override fun onMove(
				bitmap_w : Float,
				bitmap_h : Float,
				tx : Float,
				ty : Float,
				scale : Float
			) {
				App1.getAppState(this@ActMediaViewer).handler.post(Runnable {
					if(isDestroyed) return@Runnable
					if(tvStatus.visibility == View.VISIBLE) {
						tvStatus.text = getString(
							R.string.zooming_of,
							bitmap_w.toInt(),
							bitmap_h.toInt(),
							scale
						)
					}
				})
			}
		})
		
		exoPlayer = ExoPlayerFactory.newSimpleInstance(this, DefaultTrackSelector())
		exoPlayer.addListener(player_listener)
		
		exoView.player = exoPlayer
	}
	
	internal fun loadDelta(delta : Int) {
		if(media_list.size < 2) return
		val size = media_list.size
		idx = (idx + size + delta) % size
		load()
	}
	
	internal fun load(state : Bundle? = null) {
		
		exoPlayer.stop()
		pbvImage.visibility = View.GONE
		exoView.visibility = View.GONE
		tvError.visibility = View.GONE
		svDescription.visibility = View.GONE
		tvStatus.visibility = View.GONE
		
		if(idx < 0 || idx >= media_list.size) {
			showError(getString(R.string.media_attachment_empty))
			return
		}
		val ta = media_list[idx]
		val description = ta.description
		if(description?.isNotEmpty() == true) {
			svDescription.visibility = View.VISIBLE
			tvDescription.text = description
		}
		
		when(ta.type) {

			TootAttachmentType.Unknown ->
				showError(getString(R.string.media_attachment_type_error, ta.type.id))

			TootAttachmentType.Image ->
				loadBitmap(ta)

			TootAttachmentType.Video,
			TootAttachmentType.GIFV,
			TootAttachmentType.Audio ->
				loadVideo(ta, state)
		}
		
	}
	
	private fun showError(message : String) {
		exoView.visibility = View.GONE
		pbvImage.visibility = View.GONE
		tvError.visibility = View.VISIBLE
		tvError.text = message
		
	}
	
	@SuppressLint("StaticFieldLeak")
	private fun loadVideo(ta : TootAttachment, state : Bundle? = null) {
		
		val url = ta.getLargeUrl(App1.pref)
		if(url == null) {
			showError("missing media attachment url.")
			return
		}
		
		// https://github.com/google/ExoPlayer/issues/1819
		HttpsURLConnection.setDefaultSSLSocketFactory(MySslSocketFactory)
		
		exoView.visibility = View.VISIBLE
		
		val defaultBandwidthMeter = DefaultBandwidthMeter()
		val extractorsFactory = DefaultExtractorsFactory()
		
		val dataSourceFactory = DefaultDataSourceFactory(
			this, Util.getUserAgent(this, getString(R.string.app_name)), defaultBandwidthMeter
		)
		
		val mediaSource = ExtractorMediaSource.Factory(dataSourceFactory)
			.setExtractorsFactory(extractorsFactory)
			.createMediaSource(url.toUri())
		
		mediaSource.addEventListener(App1.getAppState(this).handler, mediaSourceEventListener)
		
		exoPlayer.prepare(mediaSource)
		exoPlayer.repeatMode = when(ta.type) {
			TootAttachmentType.Video -> Player.REPEAT_MODE_OFF
			// GIFV or AUDIO
			else -> Player.REPEAT_MODE_ALL
		}
		if(state == null) {
			exoPlayer.playWhenReady = true
		} else {
			exoPlayer.playWhenReady = state.getBoolean(STATE_PLAYER_PLAY_WHEN_READY, true)
			exoPlayer.seekTo(max(0L, state.getLong(STATE_PLAYER_POS, 0L)))
		}
	}
	
	private val mediaSourceEventListener = object : MediaSourceEventListener {
		override fun onLoadStarted(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			loadEventInfo : MediaSourceEventListener.LoadEventInfo?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?
		) {
			log.d("onLoadStarted")
		}
		
		override fun onDownstreamFormatChanged(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?
		) {
			log.d("onDownstreamFormatChanged")
		}
		
		override fun onUpstreamDiscarded(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?
		) {
			log.d("onUpstreamDiscarded")
		}
		
		override fun onLoadCompleted(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			loadEventInfo : MediaSourceEventListener.LoadEventInfo?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?
		) {
			log.d("onLoadCompleted")
		}
		
		override fun onLoadCanceled(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			loadEventInfo : MediaSourceEventListener.LoadEventInfo?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?
		) {
			log.d("onLoadCanceled")
		}
		
		override fun onLoadError(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?,
			loadEventInfo : MediaSourceEventListener.LoadEventInfo?,
			mediaLoadData : MediaSourceEventListener.MediaLoadData?,
			error : IOException?,
			wasCanceled : Boolean
		) {
			if(error != null) {
				showError(error.withCaption("load error."))
			}
		}
		
		override fun onMediaPeriodCreated(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?
		) {
			log.d("onMediaPeriodCreated")
		}
		
		override fun onMediaPeriodReleased(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?
		) {
			log.d("onMediaPeriodReleased")
		}
		
		override fun onReadingStarted(
			windowIndex : Int,
			mediaPeriodId : MediaSource.MediaPeriodId?
		) {
			log.d("onReadingStarted")
		}
		
	}
	
	@SuppressLint("StaticFieldLeak")
	private fun loadBitmap(ta : TootAttachment) {
		val urlList = ta.getLargeUrlList(App1.pref)
		if(urlList.isEmpty()) {
			showError("missing media attachment url.")
			return
		}
		
		tvStatus.visibility = View.VISIBLE
		tvStatus.text = null
		
		pbvImage.visibility = View.VISIBLE
		pbvImage.setBitmap(null)
		
		TootTaskRunner(this, TootTaskRunner.PROGRESS_HORIZONTAL).run(object : TootTask {
			
			private val options = BitmapFactory.Options()
			
			var bitmap : Bitmap? = null
			
			private fun decodeBitmap(data : ByteArray, pixel_max : Int) : Bitmap? {
				
				// EXIF回転情報の取得
				val orientation : Int? = try {
					ExifInterface().apply {
						readExif(
							ByteArrayInputStream(data),
							ExifInterface.Options.OPTION_IFD_0
								or ExifInterface.Options.OPTION_IFD_1
								or ExifInterface.Options.OPTION_IFD_EXIF
						)
					}.getTagIntValue(ExifInterface.TAG_ORIENTATION)
				} catch(ex : Throwable) {
					null
				}
				
				// detects image size
				options.inJustDecodeBounds = true
				options.inScaled = false
				options.outWidth = 0
				options.outHeight = 0
				BitmapFactory.decodeByteArray(data, 0, data.size, options)
				var w = options.outWidth
				var h = options.outHeight
				if(w <= 0 || h <= 0) {
					log.e("can't decode bounds.")
					return null
				}
				
				// calc bits to reduce size
				var bits = 0
				while(w > pixel_max || h > pixel_max) {
					++ bits
					w = w shr 1
					h = h shr 1
				}
				options.inJustDecodeBounds = false
				options.inSampleSize = 1 shl bits
				
				// decode image
				val bitmap1 = BitmapFactory.decodeByteArray(data, 0, data.size, options)
				
				// デコード失敗、または回転情報がない
				if(bitmap1 == null || orientation == null) return bitmap1
				
				val src_width = bitmap1.width
				val src_height = bitmap1.height
				
				// 回転行列を作る
				val matrix = Matrix()
				matrix.reset()
				
				// 画像の中心が原点に来るようにして
				matrix.postTranslate(src_width * - 0.5f, src_height * - 0.5f)
				
				// orientationに合わせた回転指定
				val flipWh = when(orientation) {
					2 -> {
						// 上下反転
						matrix.postScale(1f, - 1f)
						false
					}
					
					3 -> {
						// 180度回転
						matrix.postRotate(180f)
						false
					}
					
					4 -> {
						// 左右反転
						matrix.postScale(- 1f, 1f)
						false
					}
					
					5 -> {
						// 上下反転して反時計回りに90度
						matrix.postScale(1f, - 1f)
						matrix.postRotate(- 90f)
						true
					}
					
					6 -> {
						// 時計回りに90度
						matrix.postRotate(90f)
						true
					}
					
					7 -> {
						// 上下反転して時計回りに90度
						matrix.postScale(1f, - 1f)
						matrix.postRotate(90f)
						true
					}
					
					8 -> {
						// 上下反転して反時計回りに90度
						matrix.postRotate(- 90f)
						true
					}
					
					else -> {
						// 回転は不要
						return bitmap
					}
				}
				
				// 出力サイズ
				val dst_width : Int
				val dst_height : Int
				when(flipWh) {
					true -> {
						dst_width = src_height
						dst_height = src_width
					}
					
					else -> {
						dst_width = src_width
						dst_height = src_height
					}
				}
				
				// 表示領域に埋まるように平行移動
				matrix.postTranslate(dst_width * 0.5f, dst_height * 0.5f)
				
				// 回転後の画像
				val bitmap2 = try {
					Bitmap.createBitmap(dst_width, dst_height, Bitmap.Config.ARGB_8888)
				} catch(ex : Throwable) {
					log.trace(ex)
					null
				} ?: return bitmap1
				
				try {
					Canvas(bitmap2).drawBitmap(
						bitmap1,
						matrix,
						Paint().apply { isFilterBitmap = true }
					)
				} catch(ex : Throwable) {
					log.trace(ex)
					bitmap2.recycle()
					return bitmap1
				}
				
				try {
					bitmap1.recycle()
				} catch(ex : Throwable) {
					log.trace(ex)
				}
				return bitmap2
			}
			
			fun getHttpCached(
				client : TootApiClient,
				url : String
			) : Pair<TootApiResult?, ByteArray?> {
				val result = TootApiResult.makeWithCaption(url)
				
				val request = Request.Builder()
					.url(url)
					.cacheControl(App1.CACHE_CONTROL)
					.addHeader("Accept", "image/webp,image/*,*/*;q=0.8")
					.build()
				
				if(! client.sendRequest(
						result,
						tmpOkhttpClient = App1.ok_http_client_media_viewer
					) {
						request
					}
				) return Pair(result, null)
				
				if(client.isApiCancelled) return Pair(null, null)
				
				val response = requireNotNull(result.response)
				if(! response.isSuccessful) {
					result.setError(TootApiClient.formatResponse(response, result.caption))
					return Pair(result, null)
				}
				
				try {
					val ba = ProgressResponseBody.bytes(response) { bytesRead, bytesTotal ->
						// 50MB以上のデータはキャンセルする
						if(Math.max(bytesRead, bytesTotal) >= 50000000) {
							throw RuntimeException("media attachment is larger than 50000000")
						}
						client.publishApiProgressRatio(bytesRead.toInt(), bytesTotal.toInt())
					}
					if(client.isApiCancelled) return Pair(null, null)
					return Pair(result, ba)
				} catch(ex : Throwable) {
					result.setError(TootApiClient.formatResponse(response, result.caption, "?"))
					return Pair(result, null)
				}
			}
			
			override fun background(client : TootApiClient) : TootApiResult? {
				if(urlList.isEmpty()) return TootApiResult("missing url")
				var lastResult : TootApiResult? = null
				for(url in urlList) {
					val (result, ba) = getHttpCached(client, url)
					lastResult = result
					if(ba != null) {
						client.publishApiProgress("decoding image…")
						val bitmap = decodeBitmap(ba, 2048)
						if(bitmap != null) {
							this.bitmap = bitmap
							break
						}
					}
				}
				return lastResult
			}
			
			override fun handleResult(result : TootApiResult?) {
				val bitmap = this.bitmap
				if(bitmap != null) {
					pbvImage.setBitmap(bitmap)
				} else if(result != null) {
					showToast(this@ActMediaViewer, true, result.error)
				}
			}
		})
		
	}
	
	override fun onClick(v : View) {
		try {
			when(v.id) {
				
				R.id.btnPrevious -> loadDelta(- 1)
				R.id.btnNext -> loadDelta(+ 1)
				R.id.btnDownload -> download(media_list[idx])
				R.id.btnMore -> more(media_list[idx])
			}
		} catch(ex : Throwable) {
			showToast(this, ex, "action failed.")
		}
		
	}
	
	internal class DownloadHistory(val time : Long, val url : String)
	
	private fun download(ta : TootAttachmentLike) {
		
		val permissionCheck = ContextCompat.checkSelfPermission(
			this,
			Manifest.permission.WRITE_EXTERNAL_STORAGE
		)
		if(permissionCheck != PackageManager.PERMISSION_GRANTED) {
			preparePermission()
			return
		}
		
		val downLoadManager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
			?: throw NotImplementedError("missing DownloadManager system service")
		
		val url = if(ta is TootAttachment) {
			ta.getLargeUrl(App1.pref)
		} else {
			null
		} ?: return
		
		// ボタン連打対策
		run {
			val now = SystemClock.elapsedRealtime()
			
			// 期限切れの履歴を削除
			val it = download_history_list.iterator()
			while(it.hasNext()) {
				val dh = it.next()
				if(now - dh.time >= DOWNLOAD_REPEAT_EXPIRE) {
					// この履歴は十分に古いので捨てる
					it.remove()
				} else if(url == dh.url) {
					// 履歴に同じURLがあればエラーとする
					showToast(this, false, R.string.dont_repeat_download_to_same_url)
					return
				}
			}
			// 履歴の末尾に追加(履歴は古い順に並ぶ)
			download_history_list.addLast(DownloadHistory(now, url))
		}
		
		var fileName : String? = null
		
		try {
			val pathSegments = url.toUri().pathSegments
			if(pathSegments != null) {
				val size = pathSegments.size
				for(i in size - 1 downTo 0) {
					val s = pathSegments[i]
					if(s?.isNotEmpty() == true) {
						fileName = s
						break
					}
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex)
		}
		
		if(fileName == null) {
			fileName = url
				.replaceFirst("https?://".toRegex(), "")
				.replace("[^.\\w\\d]+".toRegex(), "-")
		}
		if(fileName.length >= 20) fileName = fileName.substring(fileName.length - 20)
		
		val request = DownloadManager.Request(url.toUri())
		request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
		request.setTitle(fileName)
		request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
		//メディアスキャンを許可する
		request.allowScanningByMediaScanner()
		
		//ダウンロード中・ダウンロード完了時にも通知を表示する
		request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
		
		downLoadManager.enqueue(request)
		showToast(this, false, R.string.downloading)
	}
	
	private fun share(action : String, url : String) {
		
		try {
			val intent = Intent(action)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if(action == Intent.ACTION_SEND) {
				intent.type = "text/plain"
				intent.putExtra(Intent.EXTRA_TEXT, url)
			} else {
				intent.data = url.toUri()
			}
			
			startActivity(intent)
		} catch(ex : Throwable) {
			showToast(this, ex, "can't open app.")
		}
		
	}
	
	internal fun copy(url : String) {
		val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
			?: throw NotImplementedError("missing ClipboardManager system service")
		
		try {
			//クリップボードに格納するItemを作成
			val item = ClipData.Item(url)
			
			val mimeType = arrayOfNulls<String>(1)
			mimeType[0] = ClipDescription.MIMETYPE_TEXT_PLAIN
			
			//クリップボードに格納するClipDataオブジェクトの作成
			val cd = ClipData(ClipDescription("media URL", mimeType), item)
			
			//クリップボードにデータを格納
			cm.primaryClip = cd
			
			showToast(this, false, R.string.url_is_copied)
			
		} catch(ex : Throwable) {
			showToast(this, ex, "clipboard access failed.")
		}
		
	}
	
	internal fun more(ta : TootAttachmentLike) {
		val ad = ActionsDialog()
		
		if(ta is TootAttachment) {
			val url = ta.getLargeUrl(App1.pref) ?: return
			
			ad.addAction(getString(R.string.open_in_browser)) { share(Intent.ACTION_VIEW, url) }
			ad.addAction(getString(R.string.share_url)) { share(Intent.ACTION_SEND, url) }
			ad.addAction(getString(R.string.copy_url)) { copy(url) }
			
			addMoreMenu(ad, "url", ta.url, Intent.ACTION_VIEW)
			addMoreMenu(ad, "remote_url", ta.remote_url, Intent.ACTION_VIEW)
			addMoreMenu(ad, "preview_url", ta.preview_url, Intent.ACTION_VIEW)
			addMoreMenu(ad, "text_url", ta.text_url, Intent.ACTION_VIEW)
			
		} else if(ta is TootAttachmentMSP) {
			val url = ta.preview_url
			ad.addAction(getString(R.string.open_in_browser)) { share(Intent.ACTION_VIEW, url) }
			ad.addAction(getString(R.string.share_url)) { share(Intent.ACTION_SEND, url) }
			ad.addAction(getString(R.string.copy_url)) { copy(url) }
		}
		
		ad.show(this, null)
	}
	
	private fun addMoreMenu(
		ad : ActionsDialog,
		caption_prefix : String,
		url : String?,
		action : String
	) {
		val uri = url.mayUri() ?: return
		
		val caption = getString(R.string.open_browser_of, caption_prefix)
		
		ad.addAction(caption) {
			try {
				val intent = Intent(action, uri)
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				startActivity(intent)
			} catch(ex : Throwable) {
				showToast(this@ActMediaViewer, ex, "can't open app.")
			}
		}
	}
	
	private fun preparePermission() {
		if(Build.VERSION.SDK_INT >= 23) {
			ActivityCompat.requestPermissions(
				this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE
			)
		} else {
			showToast(this, true, R.string.missing_permission_to_access_media)
		}
	}
	
	override fun onRequestPermissionsResult(
		requestCode : Int, permissions : Array<String>, grantResults : IntArray
	) {
		when(requestCode) {
			PERMISSION_REQUEST_CODE -> {
				var bNotGranted = false
				var i = 0
				val ie = permissions.size
				while(i < ie) {
					if(grantResults[i] != PackageManager.PERMISSION_GRANTED) {
						bNotGranted = true
					}
					++ i
				}
				if(bNotGranted) {
					showToast(this, true, R.string.missing_permission_to_access_media)
				} else {
					download(media_list[idx])
				}
			}
		}
	}
	
}
