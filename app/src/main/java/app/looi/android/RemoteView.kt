@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.looi.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot

@Composable fun RemoteDialog(remote: RemoteControl,connected: Boolean) {
    val state by remote.state.collectAsState()
    Dialog(onDismissRequest=remote::close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=720.dp).fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(24.dp)) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val compact=maxWidth > 520.dp && maxHeight < 520.dp
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("遥控器","Remote control"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
                            Text(if(connected) t("机器人已连接","Robot connected") else t("机器人未连接","Robot disconnected"),style=MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick=remote::close) {Text(t("关闭遥控","Close remote"))}
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=state.mode == RemoteMode.JOYSTICK,onClick={remote.mode(RemoteMode.JOYSTICK)},enabled=remote.ready,label={Text(t("摇杆","Joystick"))})
                        FilterChip(selected=state.mode == RemoteMode.BUTTONS,onClick={remote.mode(RemoteMode.BUTTONS)},enabled=remote.ready,label={Text(t("方向键","D-pad"))})
                        Button(onClick=remote::emergencyStop,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),modifier=Modifier.heightIn(min=48.dp)) { Text(t("停止","Stop")) }
                    }
                    if(compact) Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemotePad(remote,state)
                        RemoteInformation(remote,state,connected,Modifier.weight(1f))
                    } else {
                        RemoteInformation(remote,state,connected,Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { RemotePad(remote,state) }
                    }
                }
            }
        }
    }
}
@Composable private fun RemoteInformation(remote: RemoteControl,state: RemoteState,connected: Boolean,modifier: Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(state.message,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
        Text(t("手动控制期间暂停 AI 身体动作；对话可继续。","AI body actions are paused during manual control; conversation can continue."),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(t("速度","Speed")); Text(java.lang.String.format(java.util.Locale.ROOT,"%.2f",state.speed))
        }
        Slider(value=state.speed.toFloat(),onValueChange={remote.speed(it.toDouble())},valueRange=.05f.. .30f,enabled=remote.ready,modifier=Modifier.semantics { contentDescription=t("遥控速度","Remote speed") })
        if(state.phase == RemotePhase.FAILED) OutlinedButton(onClick=remote::retry,enabled=connected) {Text(t("重试","Retry"))}
    }
}
@Composable private fun RemotePad(remote: RemoteControl,state: RemoteState) {
    if(state.mode == RemoteMode.JOYSTICK) RemoteJoystick(remote)
    else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        RemoteDirection(remote,t("前进","Forward"),"↑",0.0,-1.0)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            RemoteDirection(remote,t("左转","Turn left"),"←",-1.0,0.0)
            Spacer(Modifier.size(56.dp))
            RemoteDirection(remote,t("右转","Turn right"),"→",1.0,0.0)
        }
        RemoteDirection(remote,t("后退","Reverse"),"↓",0.0,1.0)
    }
}
/** All pointer-up, cancellation, bounds exit and disposal paths invalidate the gesture. */
private fun Modifier.remoteHold(remote: RemoteControl,enabled: Boolean,joystick: Boolean=false,
    dx: Double=0.0,dy: Double=0.0,offset: (Offset)->Unit={}): Modifier = pointerInput(remote,enabled) {
    if(!enabled) return@pointerInput
    awaitEachGesture {
        val down=awaitFirstDown(requireUnconsumed=false)
        val id=remote.begin()
        try {
            if(id != null) {
                fun update(position: Offset) {
                    if(joystick) {
                        val radius=size.width*.33f
                        val relative=position-Offset(size.width/2f,size.height/2f)
                        val distance=hypot(relative.x,relative.y)
                        offset(relative*(if(distance > radius) radius/distance else 1f))
                        remote.update(id,(relative.x/radius).toDouble(),(relative.y/radius).toDouble())
                    } else remote.update(id,dx,dy)
                }
                update(down.position); down.consume()
                while(true) {
                    val event=awaitPointerEvent()
                    val pointer=event.changes.firstOrNull {it.id == down.id} ?: break
                    if(!pointer.pressed) {pointer.consume();break}
                    if(pointer.position.x !in 0f..size.width.toFloat() || pointer.position.y !in 0f..size.height.toFloat()) break
                    update(pointer.position);pointer.consume()
                }
            }
        } finally { if(id != null) remote.release(id);offset(Offset.Zero) }
    }
}
@Composable private fun RemoteDirection(remote: RemoteControl,label: String,symbol: String,x: Double,y: Double) {
    val state by remote.state.collectAsState()
    val enabled=state.presented && state.phase == RemotePhase.READY && remote.ready
    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(14.dp))
        .remoteHold(remote,enabled,dx=x,dy=y).semantics {
            role=Role.Button; contentDescription=label
            if(!remote.ready) disabled()
            onClick {if(remote.ready) {remote.pulse(x,y);true} else false}
        },contentAlignment=Alignment.Center) {Text(symbol,style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)}
}
@Composable private fun RemoteJoystick(remote: RemoteControl) {
    val state by remote.state.collectAsState()
    val enabled=state.presented && state.phase == RemotePhase.READY && remote.ready
    var offset by remember {mutableStateOf(Offset.Zero)}
    val primary=if(enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val surface=MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(184.dp).background(surface,CircleShape).remoteHold(remote,enabled,true,offset={offset=it}).semantics {
        contentDescription=t("摇杆，拖动控制方向，松手停止","Joystick: drag to steer, release to stop")
        if(!remote.ready) disabled()
        customActions=listOf(
            CustomAccessibilityAction(t("前进","Forward")) {if(remote.ready) {remote.pulse(0.0,-1.0);true} else false},
            CustomAccessibilityAction(t("后退","Reverse")) {if(remote.ready) {remote.pulse(0.0,1.0);true} else false},
            CustomAccessibilityAction(t("左转","Turn left")) {if(remote.ready) {remote.pulse(-1.0,0.0);true} else false},
            CustomAccessibilityAction(t("右转","Turn right")) {if(remote.ready) {remote.pulse(1.0,0.0);true} else false})
    }) {
        drawLine(Color.Gray,Offset(center.x-12.dp.toPx(),center.y),Offset(center.x+12.dp.toPx(),center.y),2.dp.toPx())
        drawLine(Color.Gray,Offset(center.x,center.y-12.dp.toPx()),Offset(center.x,center.y+12.dp.toPx()),2.dp.toPx())
        drawCircle(primary,radius=size.width*.17f,center=center+offset)
    }
}
