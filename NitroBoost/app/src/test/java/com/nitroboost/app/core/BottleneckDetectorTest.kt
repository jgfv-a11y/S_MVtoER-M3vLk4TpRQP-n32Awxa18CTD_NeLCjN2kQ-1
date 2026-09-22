package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.Bottleneck
import com.nitroboost.app.core.adaptive.BottleneckDetector
import com.nitroboost.app.core.adaptive.FrameMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckDetectorTest {

    private fun metrics(
        fps: Int? = 60,
        cpu: Int = 50,
        ram: Int = 50,
        ping: Int? = 30,
        retrans: Int? = 0,
        thermal: Int = 0
    ) = FrameMetrics(fps, 60, cpu, ram, ping, retrans, thermal, 35.0)

    @Test fun `no fps source is unknown`() {
        assertEquals(Bottleneck.UNKNOWN, BottleneckDetector.detect(metrics(fps = null)))
    }

    @Test fun `thermal status outranks everything`() {
        assertEquals(Bottleneck.THERMAL, BottleneckDetector.detect(metrics(thermal = 2)))
        assertEquals(Bottleneck.THERMAL, BottleneckDetector.detect(metrics(thermal = 4)))
    }

    @Test fun `high retransmissions or ping means network`() {
        assertEquals(Bottleneck.NETWORK, BottleneckDetector.detect(metrics(ping = 150)))
        assertEquals(Bottleneck.NETWORK, BottleneckDetector.detect(metrics(retrans = 30)))
    }

    @Test fun `cpu saturation means cpu`() {
        assertEquals(Bottleneck.CPU, BottleneckDetector.detect(metrics(cpu = 95, fps = 45)))
    }

    @Test fun `ram pressure means memory`() {
        assertEquals(Bottleneck.MEMORY, BottleneckDetector.detect(metrics(ram = 95, fps = 45)))
    }

    @Test fun `below target with nothing saturated is gpu bound`() {
        assertEquals(Bottleneck.GPU, BottleneckDetector.detect(metrics(fps = 48, cpu = 70)))
    }

    @Test fun `at or above target with no pressure is none`() {
        assertEquals(Bottleneck.NONE, BottleneckDetector.detect(metrics(fps = 60)))
        assertEquals(Bottleneck.NONE, BottleneckDetector.detect(metrics(fps = 90)))
    }
}
