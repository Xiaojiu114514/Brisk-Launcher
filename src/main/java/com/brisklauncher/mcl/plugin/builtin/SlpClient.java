package com.brisklauncher.mcl.plugin.builtin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft 服务器列表查询（Server List Ping）的协议实现。
 *
 * <p>刻意**只依赖 JDK**：不碰 JavaFX、不碰 Jackson，方便单独验证与测试。
 * 回传原始 JSON，由呼叫方自己解析。
 *
 * <p>协议流程（1.7+ 通用）：
 * <pre>
 * C→S  handshake  {protocol, host, port, nextState=1}
 * C→S  request    {id=0}
 * S→C  response   {id=0, json}
 * </pre>
 */
public final class SlpClient {

    /** 一次查詢的原始結果。 */
    public record Response(String host, int port, long latencyMs, String json) {
    }

    /** 握手用的協議版本；服務器一般不校驗，只影響 status 裡的版本回報。 */
    private static final int PROTOCOL_VERSION = 767;

    private SlpClient() {
    }

    /**
     * 查询服务器状态。
     *
     * @param address    {@code host}、{@code host:port}、{@code [ipv6]:port}
     * @param timeoutMs  连线/读取超时（毫秒）
     */
    public static Response ping(String address, int timeoutMs) throws Exception {
        String raw = address == null ? "" : address.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.empty_address"));
        }
        String host = raw;
        int port = 25565;
        if (raw.startsWith("[")) {
            // IPv6 字面量：[::1]:25565
            int close = raw.indexOf(']');
            if (close > 0) {
                host = raw.substring(1, close);
                if (raw.length() > close + 2 && raw.charAt(close + 1) == ':') {
                    port = parseInt(raw.substring(close + 2), 25565);
                }
            }
        } else if (raw.indexOf(':') == raw.lastIndexOf(':') && raw.indexOf(':') > 0) {
            host = raw.substring(0, raw.indexOf(':'));
            port = parseInt(raw.substring(raw.indexOf(':') + 1), 25565);
        }

        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1) handshake
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarInt(body, 0x00);
            writeVarInt(body, PROTOCOL_VERSION);
            writeString(body, host);
            body.write((port >> 8) & 0xFF);
            body.write(port & 0xFF);
            writeVarInt(body, 1);
            writeVarInt(out, body.size());
            out.write(body.toByteArray());

            // 2) status request（空包，只有 id=0）
            writeVarInt(out, 1);
            out.write(0x00);
            out.flush();

            // 3) status response
            DataInputStream data = new DataInputStream(in);
            int length = readVarInt(data);
            if (length <= 0 || length > 4 * 1024 * 1024) {
                throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.bad_length", length));
            }
            byte[] payload = new byte[length];
            data.readFully(payload);
            long latency = (System.nanoTime() - start) / 1_000_000L;

            ByteArrayInputStream buffer = new ByteArrayInputStream(payload);
            int packetId = readVarInt(buffer);
            if (packetId != 0) {
                throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.bad_packet", packetId));
            }
            return new Response(host, port, latency, readString(buffer));
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception error) {
            return fallback;
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws java.io.IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static void writeString(OutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(InputStream in) throws java.io.IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int read = in.read();
            if (read < 0) {
                throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.closed"));
            }
            value |= (read & 0x7F) << position;
            if ((read & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 35) {
                throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.varint_too_long"));
            }
        }
    }

    private static String readString(InputStream in) throws java.io.IOException {
        int length = readVarInt(in);
        if (length < 0 || length > 4 * 1024 * 1024) {
            throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.bad_string", length));
        }
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException(com.brisklauncher.mcl.i18n.I18n.t("tools.slp.closed"));
            }
            offset += read;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
