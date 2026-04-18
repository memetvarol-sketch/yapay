package com.varol.dizici.ui.details

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.varol.dizici.R

class DetailsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.details_fragment_container, DetailsFragment())
                .commit()
        }
    }
}
