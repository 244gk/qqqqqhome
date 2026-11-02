package com.x0finch.fake

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class FakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.fake_title)
            textSize = 40f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1C3A2A.toInt())
        })
    }
}
