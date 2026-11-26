package com.cdtubear.xpwechatcall.trigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class HttpRequestTest {

    private static HttpRequest parse(String raw) throws Exception {
        return HttpRequest.read(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.UTF_8)), 1024);
    }

    @Test
    public void parsesGetWithQuery() throws Exception {
        var request = parse("GET /v1/friends?offset=10&limit=20 HTTP/1.1\r\n"
            + "Authorization: Bearer test\r\nHost: 127.0.0.1\r\n\r\n");
        assertEquals("GET", request.method);
        assertEquals("/v1/friends", request.path);
        assertEquals("/v1/friends?offset=10&limit=20", request.target);
        assertEquals("Bearer test", request.headers.get("authorization"));
        assertEquals("", request.body);
    }

    @Test
    public void parsesPostBody() throws Exception {
        var body = "{\"wxid\":\"wxid_test\"}";
        var request = parse("POST /v1/call HTTP/1.1\r\nContent-Type: application/json\r\n"
            + "Content-Length: " + body.length() + "\r\n\r\n" + body);
        assertEquals("POST", request.method);
        assertEquals(body, request.body);
    }

    @Test
    public void rejectsChunkedBody() {
        try {
            parse("POST /v1/call HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
            throw new AssertionError("chunked should be rejected");
        } catch (HttpRequest.BadRequest e) {
            assertEquals(400, e.status);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void rejectsOversizedBody() {
        try {
            parse("POST /v1/call HTTP/1.1\r\nContent-Length: 2048\r\n\r\n");
            throw new AssertionError("oversized body should be rejected");
        } catch (HttpRequest.BadRequest e) {
            assertEquals(413, e.status);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void rejectsMalformedHeader() {
        try {
            parse("GET / HTTP/1.1\r\nBadHeader\r\n\r\n");
            throw new AssertionError("bad header should be rejected");
        } catch (HttpRequest.BadRequest e) {
            assertEquals(400, e.status);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void whitelistSupportsExactAndCidr() {
        assertTrue(IpWhitelist.allows("192.168.8.10", ""));
        assertTrue(IpWhitelist.allows("192.168.8.10", "192.168.8.10, 10.0.0.0/8"));
        assertTrue(IpWhitelist.allows("192.168.8.11", "192.168.8.0/24"));
        assertFalse(IpWhitelist.allows("192.168.9.11", "192.168.8.0/24"));
        assertFalse(IpWhitelist.allows("192.168.8.11", "10.0.0.0/8"));
        assertFalse(IpWhitelist.allows("192.168.8.11", "not-an-ip"));
    }
}
