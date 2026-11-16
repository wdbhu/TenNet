package com.fuckad.xx.core;

import android.content.pm.ApplicationInfo;

import com.fuckad.xx.Logs;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 轻量 DEX 索引器：只解析「类名 / 父类 / 实现的接口 / 访问标志」，不加载类、不解析方法体。
 *
 * <p>比反射扫描快很多，也不受 App 混淆影响。所有读取都带边界检查，遇到损坏的 dex 直接跳过，
 * 不会让目标 App 崩溃。</p>
 */
public final class DexIndex {

    public interface Visitor {
        /**
         * @param name       类描述符，如 {@code Lcom/foo/Bar;}
         * @param superName  父类描述符，可能为 null
         * @param interfaces 直接实现的接口描述符
         * @param access     ​dex 访问标志（0x0200 接口、0x0400 抽象）
         */
        void onClassDef(String name, String superName, String[] interfaces, int access);
    }

    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;

    private static final String[] EMPTY = new String[0];
    private static final int MAX_STRING_BYTES = 4096;

    private DexIndex() {
    }

    public static List<File> apkPathsOf(ApplicationInfo info) {
        List<File> out = new ArrayList<>();
        if (info == null) return out;
        if (info.sourceDir != null) {
            out.add(new File(info.sourceDir));
        }
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) {
                if (path != null) out.add(new File(path));
            }
        }
        return out;
    }

    public static int scanApk(File apk, Visitor visitor) {
        if (apk == null || visitor == null || !apk.isFile()) return 0;
        int total = 0;
        ZipFile zip = null;
        final DexBuffer buffer = new DexBuffer();
        final long maxDex = maxDexBytes();
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || !name.startsWith("classes") || !name.endsWith(".dex")) {
                    continue;
                }
                long size = entry.getSize();
                if (size <= 0) continue;
                if (size > maxDex) {
                    Logs.w("跳过超大 dex " + name + " (" + size + " bytes)");
                    continue;
                }
                InputStream in = null;
                try {
                    in = zip.getInputStream(entry);
                    if (readFully(in, (int) size, buffer)) {
                        total += scanDex(buffer.data, buffer.length, visitor);
                    }
                } catch (Throwable t) {
                    Logs.w("读取 " + entry.getName() + " 失败: " + t);
                } finally {
                    closeQuietly(in);
                }
            }
        } catch (Throwable t) {
            Logs.w("扫描 " + apk.getName() + " 失败: " + t);
        } finally {
            closeQuietly(zip);
        }
        return total;
    }

    /** 按堆大小限制单个 dex 的读取上限，避免低内存机整包读入直接 OOM。 */
    private static long maxDexBytes() {
        long max = Runtime.getRuntime().maxMemory();
        if (max <= 0) return 32L * 1024 * 1024;
        long cap = max / 6;
        if (cap < 16L * 1024 * 1024) cap = 16L * 1024 * 1024;
        if (cap > 64L * 1024 * 1024) cap = 64L * 1024 * 1024;
        return cap;
    }

    /** 复用一个 dex 缓冲区，减少大文件反复分配造成的 GC 峰值。 */
    private static final class DexBuffer {
        byte[] data;
        int length;
    }

    /** 扫描任意路径：原始 .dex 直接解析，.apk/.jar 走 ZipFile。 */
    public static int scanPath(File file, Visitor visitor) {
        if (file == null || !file.isFile() || visitor == null) return 0;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] magic = new byte[4];
            int read = in.read(magic);
            if (read == 4 && magic[0] == 'd' && magic[1] == 'e'
                    && magic[2] == 'x' && magic[3] == '\n') {
                long size = file.length();
                if (size <= 0 || size > maxDexBytes()) return 0;
                closeQuietly(in);
                in = new FileInputStream(file);
                DexBuffer buffer = new DexBuffer();
                if (readFully(in, (int) size, buffer)) {
                    return scanDex(buffer.data, buffer.length, visitor);
                }
                return 0;
            }
        } catch (Throwable t) {
            Logs.w("读取 " + file.getName() + " 失败: " + t);
        } finally {
            closeQuietly(in);
        }
        return scanApk(file, visitor);
    }

    public static int scanDex(byte[] data, Visitor visitor) {
        return scanDex(data, data == null ? 0 : data.length, visitor);
    }

    public static int scanDex(byte[] data, int length, Visitor visitor) {
        if (data == null || length < 112 || length > data.length || visitor == null) return 0;
        if (data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n') return 0;

        final ByteBuffer buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        final int stringIdsSize = buf.getInt(0x38);
        final int stringIdsOff = buf.getInt(0x3C);
        final int typeIdsSize = buf.getInt(0x40);
        final int typeIdsOff = buf.getInt(0x44);
        final int classDefsSize = buf.getInt(0x60);
        final int classDefsOff = buf.getInt(0x64);

        if (!inRange(stringIdsOff, stringIdsSize, 4, length)
                || !inRange(typeIdsOff, typeIdsSize, 4, length)
                || !inRange(classDefsOff, classDefsSize, 32, length)) {
            return 0;
        }

        final Strings strings = new Strings(data, length, buf, stringIdsOff, stringIdsSize);
        int count = 0;
        for (int i = 0; i < classDefsSize; i++) {
            int base = classDefsOff + i * 32;
            int classIdx = buf.getInt(base);
            int access = buf.getInt(base + 4);
            int superIdx = buf.getInt(base + 8);
            int interfacesOff = buf.getInt(base + 12);

            String name = typeName(buf, strings, typeIdsOff, typeIdsSize, classIdx);
            if (name == null) continue;
            String superName = superIdx == -1
                    ? null
                    : typeName(buf, strings, typeIdsOff, typeIdsSize, superIdx);
            String[] interfaces = readTypeList(buf, strings, typeIdsOff, typeIdsSize,
                    interfacesOff, length);
            try {
                visitor.onClassDef(name, superName, interfaces, access);
                count++;
            } catch (Throwable t) {
                Logs.w("访问 " + name + " 失败: " + t);
            }
        }
        return count;
    }

    private static boolean inRange(int off, int count, int itemSize, int length) {
        if (off < 0 || count < 0) return false;
        long end = (long) off + (long) count * itemSize;
        return off <= length && end <= length;
    }

    private static String typeName(ByteBuffer buf,
                                   Strings strings,
                                   int typeIdsOff,
                                   int typeIdsSize,
                                   int typeIdx) {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) return null;
        int stringIdx = buf.getInt(typeIdsOff + typeIdx * 4);
        return strings.get(stringIdx);
    }

    private static String[] readTypeList(ByteBuffer buf,
                                         Strings strings,
                                         int typeIdsOff,
                                         int typeIdsSize,
                                         int off,
                                         int length) {
        if (off <= 0 || off + 4 > length) return EMPTY;
        int size = buf.getInt(off);
        if (size <= 0 || size > 4096 || off + 4L + size * 2L > length) return EMPTY;
        String[] out = new String[size];
        for (int i = 0; i < size; i++) {
            int typeIdx = buf.getShort(off + 4 + i * 2) & 0xFFFF;
            out[i] = typeName(buf, strings, typeIdsOff, typeIdsSize, typeIdx);
        }
        return out;
    }

    private static boolean readFully(InputStream in, int expected, DexBuffer holder) throws IOException {
        if (in == null || expected <= 0 || holder == null) return false;
        if (holder.data == null || holder.data.length < expected) {
            holder.data = new byte[expected];
        }
        int offset = 0;
        int read;
        while (offset < expected && (read = in.read(holder.data, offset, expected - offset)) > 0) {
            offset += read;
        }
        holder.length = offset;
        return offset > 0;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 懒加载字符串表，按需解码并缓存。 */
    private static final class Strings {

        private final byte[] data;
        private final int length;
        private final ByteBuffer buf;
        private final int idsOff;
        private final int size;
        private final String[] cache;

        Strings(byte[] data, int length, ByteBuffer buf, int idsOff, int size) {
            this.data = data;
            this.length = length;
            this.buf = buf;
            this.idsOff = idsOff;
            this.size = size;
            this.cache = new String[size];
        }

        String get(int index) {
            if (index < 0 || index >= size) return null;
            String cached = cache[index];
            if (cached != null) return cached;
            String value = null;
            try {
                int off = buf.getInt(idsOff + index * 4);
                if (off > 0 && off < length) {
                    value = decodeMutf8(data, skipUleb(data, off, length), length);
                }
            } catch (Throwable ignored) {
            }
            cache[index] = value;
            return value;
        }

        private static int skipUleb(byte[] data, int off, int length) {
            int p = off;
            while (p < length && (data[p] & 0x80) != 0) {
                p++;
            }
            return p + 1;
        }

        private static String decodeMutf8(byte[] data, int start, int length) {
            int end = start;
            while (end < length && data[end] != 0) {
                end++;
                if (end - start > MAX_STRING_BYTES) return null;
            }
            StringBuilder sb = new StringBuilder(end - start);
            int i = start;
            while (i < end) {
                int a = data[i++] & 0xFF;
                if (a < 0x80) {
                    sb.append((char) a);
                } else if ((a & 0xE0) == 0xC0 && i < end) {
                    int b = data[i++] & 0xFF;
                    sb.append((char) (((a & 0x1F) << 6) | (b & 0x3F)));
                } else if ((a & 0xF0) == 0xE0 && i + 1 < end) {
                    int b = data[i++] & 0xFF;
                    int c = data[i++] & 0xFF;
                    sb.append((char) (((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F)));
                } else {
                    sb.append('?');
                }
            }
            return sb.toString();
        }
    }
}
