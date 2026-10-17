package app.looi.android

internal fun compareReleaseVersions(lhs: String, rhs: String): Int {
    fun parts(s: String): Pair<List<String>,List<String>?> {
        val p=s.removePrefix("v").substringBefore('+').split('-',limit=2)
        return p[0].split('.') to p.getOrNull(1)?.split('.')
    }
    fun numeric(s: String)=s.isNotEmpty()&&s.all{it in '0'..'9'}
    fun number(a: String,b: String): Int {
        val x=a.trimStart('0');val y=b.trimStart('0')
        return if(x.length!=y.length)x.length.compareTo(y.length) else x.compareTo(y)
    }
    val(a,ap)=parts(lhs);val(b,bp)=parts(rhs)
    for(i in 0 until maxOf(a.size,b.size)){val n=number(a.getOrElse(i){"0"},b.getOrElse(i){"0"});if(n!=0)return n}
    if(ap==null||bp==null)return if(ap==bp)0 else if(ap==null)1 else -1
    for(i in 0 until minOf(ap.size,bp.size)) {
        if(ap[i]==bp[i])continue
        val an=numeric(ap[i]);val bn=numeric(bp[i])
        if(an&&bn)return number(ap[i],bp[i])
        if(an!=bn)return if(an)-1 else 1
        return ap[i].compareTo(bp[i])
    }
    return ap.size.compareTo(bp.size)
}
