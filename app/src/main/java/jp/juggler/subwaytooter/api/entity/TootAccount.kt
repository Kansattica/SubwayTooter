package jp.juggler.subwaytooter.api.entity

import android.content.Context
import android.net.Uri
import android.text.Spannable
import jp.juggler.subwaytooter.api.MisskeyAccountDetailMap
import jp.juggler.subwaytooter.api.TootParser
import jp.juggler.subwaytooter.table.UserRelationMisskey
import jp.juggler.subwaytooter.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.regex.Pattern

open class TootAccount(parser : TootParser, src : JSONObject) {
	
	class Field(
		val name : String,
		val value : String,
		val verified_at : Long // 0L if not verified
	)
	
	companion object {
		private val log = LogCategory("TootAccount")
		
		internal val reWhitespace : Pattern = Pattern.compile("[\\s\\t\\x0d\\x0a]+")
		
		// host, user ,(instance)
		internal val reAccountUrl : Pattern =
			Pattern.compile("\\Ahttps://([A-Za-z0-9._-]+)/@([A-Za-z0-9_]+)(?:@([A-Za-z0-9._-]+))?(?:\\z|[?#])")
		
		fun getAcctFromUrl(url : String) : String? {
			val m = reAccountUrl.matcher(url)
			return if(m.find()) {
				val host = m.group(1)
				val user = m.group(2).unescapeUri()
				val instance = m.groupOrNull(3)?.unescapeUri()
				if(instance?.isNotEmpty() == true) {
					"$user@$instance"
				} else {
					"$user@$host"
				}
			} else {
				null
			}
		}
		
		private fun parseSource(src : JSONObject?) : Source? {
			src ?: return null
			return try {
				Source(src)
			} catch(ex : Throwable) {
				log.trace(ex)
				log.e("parseSource failed.")
				null
			}
		}
		
		// Tootsearch用。URLやUriを使ってアカウントのインスタンス名を調べる
		fun findHostFromUrl(acct : String?, accessHost : String?, url : String?) : String? {
			
			// acctから調べる
			if(acct != null) {
				val pos = acct.indexOf('@')
				if(pos != - 1) {
					val host = acct.substring(pos + 1)
					if(host.isNotEmpty()) return host.toLowerCase()
				}
			}
			
			// accessHostから調べる
			if(accessHost != null) {
				return accessHost
			}
			
			// URLから調べる
			if(url != null) {
				try {
					// たぶんどんなURLでもauthorityの部分にホスト名が来るだろう(慢心)
					val host = Uri.parse(url).authority
					if(host?.isNotEmpty() == true) {
						return host.toLowerCase()
					}
					log.e("findHostFromUrl: can't parse host from URL $url")
				} catch(ex : Throwable) {
					log.e(ex, "findHostFromUrl: can't parse host from URL $url")
				}
			}
			
			return null
		}
		
		fun parseFields(src : JSONArray?) : ArrayList<Field>? {
			src ?: return null
			val dst = ArrayList<Field>()
			for(i in 0 until src.length()) {
				val item = src.optJSONObject(i) ?: continue
				val name = item.parseString("name") ?: continue
				val value = item.parseString("value") ?: continue
				val svVerifiedAt = item.parseString("verified_at")
				val verifiedAt = when(svVerifiedAt) {
					null -> 0L
					else -> TootStatus.parseTime(svVerifiedAt)
				}
				dst.add(Field(name, value, verifiedAt))
			}
			return if(dst.isEmpty()) {
				null
			} else {
				dst
			}
		}
	}
	
	//URL of the user's profile page (can be remote)
	// https://mastodon.juggler.jp/@tateisu
	// 疑似アカウントではnullになります
	val url : String?
	
	//	The ID of the account
	val id : EntityId
	
	//	Equals username for local users, includes @domain for remote ones
	val acct : String
	
	// 	The username of the account  /[A-Za-z0-9_]{1,30}/
	val username : String
	
	val host : String
	
	//	The account's display name
	val display_name : String
	
	//Boolean for when the account cannot be followed without waiting for approval first
	val locked : Boolean
	
	//	The time the account was created
	// ex: "2017-04-13T11:06:08.289Z"
	val created_at : String?
	val time_created_at : Long
	
	//	The number of followers for the account
	var followers_count : Long? = null
	
