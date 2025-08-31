package com.yukino.tool

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.yukino.tool.module.compress.CompressDetail
import com.yukino.tool.module.compress.DecompressRoute
import com.yukino.tool.module.compress.SelectFile
import com.yukino.tool.module.compress.SelectFileRoute
import com.yukino.tool.module.home.Home
import com.yukino.tool.module.web.WebBrowser
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object WebBrowserRoute

@Composable
fun Router(
    navController : NavHostController = rememberNavController()
) {
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