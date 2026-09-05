package com.yukino.tool.module.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 列表: 每条记录一张卡片，字段的 显示/复制/隐藏 和编辑入口都在卡片上，无需中间页
@Composable
internal fun ListPage(
    entries: List<NoteEntry>,
    secrets: Map<String, String>,
    revealed: Set<String>,
    nowSeconds: Long,
    onCopy: (NoteEntry, NoteField) -> Unit,
    onReveal: (NoteEntry, NoteField) -> Unit,
    onEdit: (NoteEntry) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val copyColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopBar("备忘录", actions = {
            IconButton(onClick = onSettings) {
                Icon(imageVector = Icons.Rounded.Settings, contentDescription = "设置", tint = Color.White)
            }
        })
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "暂无记录，点下方新增按钮添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            //字段行: 加密字段 显示/隐藏 需认证；复制在明文可见时直接可用
                            entry.fields.forEach { field ->
                                val cacheKey = "${entry.id}:${field.key}"
                                val secretValue = secrets[cacheKey]
                                val isRevealed = cacheKey in revealed && secretValue != null
                                val display = when {
                                    !field.secret -> field.value
                                    //2FA字段解密后显示实时动态码+剩余秒数
                                    isRevealed && field.totp -> Totp.code(secretValue ?: "", nowSeconds)
                                        ?.let { "${it.substring(0, 3)} ${it.substring(3)} (${30 - nowSeconds % 30}s)" }
                                        ?: "密钥无效"
                                    isRevealed -> secretValue
                                    else -> "••••••"
                                }
                                //空的明文字段不显示行
                                if (!field.secret && display.isBlank()) return@forEach
                                Text(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(fontSize = 13.sp, color = labelColor)) {
                                            append("${field.key}  ")
                                        }
                                        if (display.isNotBlank()) {
                                            withStyle(SpanStyle(fontSize = 14.sp)) { append(display) }
                                            append("  ")
                                        }
                                        withLink(
                                            LinkAnnotation.Clickable(
                                                tag = TAG_COPY,
                                                styles = TextLinkStyles(
                                                    style = SpanStyle(fontSize = 13.sp, color = copyColor, fontWeight = FontWeight.Medium)
                                                ),
                                                linkInteractionListener = { onCopy(entry, field) }
                                            )
                                        ) { append("复制") }
                                        if (field.secret) {
                                            append("  ")
                                            withLink(
                                                LinkAnnotation.Clickable(
                                                    tag = "toggle",
                                                    styles = TextLinkStyles(
                                                        style = SpanStyle(fontSize = 13.sp, color = copyColor, fontWeight = FontWeight.Medium)
                                                    ),
                                                    linkInteractionListener = { onReveal(entry, field) }
                                                )
                                            ) { append(if (isRevealed) "隐藏" else "显示") }
                                        }
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            //编辑入口放在卡片底部靠右
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onEdit(entry) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "编辑", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        //新增按钮放在底部，便于单手操作
        Button(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp)
        ) {
            Text(text = "新增")
        }
    }
}
