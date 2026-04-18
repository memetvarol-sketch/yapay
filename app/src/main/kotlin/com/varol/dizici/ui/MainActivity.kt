package com.varol.dizici.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.varol.dizici.R
import com.varol.dizici.ui.browse.BrowseFragment

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, BrowseFragment())
                .commit()
        }
    }
}
