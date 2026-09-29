package com.knu.blechprobe.collect

import android.content.Context
import com.knu.blechprobe.model.TagForm

/** TAG 조건 입력을 앱을 껐다 켜도 남게 한다. 날·배치·높이를 매번 다시 고르지 않도록 */
internal class FormStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("tag_form", Context.MODE_PRIVATE)

    fun load(): TagForm {
        val d = TagForm()
        return TagForm(
            distanceM = p.getInt("distanceM", d.distanceM),
            block = enumOr(p.getString("block", null), d.block),
            pos = enumOr(p.getString("pos", null), d.pos),
            day = p.getInt("day", d.day),
            placement = p.getInt("placement", d.placement),
            wallMaterial = p.getString("wallMaterial", d.wallMaterial) ?: "",
            equipment = p.getString("equipment", d.equipment) ?: "",
            photo = p.getString("photo", d.photo) ?: "",
            phoneHeightCm = p.getString("phoneHeightCm", d.phoneHeightCm) ?: "",
            tagHeightCm = p.getString("tagHeightCm", d.tagHeightCm) ?: "",
            phoneOrient = p.getString("phoneOrient", d.phoneOrient) ?: "",
            tagOrient = p.getString("tagOrient", d.tagOrient) ?: "",
            operatorPos = p.getString("operatorPos", d.operatorPos) ?: "",
            registered = p.getBoolean("registered", d.registered),
            smartThingsConnected = p.getBoolean("smartThingsConnected", d.smartThingsConnected),
        )
    }

    fun save(f: TagForm) {
        p.edit()
            .putInt("distanceM", f.distanceM)
            .putString("block", f.block.name)
            .putString("pos", f.pos.name)
            .putInt("day", f.day)
            .putInt("placement", f.placement)
            .putString("wallMaterial", f.wallMaterial)
            .putString("equipment", f.equipment)
            .putString("photo", f.photo)
            .putString("phoneHeightCm", f.phoneHeightCm)
            .putString("tagHeightCm", f.tagHeightCm)
            .putString("phoneOrient", f.phoneOrient)
            .putString("tagOrient", f.tagOrient)
            .putString("operatorPos", f.operatorPos)
            .putBoolean("registered", f.registered)
            .putBoolean("smartThingsConnected", f.smartThingsConnected)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: def
}
