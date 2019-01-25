package jp.juggler.subwaytooter.span

import android.graphics.Paint
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

import java.lang.ref.WeakReference

import jp.juggler.subwaytooter.table.AcctColor
import jp.juggler.util.notZero

typealias MyClickableSpanClickCallback = (widget : View, span : MyClickableSpan)->Unit

class MyClickableSpan(
//	val lcc : LinkHelper,
	val text : String,
	val url : String,
	ac : AcctColor?,
	val tag : Any?
) : ClickableSpan() {
	
	companion object {
		var link_callback : WeakReference<MyClickableSpanClickCallback>? = null
		var defaultLinkColor : Int =0
		var showLinkUnderline = true
	}

	val color_fg : Int
	val color_bg : Int
	
	init {
		if(ac != null) {
			this.color_fg = ac.color_fg
			this.color_bg = ac.color_bg
		} else {
			this.color_fg = 0
			this.color_bg = 0
		}
	}
	
	override fun onClick( view : View) {
		link_callback?.get()?.invoke(view,this )
	}
	
	override fun updateDrawState(ds : TextPaint) {
		super.updateDrawState(ds)
		if(color_bg != 0) ds.bgColor = color_bg
		
		ds.color = color_fg.notZero() ?: defaultLinkColor
		ds.isUnderlineText = showLinkUnderline
	}
	
}
