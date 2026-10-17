package app.looi.android
import java.io.InputStream
import java.io.ByteArrayOutputStream
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

internal class FirmwareProgressBody(private val body:RequestBody,private val progress:((Double)->Unit)?):RequestBody(){
    override fun contentType()=body.contentType()
    override fun contentLength()=body.contentLength()
    override fun writeTo(sink:BufferedSink){
        var sent=0L;val total=contentLength()
        val counted=object:ForwardingSink(sink){override fun write(source:okio.Buffer,byteCount:Long){super.write(source,byteCount);sent+=byteCount;if(total>0)progress?.invoke((sent.toDouble()/total).coerceIn(0.0,1.0))}}.buffer()
        body.writeTo(counted);counted.flush()
    }
}
internal fun readFirmwareStream(input:InputStream,limit:Int,expected:Long=0,progress:((Double)->Unit)?=null):ByteArray {
    val output=ByteArrayOutputStream();val buffer=ByteArray(8192);progress?.invoke(0.0)
    while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=limit){"Release too large"};output.write(buffer,0,n);if(expected>0)progress?.invoke((output.size().toDouble()/expected).coerceIn(0.0,1.0))}
    require(expected<=0||output.size().toLong()==expected){"Firmware download interrupted"};return output.toByteArray()
}
