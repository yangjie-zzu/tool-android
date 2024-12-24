package com.yukino.tool

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yukino.tool.home.Home
import com.yukino.tool.web.WebBrowser
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object WebBrowserRoute

@Composable
fun Router() {
    val navController = rememberNavController()
    NavHost(
        modifier = Modifier.fillMaxSize(),
        navController = navController,
        startDestination = HomeRoute
    ) {
        composable<HomeRoute> {
            Home()
        }
        composable<WebBrowserRoute> { WebBrowser() }
    }
}