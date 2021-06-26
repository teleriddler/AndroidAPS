package info.nightscout.androidaps.diaconn.pumplog

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
* Battery Shortage Alarm Log
*/
class LOG_ALARM_BATTERY private constructor(
    val data: String,
    val dttm: String,
    typeAndKind: Byte,    // 1=INFO, 2=WARNING, 3=MAJOR, 4=CRITICAL
    private val alarmLevel: Byte,    // 1=OCCUR, 2=STOP
    private val ack: Byte,
    val batteryRemain: Byte
) {

    val type: Byte = PumplogUtil.getType(typeAndKind)
    val kind: Byte = PumplogUtil.getKind(typeAndKind)

    override fun toString(): String {
        val sb = StringBuilder("LOG_ALARM_BATTERY{")
        sb.append("LOG_KIND=").append(LOG_KIND.toInt())
        sb.append(", data='").append(data).append('\'')
        sb.append(", dttm='").append(dttm).append('\'')
        sb.append(", type=").append(type.toInt())
        sb.append(", kind=").append(kind.toInt())
        sb.append(", alarmLevel=").append(alarmLevel.toInt())
        sb.append(", ack=").append(ack.toInt())
        sb.append(", batteryRemain=").append(batteryRemain.toInt())
        sb.append('}')
        return sb.toString()
    }

    companion object {

        const val LOG_KIND: Byte = 0x28
        fun parse(data: String): LOG_ALARM_BATTERY {
            val bytes = PumplogUtil.hexStringToByteArray(data)
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return LOG_ALARM_BATTERY(
                data,
                PumplogUtil.getDttm(buffer),
                PumplogUtil.getByte(buffer),
                PumplogUtil.getByte(buffer),
                PumplogUtil.getByte(buffer),
                PumplogUtil.getByte(buffer)
            )
        }
    }

}