	//The number of accounts the given account is following
	var following_count : Long? = null
	
	//	The number of statuses the account has made
	var statuses_count : Long? = null
	
	// Biography of user
	// 説明文。改行は\r\n。リンクなどはHTMLタグで書かれている
	val note : String?
	
	//	URL to the avatar image
	val avatar : String?
	
	//	URL to the avatar static image (gif)
	val avatar_static : String?
	
	//URL to the header image
	val header : String?
	
	//	URL to the header static image (gif)
	val header_static : String?
	
	val source : Source?
	
	val profile_emojis : HashMap<String, NicoProfileEmoji>?
	
	val movedRef : TootAccountRef?
	
	val moved : TootAccount?
		get() = movedRef?.get()
	
	val fields : ArrayList<Field>?
	
	val custom_emojis : java.util.HashMap<String, CustomEmoji>?
	
	val bot : Boolean
	val isCat : Boolean
	val isAdmin : Boolean
	val isPro : Boolean
	
	// user_hides_network is preference, not exposed in API
	// val user_hides_network : Boolean
	
	var pinnedNotes : ArrayList<TootStatus>? = null
	var pinnedNoteIds : ArrayList<String>? = null
	
	init {
		var sv : String?
		
		if(parser.serviceType == ServiceType.MISSKEY) {
			
			val remoteHost = src.parseString("host")
			val instance = remoteHost ?: parser.linkHelper.host ?: error("missing host")
			
			this.custom_emojis = parseMapOrNull(CustomEmoji.decodeMisskey, src.optJSONArray("emojis"))
			
			this.profile_emojis = null
			
			this.username = src.notEmptyOrThrow("username")
			this.url = "https://$instance/@$username"
			
			//
			sv = src.parseString("name")
			this.display_name = if(sv?.isNotEmpty() == true) sv.sanitizeBDI() else username
			
			//
			this.note = src.parseString("description")
			
			this.source = null
			this.movedRef = null
			this.locked = src.optBoolean("isLocked")
			
			this.fields = null
			
			this.bot = src.optBoolean("isBot", false)
			this.isCat = src.optBoolean("isCat", false)
			this.isAdmin = src.optBoolean("isAdmin", false)
			this.isPro = src.optBoolean("isPro", false)
			
			// this.user_hides_network = src.optBoolean("user_hides_network")
			
			this.id = EntityId.mayDefault(src.parseString("id"))
			
			this.host = instance
			this.acct = when {
				remoteHost?.isNotEmpty() == true -> "$username@$remoteHost"
				else -> username
			}
			
			this.followers_count = src.parseLong("followersCount") ?: - 1L
			this.following_count = src.parseLong("followingCount") ?: - 1L
			this.statuses_count = src.parseLong("notesCount") ?: - 1L
			
			this.created_at = src.parseString("createdAt")
			this.time_created_at = TootStatus.parseTime(this.created_at)
			
			this.avatar = src.parseString("avatarUrl")
			this.avatar_static = src.parseString("avatarUrl")
			this.header = src.parseString("bannerUrl")
			this.header_static = src.parseString("bannerUrl")
			
			this.pinnedNoteIds = src.parseStringArrayList("pinnedNoteIds")
			if(parser.misskeyDecodeProfilePin) {
				val list = parseList(::TootStatus, parser,src.optJSONArray("pinnedNotes"))
				list.forEach { it.pinned = true }
				this.pinnedNotes = if( list.isNotEmpty() ) list else null
			}
			
			UserRelationMisskey.fromAccount(parser,src,id)
			MisskeyAccountDetailMap.fromAccount(parser,this,id)
			
		} else {
			
			// 絵文字データは先に読んでおく
			this.custom_emojis = parseMapOrNull(CustomEmoji.decode, src.optJSONArray("emojis"))
			this.profile_emojis =
				parseMapOrNull(::NicoProfileEmoji, src.optJSONArray("profile_emojis"))
			
			// 疑似アカウントにacctとusernameだけ
			this.url = src.parseString("url")
			this.username = src.notEmptyOrThrow("username")
			
			//
			sv = src.parseString("display_name")
			this.display_name = if(sv?.isNotEmpty() == true) sv.sanitizeBDI() else username
			
			//
			this.note = src.parseString("note")
			
			this.source = parseSource(src.optJSONObject("source"))
			this.movedRef = TootAccountRef.mayNull(
				parser,
				src.optJSONObject("moved")?.let {
					TootAccount(parser, it)
				}
			)
			this.locked = src.optBoolean("locked")
			
			this.fields = parseFields(src.optJSONArray("fields"))
			
			this.bot = src.optBoolean("bot", false)
			this.isAdmin = false
			this.isCat = false
			this.isPro = false
			// this.user_hides_network = src.optBoolean("user_hides_network")
			
			when(parser.serviceType) {
				ServiceType.MASTODON -> {
					
					val hostAccess = parser.linkHelper.host
					
					this.id = EntityId.mayDefault(src.parseLong("id"))
					
					this.acct = src.notEmptyOrThrow("acct")
					this.host = findHostFromUrl(acct, hostAccess, url)
						?: throw RuntimeException("can't get host from acct or url")
					
					this.followers_count = src.parseLong("followers_count")
					this.following_count = src.parseLong("following_count")
					this.statuses_count = src.parseLong("statuses_count")
					
					this.created_at = src.parseString("created_at")
					this.time_created_at = TootStatus.parseTime(this.created_at)
					
					this.avatar = src.parseString("avatar")
					this.avatar_static = src.parseString("avatar_static")
					this.header = src.parseString("header")
					this.header_static = src.parseString("header_static")
					
				}
				
				ServiceType.TOOTSEARCH -> {
					// tootsearch のアカウントのIDはどのタンス上のものか分からないので役に立たない
					this.id = EntityId.defaultLong
					
					sv = src.notEmptyOrThrow("acct")
					this.host = findHostFromUrl(sv, null, url)
						?: throw RuntimeException("can't get host from acct or url")
					this.acct = this.username + "@" + this.host
					
					this.followers_count = src.parseLong("followers_count")
					this.following_count = src.parseLong("following_count")
					this.statuses_count = src.parseLong("statuses_count")
					
					this.created_at = src.parseString("created_at")
					this.time_created_at = TootStatus.parseTime(this.created_at)
					
					this.avatar = src.parseString("avatar")
					this.avatar_static = src.parseString("avatar_static")
					this.header = src.parseString("header")
					this.header_static = src.parseString("header_static")
				}
				
				ServiceType.MSP -> {
					this.id = EntityId.mayDefault(src.parseLong("id"))
					
					// MSPはLTLの情報しか持ってないのでacctは常にホスト名部分を持たない
					this.host = findHostFromUrl(null, null, url)
						?: throw RuntimeException("can't get host from url")
					this.acct = this.username + "@" + host
					
					this.followers_count = null
					this.following_count = null
					this.statuses_count = null
					
					this.created_at = null
					this.time_created_at = 0L
					
					val avatar = src.parseString("avatar")
					this.avatar = avatar
					this.avatar_static = avatar
					this.header = null
					this.header_static = null
					
				}
				
				ServiceType.MISSKEY -> error("will not happen")
			}
		}
	}
	
