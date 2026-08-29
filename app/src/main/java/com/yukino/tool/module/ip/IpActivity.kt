package com.yukino.tool.module.ip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yukino.tool.ui.theme.ToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

class IpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IpViewer()
                }
            }
        }
    }
}

//一条网络/接口信息。kind的声明顺序即列表排序: 越可能是对外地址越靠前
enum class EntryKind { WLAN, HOTSPOT, ETHERNET, VPN, CELLULAR, OTHER, LOOPBACK }

data class IpEntry(
    val title: String,
    val kind: EntryKind = EntryKind.OTHER,
    val internet: Boolean = true,
    val note: String? = null,
    val ips: List<String> = emptyList(),
    val gateway: String? = null,
    val dns: List<String> = emptyList()
)

@Composable
fun IpViewer() {

    val context = LocalContext.current

    var entries by remember {
        mutableStateOf<List<IpEntry>>(emptyList())
    }

    var refresh by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(refresh) {
        entries = withContext(Dispatchers.IO) {
            queryIpEntries(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(start = 15.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "IP 信息",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { refresh++ }) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    tint = Color.White
                )
            }
        }
        //全部文字可长按选择复制
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries) { entry ->
                    EntryCard(entry)
                }
            }
        }
    }
}

//复制注解标记
private const val TAG_COPY = "copy"

@Composable
private fun EntryCard(entry: IpEntry) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val copyColor = MaterialTheme.colorScheme.primary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(text = entry.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            entry.note?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.ips.forEach { ip ->
                //属性行内联布局: 属性(灰小字) + 值(正常) + 复制(主题色，LinkAnnotation点击)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 13.sp, color = labelColor)) { append("IP  ") }
                        withStyle(SpanStyle(fontSize = 14.sp)) { append(ip) }
                        append("  ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = TAG_COPY,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = copyColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                ),
                                linkInteractionListener = {
                                    //复制纯IP(不带前缀长度)，可直接用于ping/访问
                                    val pure = ip.substringBefore("/")
                                    clipboard.setText(AnnotatedString(pure))
                                    Toast.makeText(context, "已复制 $pure", Toast.LENGTH_SHORT).show()
                                }
                            )
                        ) { append("复制") }
                    }
                )
            }
            if (entry.gateway != null) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 13.sp, color = labelColor)) { append("网关  ") }
                        withStyle(SpanStyle(fontSize = 14.sp)) { append(entry.gateway) }
                    }
                )
            }
            if (entry.dns.isNotEmpty()) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 13.sp, color = labelColor)) { append("DNS  ") }
                        withStyle(SpanStyle(fontSize = 14.sp)) { append(entry.dns.joinToString(", ")) }
                    }
                )
            }
        }
    }
}

//收集所有网络与接口的IP信息: 活动网络(WLAN/移动/VPN等)带网关DNS，其余接口(热点自身/回环等)单独列出
@Suppress("DEPRECATION")
private fun queryIpEntries(context: Context): List<IpEntry> {
    val entries = mutableListOf<IpEntry>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkIfaces = mutableSetOf<String>()
    cm.allNetworks.forEach { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@forEach
        val lp = cm.getLinkProperties(network) ?: return@forEach
        val iface = lp.interfaceName ?: return@forEach
        networkIfaces.add(iface)
        val (transport, kind) = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN" to EntryKind.WLAN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网" to EntryKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN" to EntryKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络" to EntryKind.CELLULAR
            else -> "网络" to EntryKind.OTHER
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        entries.add(
            IpEntry(
                title = "$transport ($iface)",
                kind = kind,
                internet = internet,
                note = if (kind == EntryKind.CELLULAR && !internet) "无上网能力(IMS等运营商内部网络)" else null,
                ips = lp.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" },
                gateway = lp.routes.firstOrNull { it.destination?.prefixLength == 0 }?.gateway?.hostAddress,
                dns = lp.dnsServers.mapNotNull { it.hostAddress }
            )
        )
    }
    //未挂在活动网络上的接口: 开启热点时的自身接口(ap0/swlan0等)、回环等
    Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp }.forEach { nif ->
        if (nif.name in networkIfaces) {
            return@forEach
        }
        val ips = Collections.list(nif.inetAddresses).mapNotNull { it.hostAddress }
        if (ips.isEmpty()) {
            return@forEach
        }
        if (nif.isLoopback) {
            entries.add(
                IpEntry(
                    title = "接口 ${nif.displayName ?: nif.name}",
                    kind = EntryKind.LOOPBACK,
                    note = "回环接口",
                    ips = ips
                )
            )
        } else if (ips.any { !it.contains(":") }) {
            entries.add(
                IpEntry(
                    title = "接口 ${nif.displayName ?: nif.name}",
                    kind = EntryKind.HOTSPOT,
                    note = "未联网接口(开启热点/网络共享时，自身IP在此，通常为 192.168.x.1)",
                    ips = ips
                )
            )
        } else {
            entries.add(
                IpEntry(
                    title = "接口 ${nif.displayName ?: nif.name}",
                    kind = EntryKind.OTHER,
                    note = "仅本地链路地址(未连接)",
                    ips = ips
                )
            )
        }
    }
    //排序: 最可能作为对外地址的排前面; 卡内IPv4排在IPv6前，本地链路/回环殿后
    return entries.sortedBy(::entryRank).map { it.copy(ips = it.ips.sortedBy(::ipPriority)) }
}

//排序权重: WLAN/热点有IPv4最优先，无上网能力的网络与仅本地链路的接口垫底
private fun entryRank(entry: IpEntry): Int {
    val hasIpv4 = entry.ips.any { !it.contains(":") && !it.startsWith("127.") }
    val onlyLinkLocal = entry.ips.all { it.startsWith("fe80") || it.startsWith("127.") }
    return when {
        onlyLinkLocal -> 9
        entry.kind == EntryKind.WLAN -> if (hasIpv4) 0 else 5
        entry.kind == EntryKind.HOTSPOT -> if (hasIpv4) 1 else 6
        entry.kind == EntryKind.ETHERNET -> 2
        entry.kind == EntryKind.VPN -> 3
        entry.kind == EntryKind.CELLULAR -> if (entry.internet) 4 else 7
        else -> 8
    }
}

//IPv4优先，其次IPv6全局地址，再IPv6本地链路(fe80)，回环最后
private fun ipPriority(ip: String): Int = when {
    ip.startsWith("127.") -> 3
    ip.contains(":") -> if (ip.startsWith("fe80")) 2 else 1
    else -> 0
}
