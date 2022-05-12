package com.example.androidsslpinning.data

import com.example.androidsslpinning.data.dto.GithubUser
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface ApiService {

    @GET("/users/{profile}")
    fun getUserData(@Path("profile") profile: String):
            Call<GithubUser>
}
