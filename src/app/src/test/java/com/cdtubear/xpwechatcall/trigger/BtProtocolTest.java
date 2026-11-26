package com.cdtubear.xpwechatcall.trigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 协议常量是手机端和设备端的共同契约，改动时必须显式同步文档和固件。
 */
public class BtProtocolTest {

    @Test
    public void serviceUuidsStayCompatible() {
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BtProtocol.SERVICE_UUID.toString());
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            BtProtocol.RX_UUID.toString());
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            BtProtocol.TX_UUID.toString());
    }

    @Test
    public void actionsCoverCallLifecycle() {
        assertEquals("incoming", BtProtocol.ACTION_INCOMING);
        assertEquals("answer", BtProtocol.ACTION_ANSWER);
        assertEquals("end", BtProtocol.ACTION_END);
        assertEquals("call", BtProtocol.ACTION_CALL);
        assertEquals("query", BtProtocol.ACTION_QUERY);
    }

    @Test
    public void frameLimitMatchesDocumentedValue() {
        assertEquals(1024, BtProtocol.MAX_FRAME);
        assertTrue(BtProtocol.MAX_FRAME > 0);
    }
}
