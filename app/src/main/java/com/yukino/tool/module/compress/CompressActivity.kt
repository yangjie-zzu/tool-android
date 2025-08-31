package com.yukino.tool.module.compress

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.yukino.tool.TAG
import com.yukino.tool.util.FilePicker
import kotlinx.serialization.Serializable
import net.sf.sevenzipjbinding.SevenZip

lateinit var compressFilePicker: FilePicker

class CompressActivity: ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        Log.i(TAG, "intent: $intent")
        Log.i(TAG, "version: ${SevenZip.getSevenZipVersion().let { "${it.version}" }}, ${SevenZip.getSevenZipJBindingVersion()}, ${SevenZip.isInitializedSuccessfully()}")
        compressFilePicker = FilePicker(this)
        setContent {

            val url = if (Intent.ACTION_VIEW == intent.action) {
                intent.dataString
            } else if (Intent.ACTION_SEND == intent.action) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
            } else {
                null
            }
            Log.i(TAG, "url: $url")
            val navController = rememberNavController()
            LaunchedEffect(url) {
                if (url != null) {
                    navController.navigate(DecompressRoute(url))
                }
            }
            Router(navController = navController)
        }
    }

}

@Serializable
object SelectFileRoute

@Serializable
data class DecompressRoute(val url: String?)

@Composable
fun Router(
    navController : NavHostController = rememberNavController()
) {
    NavHost(
        modifier = Modifier.fillMaxSize(),
        navController = navController,
        startDestination = SelectFileRoute
    ) {
        composable<SelectFileRoute> {
            SelectFile(navController = navController)
        }
        composable<DecompressRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<DecompressRoute>()
            CompressDetail(url = route.url)
        }
    }
}