	class Source(src : JSONObject) {
		// デフォルト公開範囲
		val privacy : String?
		
		// 添付画像をデフォルトでNSFWにする設定
		private val sensitive : Boolean
		
		// HTMLエンコードされていない、生のnote
		val note : String?
		
		// 2.4.0 から？
		val fields : ArrayList<Field>?
		
		init {
			this.privacy = src.parseString("privacy")
			this.note = src.parseString("note")
			// nullになることがあるが、falseと同じ扱いでよい
			this.sensitive = src.optBoolean("sensitive", false)
			//
			this.fields = parseFields(src.optJSONArray("fields"))
		}
	}
	
	// リストメンバーダイアログや引っ越し先ユーザなど、TL以外の部分に名前を表示する場合は
	// Invalidator の都合でSpannableを別途生成する必要がある
	fun decodeDisplayName(context : Context) : Spannable {
		
		// remove white spaces
		val sv = reWhitespace.matcher(display_name).replaceAll(" ")
		
		// decode emoji code
		return DecodeOptions(
			context,
			emojiMapProfile = profile_emojis,
			emojiMapCustom = custom_emojis
		).decodeEmoji(sv)
	}
	
	var _orderId : EntityId? = null
	
	fun getOrderId() : EntityId = _orderId ?: id
	
}
