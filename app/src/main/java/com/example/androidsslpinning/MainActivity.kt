package com.example.androidsslpinning

import android.os.Bundle
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.example.androidsslpinning.data.ApiService
import com.example.androidsslpinning.data.dto.GithubUser
import com.example.androidsslpinning.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val api = apiService.getUserData("john1jan")

        api.enqueue(object : Callback<GithubUser> {
            override fun onFailure(call: Call<GithubUser>, t: Throwable) {
                Log.d("TAG_TAG", "Failed :" + t.message)
                findViewById<TextView>(R.id.result).text = t.message
            }

            override fun onResponse(call: Call<GithubUser>, response: Response<GithubUser>) {
                Log.d("TAG_TAG", "Failed :" + response.message())
                findViewById<TextView>(R.id.result).text = response.body()?.name
                findViewById<TextView>(R.id.result1).text = response.body()?.bio
            }
        })
    }
}