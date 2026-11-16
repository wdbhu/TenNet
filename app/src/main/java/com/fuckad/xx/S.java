package com.fuckad.xx;

import java.nio.charset.StandardCharsets;

/** 字符串解密：dex 里只存 Base64(XOR) 结果，运行时还原。 */
public final class S {

    /** 密钥由两段常量异或得到，反编译看不到明文数组。 */
    private static byte[] key() {
        int[] a = {
            0x71,
            0x5F,
            0x63,
            0x32,
            0x65,
            0x75,
            0x55,
            0x43,
            0x15,
            0x37,
            0x62,
            0x05,
            0x78,
            0x43,
            0x2D,
            0x18
        };
        int[] b = {
            0x2B,
            0x6E,
            0x14,
            0x59,
            0x37,
            0x4C,
            0x21,
            0x0D,
            0x76,
            0x05,
            0x3A,
            0x68,
            0x4F,
            0x12,
            0x5D,
            0x2C
        };
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private S() {
    }

    public static String d(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            byte[] key = key();
            int salt = raw[0] & 0xFF;
            int len = raw.length - 1;
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                int b = raw[len - i] & 0xFF;                  // 倒序还原
                b ^= (i * 31 + salt) & 0xFF;                  // 第二轮掩码
                out[i] = (byte) (b ^ key[(i + salt) % key.length]);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
