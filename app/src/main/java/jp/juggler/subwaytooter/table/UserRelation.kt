package jp.juggler.subwaytooter.table

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.support.v4.util.LruCache
import jp.juggler.subwaytooter.App1
import jp.juggler.subwaytooter.api.entity.EntityId
import jp.juggler.subwaytooter.api.entity.EntityIdString
import jp.juggler.subwaytooter.api.entity.TootAccount
import jp.juggler.subwaytooter.api.entity.TootRelationShip
import jp.juggler.subwaytooter.util.LogCategory
import jp.juggler.subwaytooter.util.getInt
import org.json.JSONObject

class UserRelation {
	
	var following : Boolean = false   // 認証ユーザからのフォロー状態にある
	var followed_by : Boolean = false // 認証ユーザは被フォロー状態にある
	var blocking : Boolean = false // 認証ユーザからブロックした
	var blocked_by : Boolean = false // 認証ユーザからブロックされた(Misskeyのみ。Mastodonでは常にfalse)
	var muting : Boolean = false
	var requested : Boolean = false  // 認証ユーザからのフォローは申請中である
	var requested_by : Boolean = false  // 相手から認証ユーザへのフォローリクエスト申請中(Misskeyのみ。Mastodonでは常にfalse)
	var following_reblogs : Int = 0 // このユーザからのブーストをTLに表示する
	var endorsed : Boolean = false // ユーザをプロフィールで紹介する
	
	// 認証ユーザからのフォロー状態
	fun getFollowing(who : TootAccount?) : Boolean {
		return if(requested && ! following && who != null && ! who.locked) true else following
	}
	
	// 認証ユーザからのフォローリクエスト申請中状態
	fun getRequested(who : TootAccount?) : Boolean {
		return if(requested && ! following && who != null && ! who.locked) false else requested
	}
	
