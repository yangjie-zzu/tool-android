package com.yukino.tool.module.bluetooth.ble

import android.content.Context
import com.yukino.tool.db.AppDb

/*
 * 预设存取: 单行增删改查 + 首次种子(表空时插入内置"关灯"预设, 复刻旧硬编码行为)。
 */
object PresetStore {

    fun list(context: Context): List<Preset> {
        val db = AppDb.get(context)
        return db.rawQuery(
            "SELECT id, name, template, params, created_at FROM ble_preset ORDER BY created_at ASC", null
        ).use { c ->
            val out = ArrayList<Preset>()
            while (c.moveToNext()) {
                val template = c.getString(2)
                out.add(
                    Preset(
                        id = c.getLong(0),
                        name = c.getString(1),
                        template = template,
                        params = PresetCodec.decode(template, c.getString(3)),
                        createdAt = c.getLong(4)
                    )
                )
            }
            out
        }
    }

    fun add(context: Context, name: String, template: String, params: PresetParams): Long {
        val db = AppDb.get(context)
        val v = android.content.ContentValues().apply {
            put("name", name)
            put("template", template)
            put("params", PresetCodec.encode(params))
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("ble_preset", null, v)
    }

    fun update(context: Context, id: Long, name: String, params: PresetParams) {
        val db = AppDb.get(context)
        val v = android.content.ContentValues().apply {
            put("name", name)
            put("params", PresetCodec.encode(params))
        }
        db.update("ble_preset", v, "id = ?", arrayOf(id.toString()))
    }

    fun delete(context: Context, id: Long) {
        AppDb.get(context).delete("ble_preset", "id = ?", arrayOf(id.toString()))
    }

    /** 表空时插入内置"关灯"预设(旧硬编码行为的等价迁移) */
    fun seedIfEmpty(context: Context) {
        if (list(context).isNotEmpty()) return
        add(
            context, "关灯", PresetTemplates.WRITE,
            WriteParams(
                match = DeviceMatch(matchType = "uuid", serviceUuid = "FFE0"),
                svc = "FFF0",
                chr = "FFF2",
                commands = listOf(
                    WriteCommand("43020101", delayMs = 100),
                    WriteCommand("43020102", delayMs = 0)
                ),
                writeWithResp = true,
                btOffAfter = true
            )
        )
    }
}
