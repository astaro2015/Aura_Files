package ru.chitets.app.djvu;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** DjVu BZZ decompressor (ZP + MTF + inverse BWT), pure Java; adapted from the public DjVu v3 specification and the MIT clean implementation in djvu-rs. */
final class BzzDecoder {
    private static final int CTX_COUNT = 300;
    private static final int FREQ_SLOTS = 4;
    private static final int LEVEL_CTXIDS = 3;

    static byte[] decode(byte[] data) throws DjvuException {
        ZpDecoder zp = new ZpDecoder(data);
        int[] ctx = new int[CTX_COUNT];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int guard = 0;
        while (true) {
            int blockSize = decodeRawBits(zp, 24);
            if (blockSize == 0) break;
            // DjVu reference blocks are <= 4 MiB; keep a generous hard safety cap.
            if (blockSize < 1 || blockSize > 32 * 1024 * 1024) {
                throw new DjvuException("BZZ: недопустимый размер блока " + blockSize);
            }
            byte[] block = decodeOneBlock(zp, ctx, blockSize);
            out.write(block, 0, block.length);
            if (++guard > 65536) throw new DjvuException("BZZ: слишком много блоков");
        }
        return out.toByteArray();
    }

    private static int decodeRawBits(ZpDecoder zp, int bitCount) {
        int limit = 1 << bitCount;
        int n = 1;
        while (n < limit) n = (n << 1) | (zp.decodePassthrough() ? 1 : 0);
        return n - limit;
    }

    private static int decodeContextBits(ZpDecoder zp, int[] ctx, int ctxBase, int bitCount) {
        int subtreeOffset = ctxBase - 1;
        int limit = 1 << bitCount;
        int n = 1;
        while (n < limit) {
            int bit = zp.decode(ctx, subtreeOffset + n) ? 1 : 0;
            n = (n << 1) | bit;
        }
        return n - limit;
    }

    private static byte[] decodeOneBlock(ZpDecoder zp, int[] ctx, int blockSize) throws DjvuException {
        int freqShift = 0;
        if (zp.decodePassthrough()) {
            freqShift++;
            if (zp.decodePassthrough()) freqShift++;
        }

        byte[] mtfOrder = new byte[256];
        for (int i = 0; i < 256; i++) mtfOrder[i] = (byte) i;
        long[] freqCounts = new long[FREQ_SLOTS];
        long freqAdd = 4;
        int lastMtfPos = 3;
        int markerAt = -1;
        byte[] bwtData = new byte[blockSize];

        for (int symIdx = 0; symIdx < blockSize; symIdx++) {
            int ctxId = Math.min(lastMtfPos, LEVEL_CTXIDS - 1);
            int mtfPosition;
            int off = 0;
            if (zp.decode(ctx, off + ctxId)) {
                mtfPosition = 0;
            } else {
                off += LEVEL_CTXIDS;
                if (zp.decode(ctx, off + ctxId)) {
                    mtfPosition = 1;
                } else {
                    off += LEVEL_CTXIDS;
                    if (zp.decode(ctx, off)) {
                        mtfPosition = 2 + decodeContextBits(zp, ctx, off + 1, 1);
                    } else {
                        off += 2;
                        if (zp.decode(ctx, off)) {
                            mtfPosition = 4 + decodeContextBits(zp, ctx, off + 1, 2);
                        } else {
                            off += 4;
                            if (zp.decode(ctx, off)) {
                                mtfPosition = 8 + decodeContextBits(zp, ctx, off + 1, 3);
                            } else {
                                off += 8;
                                if (zp.decode(ctx, off)) {
                                    mtfPosition = 16 + decodeContextBits(zp, ctx, off + 1, 4);
                                } else {
                                    off += 16;
                                    if (zp.decode(ctx, off)) {
                                        mtfPosition = 32 + decodeContextBits(zp, ctx, off + 1, 5);
                                    } else {
                                        off += 32;
                                        if (zp.decode(ctx, off)) {
                                            mtfPosition = 64 + decodeContextBits(zp, ctx, off + 1, 6);
                                        } else {
                                            off += 64;
                                            if (zp.decode(ctx, off)) {
                                                mtfPosition = 128 + decodeContextBits(zp, ctx, off + 1, 7);
                                            } else {
                                                mtfPosition = 256;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            lastMtfPos = mtfPosition;
            if (mtfPosition == 256) {
                bwtData[symIdx] = 0;
                markerAt = symIdx;
                continue;
            }
            if (mtfPosition < 0 || mtfPosition >= 256) throw new DjvuException("BZZ: позиция MTF вне диапазона");
            byte sym = mtfOrder[mtfPosition];
            bwtData[symIdx] = sym;

            freqAdd = (freqAdd + (freqAdd >>> freqShift)) & 0xffff_ffffL;
            if (freqAdd > 0x1000_0000L) {
                freqAdd >>>= 24;
                for (int i = 0; i < FREQ_SLOTS; i++) freqCounts[i] >>>= 24;
            }
            long combined = freqAdd;
            if (mtfPosition < FREQ_SLOTS) combined += freqCounts[mtfPosition];

            int insertAt = mtfPosition;
            while (insertAt >= FREQ_SLOTS) {
                mtfOrder[insertAt] = mtfOrder[insertAt - 1];
                insertAt--;
            }
            while (insertAt > 0) {
                long prevFreq = freqCounts[insertAt - 1];
                if (combined >= prevFreq) {
                    mtfOrder[insertAt] = mtfOrder[insertAt - 1];
                    freqCounts[insertAt] = prevFreq;
                    insertAt--;
                } else break;
            }
            mtfOrder[insertAt] = sym;
            if (insertAt < FREQ_SLOTS) freqCounts[insertAt] = combined;
        }
        if (markerAt < 0) throw new DjvuException("BZZ: не найден BWT-маркер");
        return inverseBwt(bwtData, markerAt);
    }

    private static byte[] inverseBwt(byte[] bwt, int markerPos) throws DjvuException {
        int total = bwt.length;
        if (total == 0) return new byte[0];
        if (markerPos < 0 || markerPos >= total) throw new DjvuException("BZZ: неверная позиция BWT-маркера");
        int[] byteCount = new int[256];
        int[] rank = new int[total];
        for (int i = 0; i < total; i++) {
            if (i == markerPos) continue;
            int v = bwt[i] & 0xff;
            rank[i] = (v << 24) | (byteCount[v] & 0x00ff_ffff);
            byteCount[v]++;
        }
        int[] sortedStart = new int[256];
        int running = 1;
        for (int v = 0; v < 256; v++) {
            sortedStart[v] = running;
            running += byteCount[v];
        }
        byte[] output = new byte[total - 1];
        int follow = 0;
        int remaining = output.length;
        int steps = 0;
        while (remaining > 0) {
            if (follow < 0 || follow >= rank.length) throw new DjvuException("BZZ: повреждён BWT follow-chain");
            int encoded = rank[follow];
            int v = (encoded >>> 24) & 0xff;
            int occurrence = encoded & 0x00ff_ffff;
            output[--remaining] = (byte) v;
            follow = sortedStart[v] + occurrence;
            if (++steps > total + 1) throw new DjvuException("BZZ: зацикленный BWT follow-chain");
        }
        return output;
    }

    private BzzDecoder() {}
}
