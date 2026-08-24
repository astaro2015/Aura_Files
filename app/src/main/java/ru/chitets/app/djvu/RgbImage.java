package ru.chitets.app.djvu;

/** Simple Android-independent opaque RGB image. */
final class RgbImage {
    final int width, height;
    final int[] argb;
    RgbImage(int width, int height) throws DjvuException {
        if (width <= 0 || height <= 0 || (long)width * height > 128L * 1024 * 1024) throw new DjvuException("DjVu: недопустимый размер изображения " + width + "x" + height);
        this.width=width; this.height=height; this.argb=new int[width*height];
    }
    void setRgb(int x,int y,int r,int g,int b){argb[y*width+x]=0xff000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);}
    static int clamp(int v){return v<0?0:Math.min(255,v);}
}
