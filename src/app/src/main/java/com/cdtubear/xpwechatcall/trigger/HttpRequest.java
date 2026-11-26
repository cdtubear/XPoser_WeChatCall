package com.cdtubear.xpwechatcall.trigger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP/1.1 请求解析器。
 *
 * 只服务于本机触发的几个固定接口，因此不支持 chunked、keep-alive 和 multipart；
 * 但会严格限制请求行、Header 和 Body 大小，避免畸形请求拖死服务线程。
 * 抽成纯 Java 类后可以在 JVM 单测里覆盖边界情况。
 */
public final class HttpRequest {

    public static final int MAX_HEADER_BYTES = 8 * 1024;

    public final String method;
    public final String target;
    public final String path;
    public final Map<String, String> headers;
    public final String body;

    private HttpRequest(String method, String target, String path,
                        Map<String, String> headers, String body) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.headers = headers;
        this.body = body;
    }

    public static final class BadRequest extends Exception {
        public final int status;

        public BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static HttpRequest read(InputStream rawInput, int maxBody)
        throws IOException, BadRequest {
        var input = rawInput instanceof BufferedInputStream
            ? (BufferedInputStream) rawInput : new BufferedInputStream(rawInput);
        var requestLine = readLine(input, MAX_HEADER_BYTES);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new BadRequest(400, "empty request");
        }
        var parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new BadRequest(400, "invalid request line");
        }
        var method = parts[0].toUpperCase(Locale.ROOT);
        var target = parts[1];
        var path = target;
        var query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }

        var headers = new HashMap<String, String>();
        var headerBytes = requestLine.length();
        while (true) {
            var line = readLine(input, MAX_HEADER_BYTES - headerBytes);
            if (line == null) {
                throw new BadRequest(400, "truncated headers");
            }
            if (line.isEmpty()) {
                break;
            }
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new BadRequest(431, "headers too large");
            }
            var colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequest(400, "invalid header");
            }
            var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            var value = line.substring(colon + 1).trim();
            var old = headers.put(name, value);
            if (old != null && "content-length".equals(name)) {
                throw new BadRequest(400, "duplicate content-length");
            }
        }

        var transfer = headers.get("transfer-encoding");
        if (transfer != null && !transfer.isEmpty()) {
            // 本项目不接收流式上传，直接拒绝，避免 readN 等待不存在的 Content-Length
            throw new BadRequest(400, "transfer-encoding not supported");
        }
        var lengthText = headers.get("content-length");
        int length = 0;
        if (lengthText != null && !lengthText.isEmpty()) {
            try {
                length = Integer.parseInt(lengthText);
            } catch (NumberFormatException e) {
                throw new BadRequest(400, "invalid content-length");
            }
        }
        if (length < 0) {
            throw new BadRequest(400, "invalid content-length");
        }
        if (length > maxBody) {
            throw new BadRequest(413, "body too large");
        }
        var body = length == 0 ? "" : new String(readN(input, length), StandardCharsets.UTF_8);
        return new HttpRequest(method, target, path, headers, body);
    }

    private static String readLine(InputStream input, int maxBytes) throws IOException {
        var builder = new StringBuilder();
        var count = 0;
        while (true) {
            var value = input.read();
            if (value < 0) {
                return builder.length() == 0 ? null : builder.toString();
            }
            if (value == '\n') {
                var length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            if (++count > maxBytes) {
                return builder.toString();
            }
            builder.append((char) value);
        }
    }

    private static byte[] readN(InputStream input, int length) throws IOException {
        var data = new byte[length];
        var offset = 0;
        while (offset < length) {
            var read = input.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("truncated body");
            }
            offset += read;
        }
        return data;
    }
}
