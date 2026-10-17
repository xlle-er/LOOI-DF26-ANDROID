package app.looi.android
import org.json.JSONObject

internal fun needsMotorWiring(info:JSONObject)=info.has("hardware_configured")&&!info.optBoolean("hardware_configured")
internal fun defaultMotorWiring(info:JSONObject):JSONObject? {
    if(!needsMotorWiring(info))return null
    val board=info.optJSONObject("hardware_profile")?.optString("board")?:return null
    if(board !in listOf("n16r8","n8r8"))return null
    return JSONObject().put("version",1).put("board",board).put("pins",JSONObject().put("ena",17).put("in1",16).put("in2",15).put("in3",7).put("in4",6).put("enb",5))
}
