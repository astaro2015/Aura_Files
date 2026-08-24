package ru.chitets.app.djvu;

/** Foreground palette used by DjVu FGbz chunks; format handling follows the public DjVu v3 specification / MIT djvu-rs reference. */
final class FgbzPalette {
    final int[] colors;   // opaque ARGB
    final int[] indices;  // blit index -> palette index

    private FgbzPalette(int[] colors, int[] indices) {
        this.colors = colors;
        this.indices = indices;
    }

    static FgbzPalette parse(byte[] data) throws DjvuException {
        if (data == null || data.length < 3) return new FgbzPalette(new int[0], new int[0]);
        int version = data[0] & 0xff;
        int count = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        if (count <= 0) return new FgbzPalette(new int[0], new int[0]);
        long colorBytesLong = (long) count * 3L;
        if (colorBytesLong > Integer.MAX_VALUE) throw new DjvuException("FGbz: слишком большая палитра");
        int colorBytes = (int) colorBytesLong;
        int[] colors = new int[count];
        int p = 3;
        for (int i = 0; i < count; i++) {
            int b = p < data.length ? data[p] & 0xff : 0;
            int g = p + 1 < data.length ? data[p + 1] & 0xff : 0;
            int r = p + 2 < data.length ? data[p + 2] & 0xff : 0;
            colors[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            p += 3;
        }

        if ((version & 0x80) == 0) return new FgbzPalette(colors, new int[0]);
        int idxStart = 3 + colorBytes;
        if (idxStart + 3 > data.length) return new FgbzPalette(colors, new int[0]);
        int n = ((data[idxStart] & 0xff) << 16) | ((data[idxStart + 1] & 0xff) << 8) | (data[idxStart + 2] & 0xff);
        byte[] packed = new byte[data.length - (idxStart + 3)];
        System.arraycopy(data, idxStart + 3, packed, 0, packed.length);
        byte[] decoded = BzzDecoder.decode(packed);
        int available = Math.min(n, decoded.length / 2);
        int[] indices = new int[available];
        for (int i = 0; i < available; i++) {
            int v = (short) (((decoded[i * 2] & 0xff) << 8) | (decoded[i * 2 + 1] & 0xff));
            indices[i] = v;
        }
        return new FgbzPalette(colors, indices);
    }

    int colorAtIndex(int idx) {
        if (colors.length == 0) return 0xff000000;
        return (idx >= 0 && idx < colors.length) ? colors[idx] : colors[0];
    }

    int colorForBlit(int blit) {
        if (colors.length == 0) return 0xff000000;
        if (blit >= 0 && blit < indices.length) {
            int idx = indices[blit];
            if (idx >= 0 && idx < colors.length) return colors[idx];
        }
        return colors[0];
    }
}
