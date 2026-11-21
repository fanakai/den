package ai.fanak.den.core.device

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceProbeTest {
    @Test
    fun cpuFeaturesFromCpuinfo() {
        // The A52s (SM7325), as /proc/cpuinfo gives it for each core.
        val a52s = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
        assertEquals(setOf("fp16", "dotprod"), CpuFeatures.parse(a52s.repeat(8)))
        // A feature only some cores have is left out.
        assertEquals(setOf("dotprod"), CpuFeatures.parse("Features : asimddp i8mm\nFeatures : asimddp\n"))
        assertEquals(emptySet(), CpuFeatures.parse(""))
    }

    @Test
    fun unknownChipsGetTheirSeries() {
        assertEquals("snapdragon-778g", SocFamilies.of("SM7325"))
        assertEquals("snapdragon-7-series", SocFamilies.of("SM7450"))
        assertEquals("snapdragon-8-series", SocFamilies.of("sm8650"))
        assertEquals("mediatek-dimensity", SocFamilies.of("MT6983"))
        assertEquals("mediatek-helio", SocFamilies.of("MT6785"))
        assertEquals("google-tensor", SocFamilies.of("Tensor G3"))
        assertEquals("samsung-exynos", SocFamilies.of("s5e9925"))
        assertEquals("unisoc-t", SocFamilies.of("T606"))
        assertEquals("kirin9000", SocFamilies.of("Kirin9000"))
    }
}