	companion object : TableCompanion {
		
		private val log = LogCategory("UserRelation")
		
		private const val table = "user_relation"
		private const val COL_TIME_SAVE = "time_save"
		private const val COL_DB_ID = "db_id" // SavedAccount のDB_ID
		private const val COL_WHO_ID = "who_id" // ターゲットアカウントのID
		private const val COL_FOLLOWING = "following"
		private const val COL_FOLLOWED_BY = "followed_by"
		private const val COL_BLOCKING = "blocking"
		private const val COL_MUTING = "muting"
		private const val COL_REQUESTED = "requested"
		private const val COL_ENDORSED = "endorsed"
		
		// (mastodon 2.1 or later) per-following-user setting.
		// Whether the boosts from target account will be shown on authorized user's home TL.
		private const val COL_FOLLOWING_REBLOGS = "following_reblogs"
		
		const val REBLOG_HIDE =
			0 // don't show the boosts from target account will be shown on authorized user's home TL.
		const val REBLOG_SHOW =
			1 // show the boosts from target account will be shown on authorized user's home TL.
		const val REBLOG_UNKNOWN = 2 // not following, or instance don't support hide reblog.
		
		internal val mMemoryCache = LruCache<String, UserRelation>(2048)
		
		private const val load_where = "$COL_DB_ID=? and $COL_WHO_ID=?"
		
		private val load_where_arg = object : ThreadLocal<Array<String?>>() {
			override fun initialValue() : Array<String?> {
				return Array(2) { _ -> null }
			}
		}
		
		override fun onDBCreate(db : SQLiteDatabase) {
			log.d("onDBCreate!")
			db.execSQL(
				"""
				create table if not exists $table
				(_id INTEGER PRIMARY KEY
				,$COL_TIME_SAVE integer not null
				,$COL_DB_ID integer not null
				,$COL_WHO_ID integer not null
				,$COL_FOLLOWING integer not null
				,$COL_FOLLOWED_BY integer not null
				,$COL_BLOCKING integer not null
				,$COL_MUTING integer not null
				,$COL_REQUESTED integer not null
				,$COL_FOLLOWING_REBLOGS integer not null
				,$COL_ENDORSED integer default 0
				)"""
			)
			db.execSQL(
				"create unique index if not exists ${table}_id on $table ($COL_DB_ID,$COL_WHO_ID)"
			)
			db.execSQL(
				"create index if not exists ${table}_time on $table ($COL_TIME_SAVE)"
			)
		}
		
		override fun onDBUpgrade(db : SQLiteDatabase, oldVersion : Int, newVersion : Int) {
			if(oldVersion < 6 && newVersion >= 6) {
				onDBCreate(db)
			}
			if(oldVersion < 20 && newVersion >= 20) {
				try {
					db.execSQL("alter table $table add column $COL_FOLLOWING_REBLOGS integer default 1")
					/*
						(COL_FOLLOWING_REBLOGS カラムのデフォルト値について)
						1.7.5でboolean値を保存していた関係でデフォルト値は1(REBLOG_SHOW)になってしまっている
						1.7.6以降では3値論理にしたのでデフォルトは2(REBLOG_UNKNOWN)の方が適切だが、SQLiteにはカラムのデフォルト制約の変更を行う機能がない
						データは適当に更新されるはずだから、今のままでも多分問題ないはず…
					*/
				} catch(ex : Throwable) {
					log.trace(ex)
				}
				
			}
			if(oldVersion < 32 && newVersion >= 32) {
				try {
					db.execSQL("alter table $table add column $COL_ENDORSED integer default 0")
				} catch(ex : Throwable) {
					log.trace(ex)
				}
			}
		}
		
		fun deleteOld(now : Long) {
			try {
				// 古いデータを掃除する
				val expire = now - 86400000L * 365
				App1.database.delete(table, "$COL_TIME_SAVE<?", arrayOf(expire.toString()))
				
			} catch(ex : Throwable) {
				log.e(ex, "deleteOld failed.")
			}
			
		}
		
		// マストドン用
		fun save1(now : Long, db_id : Long, src : TootRelationShip) : UserRelation {
			try {
				val cv = ContentValues()
				cv.put(COL_TIME_SAVE, now)
				cv.put(COL_DB_ID, db_id)
				cv.put(COL_WHO_ID, src.id.toLong())
				cv.put(COL_FOLLOWING, src.following.b2i())
				cv.put(COL_FOLLOWED_BY, src.followed_by.b2i())
				cv.put(COL_BLOCKING, src.blocking.b2i())
				cv.put(COL_MUTING, src.muting.b2i())
				cv.put(COL_REQUESTED, src.requested.b2i())
				cv.put(COL_FOLLOWING_REBLOGS, src.showing_reblogs)
				cv.put(COL_ENDORSED,src.endorsed.b2i() )
				App1.database.replace(table, null, cv)
				val key = String.format("%s:%s", db_id, src.id)
				mMemoryCache.remove(key)
			} catch(ex : Throwable) {
				log.e(ex, "save failed.")
			}
			
			return load(db_id, src.id)
		}
		
		// マストドン用
		fun saveList(now : Long, db_id : Long, src_list : ArrayList<TootRelationShip>) {
			
			val cv = ContentValues()
			cv.put(COL_TIME_SAVE, now)
			cv.put(COL_DB_ID, db_id)
			
			var bOK = false
			val db = App1.database
			db.execSQL("BEGIN TRANSACTION")
			try {
				for(src in src_list) {
					cv.put(COL_WHO_ID, src.id.toLong())
					cv.put(COL_FOLLOWING, src.following.b2i())
					cv.put(COL_FOLLOWED_BY, src.followed_by.b2i())
					cv.put(COL_BLOCKING, src.blocking.b2i())
					cv.put(COL_MUTING, src.muting.b2i())
					cv.put(COL_REQUESTED, src.requested.b2i())
					cv.put(COL_FOLLOWING_REBLOGS, src.showing_reblogs)
					cv.put(COL_ENDORSED,src.endorsed.b2i() )
					db.replace(table, null, cv)
					
				}
				bOK = true
			} catch(ex : Throwable) {
				log.trace(ex)
				log.e(ex, "saveList failed.")
			}
			
			if(bOK) {
				db.execSQL("COMMIT TRANSACTION")
				for(src in src_list) {
					val key = String.format("%s:%s", db_id, src.id)
					mMemoryCache.remove(key)
				}
			} else {
				db.execSQL("ROLLBACK TRANSACTION")
			}
		}
		
		fun load(db_id : Long, whoId : EntityId) : UserRelation {
			//
			val key = String.format("%s:%s", db_id, whoId)
			val cached : UserRelation? = mMemoryCache.get(key)
			if(cached != null) return cached
			
			val dst = if(whoId is EntityIdString) {
				UserRelationMisskey.load(db_id, whoId.toString())
			} else {
				load(db_id, whoId.toLong())
			} ?: UserRelation()
			
			mMemoryCache.put(key, dst)
			return dst
		}
		
		private fun load(db_id : Long, who_id : Long) : UserRelation? {
			try {
				val where_arg = load_where_arg.get() ?: arrayOfNulls<String?>(2)
				where_arg[0] = db_id.toString()
				where_arg[1] = who_id.toString()
				App1.database.query(table, null, load_where, where_arg, null, null, null)
					.use { cursor ->
						if(cursor.moveToNext()) {
							val dst = UserRelation()
							dst.following = cursor.getBoolean(COL_FOLLOWING)
							dst.followed_by =cursor.getBoolean(COL_FOLLOWED_BY)
							dst.blocking = cursor.getBoolean(COL_BLOCKING)
							dst.muting = cursor.getBoolean(COL_MUTING)
							dst.requested = cursor.getBoolean(COL_REQUESTED)
							dst.following_reblogs = cursor.getInt(COL_FOLLOWING_REBLOGS)
							dst.endorsed = cursor.getBoolean(COL_ENDORSED)
							return dst
						}
					}
			} catch(ex : Throwable) {
				log.trace(ex)
				log.e(ex, "load failed.")
			}
			return null
		}
		
		// Misskey用
		fun parseMisskeyUser(src : JSONObject) :UserRelation? {

			// リレーションを返さない場合がある
			src.opt("isFollowing") ?: return null

			return UserRelation().apply {
				following = src.optBoolean("isFollowing")
				followed_by = src.optBoolean("isFollowed")
				muting = src.optBoolean("isMuted")
				blocking = src.optBoolean("isBlocking")
				blocked_by = src.optBoolean("isBlocked")
				endorsed = false
				requested = src.optBoolean("hasPendingFollowRequestFromYou")
				requested_by = src.optBoolean("hasPendingFollowRequestToYou")
			}
		}
	}

}
