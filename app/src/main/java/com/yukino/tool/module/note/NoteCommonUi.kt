package com.yukino.tool.module.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "复制"链接在 AnnotatedString 里的注解标记
internal const val TAG_COPY = "copy"

// 新增时的预填充模板(字段可改可删，加密/标题/预览随意调)
internal fun newTemplateEntry() = NoteEntry(
    fields = listOf(
        NoteField(key = "网站/APP", value = "", title = true, preview = true),
        NoteField(key = "账号", value = "", preview = true),
        NoteField(key = "密码", value = "", secret = true),
        NoteField(key = "备注", value = "")
    )
)

// 备忘录统一顶栏: 标题居中，动作按钮靠右
@Composable
internal fun VaultTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(start = 15.dp, end = 5.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
    }
}

//统一输入框: 边框颜色更明显；password=true时用密码掩码；placeholder为占位提示
@Composable
internal fun NoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(text = placeholder, fontSize = 12.sp) }
        } else null,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}
