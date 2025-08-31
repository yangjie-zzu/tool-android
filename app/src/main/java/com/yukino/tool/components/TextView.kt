package com.yukino.tool.components

fun String?.text(ifNull: String = ""): String {
    return this ?: ifNull
}