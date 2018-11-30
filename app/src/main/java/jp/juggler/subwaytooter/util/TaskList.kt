package jp.juggler.subwaytooter.util

import android.content.Context
import jp.juggler.util.LogCategory
import jp.juggler.util.decodeUTF8
import jp.juggler.util.encodeUTF8
import jp.juggler.util.toJsonArray
import org.apache.commons.io.IOUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.*

class TaskList {
	
	companion object {
		
		private val log = LogCategory("TaskList")
		private const val FILE_TASK_LIST = "JOB_TASK_LIST"
	}
	
	private lateinit var _list : LinkedList<JSONObject>
	
	@Synchronized
	private fun prepareList(context : Context) : LinkedList<JSONObject> {
		if(! ::_list.isInitialized) {
			_list = LinkedList()
			
			try {
				context.openFileInput(FILE_TASK_LIST).use { inputStream ->
					val bao = ByteArrayOutputStream()
					IOUtils.copy(inputStream, bao)
					val array = bao.toByteArray().decodeUTF8().toJsonArray()
					var i = 0
					val ie = array.length()
					while(i < ie) {
						val item = array.optJSONObject(i)
						if(item != null) _list.add(item)
						++ i
					}
					
				}
			} catch(ex : FileNotFoundException) {
				log.e(ex, "prepareList: file not found.")
			} catch(ex : Throwable) {
				log.trace(ex,"TaskList: prepareArray failed.")
			}
			
		}
		
		return _list
	}
	
	@Synchronized
	private fun saveArray(context : Context) {
		val list = prepareList(context)
		try {
			log.d("saveArray size=%s", list.size)
			val array = JSONArray()
			for(item in list) {
				array.put(item)
			}
			val data = array.toString().encodeUTF8()
			context.openFileOutput(FILE_TASK_LIST, Context.MODE_PRIVATE)
				.use { IOUtils.write(data, it) }
		} catch(ex : Throwable) {
			log.trace(ex)
			log.e(ex, "TaskList: saveArray failed.size=%s", list.size)
		}
		
	}
	
	@Synchronized
	fun addLast(context : Context, removeOld : Boolean, taskData : JSONObject) {
		val list = prepareList(context)
		if(removeOld) {
			val it = list.iterator()
			while(it.hasNext()) {
				val item = it.next()
				if(taskData == item) it.remove()
			}
		}
		list.addLast(taskData)
		saveArray(context)
	}
	
	@Suppress("unused")
	@Synchronized
	fun hasNext(context : Context) : Boolean {
		return prepareList(context).isNotEmpty()
	}
	
	@Synchronized
	fun next(context : Context) : JSONObject? {
		val list = prepareList(context)
		val item = if(list.isEmpty()) null else list.removeFirst()
		saveArray(context)
		return item
	}
	
}
