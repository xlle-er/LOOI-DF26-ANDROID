package app.looi.android
import org.junit.Test
import org.junit.Assert.assertEquals
class ReleaseVersionTest { @Test fun comparesSemver() {
assertEquals(1,compareReleaseVersions("2.2.0","2.1.0").compareTo(0))
assertEquals(1,compareReleaseVersions("2.10.0","2.9.9").compareTo(0))
assertEquals(0,compareReleaseVersions("2.2.0","2.2.0").compareTo(0))
assertEquals(-1,compareReleaseVersions("2.3.0-beta.2","2.3.0-beta.10").compareTo(0))
assertEquals(1,compareReleaseVersions("2.3.0","2.3.0-rc.1").compareTo(0))
assertEquals(1,compareReleaseVersions("2.3.0-beta","2.3.0-alpha").compareTo(0))
assertEquals(0,compareReleaseVersions("2.3.0+old","2.3.0+new").compareTo(0))
assertEquals(0,compareReleaseVersions("v2.2.0","2.2.0").compareTo(0))
assertEquals(1,compareReleaseVersions("2.3.0-beta","2.2.9").compareTo(0))
assertEquals(-1,compareReleaseVersions("2.3.0-1","2.3.0-alpha").compareTo(0))
assertEquals(-1,compareReleaseVersions("2.3.0-beta","2.3.0-beta.1").compareTo(0))
}}
