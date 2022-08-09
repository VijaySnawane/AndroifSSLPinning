package com.example.androidsslpinning

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
  
  val a=3
  @Override
  fun onCreate()
  {
     if(a==3)
  {
    Log.i("","")
  }
  }
  
 
};
