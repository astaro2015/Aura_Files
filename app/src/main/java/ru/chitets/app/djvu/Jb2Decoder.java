package ru.chitets.app.djvu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JB2 bi-level image decoder used by Sjbz/Djbz chunks.
 * Pure Java translation/adaptation of the MIT clean implementation in djvu-rs.
 *
 * Chitets 0.5.7 memory note:
 * page masks are bit-packed (1 bit/pixel), and FGbz colours store the final
 * palette index rather than a 32-bit per-pixel blit number. This is essential
 * for 20+ MP scanned pages on Android's ~256 MB Java heap.
 */
final class Jb2Decoder {
    static final class Dict {
        final List<Jbm> symbols;
        Dict(List<Jbm> s) { symbols = s; }
    }

    static final class Mask {
        final int width, height;
        /** Bit-packed bitmap. Bit=1 means foreground. */
        final byte[] bits;
        /** Optional per-pixel FGbz palette index, one byte when palette <= 256. */
        final byte[] palette8;
        /** Optional per-pixel FGbz palette index, two bytes when palette > 256. */
        final short[] palette16;
        /** JB2 builds rows bottom-to-top; Smmr is top-to-bottom. */
        final boolean bottomUp;

        Mask(int w, int h, byte[] bits, byte[] palette8, short[] palette16, boolean bottomUp) {
            this.width=w; this.height=h; this.bits=bits;
            this.palette8=palette8==null?new byte[0]:palette8;
            this.palette16=palette16==null?new short[0]:palette16;
            this.bottomUp=bottomUp;
        }

        private int storageIndex(int x, int y) {
            if (x<0||y<0||x>=width||y>=height) return -1;
            int sy = bottomUp ? (height - 1 - y) : y;
            return sy * width + x;
        }

        boolean get(int x, int y) {
            int i=storageIndex(x,y); if(i<0)return false;
            return (bits[i>>>3] & (1 << (i&7))) != 0;
        }

        int paletteIndexAt(int x, int y) {
            int i=storageIndex(x,y); if(i<0)return 0;
            if (palette8.length != 0) return palette8[i] & 0xff;
            if (palette16.length != 0) return palette16[i] & 0xffff;
            return 0;
        }
    }

    private static final class NumContext {
        int[] ctx = new int[16], left = new int[16], right = new int[16];
        int size = 2; // 0 sentinel, 1 root
        int root() { return 1; }
        private void grow() {
            if (size < ctx.length) return;
            int n = ctx.length * 2;
            ctx=Arrays.copyOf(ctx,n); left=Arrays.copyOf(left,n); right=Arrays.copyOf(right,n);
        }
        int getLeft(int node) { if(left[node]==0){grow(); left[node]=size++;} return left[node]; }
        int getRight(int node){ if(right[node]==0){grow(); right[node]=size++;} return right[node]; }
    }

    private static int decodeNum(ZpDecoder zp, NumContext nc, int low0, int high0) {
        int low=low0, high=high0;
        boolean negative=false;
        int cutoff=0;
        int phase=1;
        long range=0xffff_ffffL;
        int node=nc.root();
        while (range != 1) {
            boolean decision;
            if (low >= cutoff) decision=true;
            else if (high >= cutoff) decision=zp.decode(nc.ctx,node);
            else decision=false;
            node = decision ? nc.getRight(node) : nc.getLeft(node);
            switch(phase) {
                case 1:
                    negative=!decision;
                    if(negative){ int t=-low-1; low=-high-1; high=t; }
                    phase=2; cutoff=1; break;
                case 2:
                    if(!decision){
                        phase=3; range=((cutoff+1L)/2L);
                        if(range==1) cutoff=0; else cutoff -= (int)(range/2);
                    } else cutoff = cutoff + cutoff + 1;
                    break;
                case 3:
                    range /= 2;
                    if(range != 1){ if(!decision) cutoff -= (int)(range/2); else cutoff += (int)(range/2); }
                    else if(!decision) cutoff -= 1;
                    break;
                default: throw new IllegalStateException();
            }
        }
        return negative ? -cutoff-1 : cutoff;
    }

    static final class Jbm {
        final int width,height;
        final byte[] data; // row 0 = bottom
        Jbm(int w,int h){ width=Math.max(0,w); height=Math.max(0,h); long n=(long)width*height; data=n>Integer.MAX_VALUE?new byte[0]:new byte[(int)n]; }
        int get(int row,int col){ if(row<0||row>=height||col<0||col>=width)return 0; return data[row*width+col]&1; }
        void set(int row,int col){ if(row>=0&&row<height&&col>=0&&col<width)data[row*width+col]=1; }
        Jbm trim(){
            int minR=height,maxR=-1,minC=width,maxC=-1;
            for(int r=0;r<height;r++)for(int c=0;c<width;c++)if(data[r*width+c]!=0){minR=Math.min(minR,r);maxR=Math.max(maxR,r);minC=Math.min(minC,c);maxC=Math.max(maxC,c);}
            if(maxR<0)return new Jbm(0,0);
            Jbm o=new Jbm(maxC-minC+1,maxR-minR+1);
            for(int r=minR;r<=maxR;r++)for(int c=minC;c<=maxC;c++)if(data[r*width+c]!=0)o.data[(r-minR)*o.width+(c-minC)]=1;
            return o;
        }
    }

    private static final class Baseline {
        final int[] a={0,0,0}; int index=-1;
        void fill(int v){a[0]=a[1]=a[2]=v;}
        void add(int v){index++;if(index==3)index=0;a[index]=v;}
        int val(){int x=a[0],y=a[1],z=a[2]; if((x>=y&&x<=z)||(x<=y&&x>=z))return x;if((y>=x&&y<=z)||(y<=x&&y>=z))return y;return z;}
    }

    private static Jbm decodeDirect(ZpDecoder zp,int[] ctx,int width,int height) throws DjvuException {
        if(width<0||height<0||(long)width*height>64L*1024*1024)throw new DjvuException("JB2: неверный размер символа "+width+"x"+height);
        Jbm bm=new Jbm(width,height); if(width<=0||height<=0)return bm;
        for(int row=height-1;row>=0;row--){
            int r2=(bm.get(row+2,0)<<1)|bm.get(row+2,1);
            int r1=(bm.get(row+1,0)<<2)|(bm.get(row+1,1)<<1)|bm.get(row+1,2);
            int r0=0;
            for(int col=0;col<width;col++){
                int idx=(r2<<7)|(r1<<2)|r0;
                boolean bit=zp.decode(ctx,idx); if(bit)bm.set(row,col);
                r2=((r2<<1)&7)|bm.get(row+2,col+2);
                r1=((r1<<1)&31)|bm.get(row+1,col+3);
                r0=((r0<<1)&3)|(bit?1:0);
            }
        }
        return bm;
    }

    private static Jbm decodeRef(ZpDecoder zp,int[] ctx,int width,int height,Jbm mbm) throws DjvuException {
        if(width<0||height<0||(long)Math.max(0,width)*Math.max(0,height)>64L*1024*1024)throw new DjvuException("JB2: неверный размер refinement-символа");
        Jbm cbm=new Jbm(width,height); if(width<=0||height<=0)return cbm;
        int crow=(height-1)>>1, ccol=(width-1)>>1, mrow=(mbm.height-1)>>1, mcol=(mbm.width-1)>>1;
        int rowShift=mrow-crow, colShift=mcol-ccol;
        for(int row=height-1;row>=0;row--){
            int mr=row+rowShift, cs=colShift;
            int cR1=(cbm.get(row+1,0)<<1)|cbm.get(row+1,1), cR0=0;
            int mR1=(mbm.get(mr,cs-1)<<2)|(mbm.get(mr,cs)<<1)|mbm.get(mr,cs+1);
            int mR0=(mbm.get(mr-1,cs-1)<<2)|(mbm.get(mr-1,cs)<<1)|mbm.get(mr-1,cs+1);
            for(int col=0;col<width;col++){
                int mR2=mbm.get(mr+1,col+colShift);
                int idx=(cR1<<8)|(cR0<<7)|(mR2<<6)|(mR1<<3)|mR0;
                boolean bit=zp.decode(ctx,idx); if(bit)cbm.set(row,col);
                cR1=((cR1<<1)&7)|cbm.get(row+1,col+2); cR0=bit?1:0;
                mR1=((mR1<<1)&7)|mbm.get(mr,col+colShift+2);
                mR0=((mR0<<1)&7)|mbm.get(mr-1,col+colShift+2);
            }
        }
        return cbm;
    }

    static Mask decode(byte[] data, Dict shared) throws DjvuException {
        return decodeInner(data,shared,null,0);
    }

    static Mask decodeIndexed(byte[] data, Dict shared, int[] blitToPalette, int paletteCount) throws DjvuException {
        return decodeInner(data,shared,blitToPalette,paletteCount);
    }

    private static Mask decodeInner(byte[] data,Dict shared,int[] blitToPalette,int paletteCount) throws DjvuException {
        ZpDecoder zp=new ZpDecoder(data);
        NumContext record=new NumContext(), imageSize=new NumContext(), sw=new NumContext(), sh=new NumContext(), inherit=new NumContext();
        NumContext hoff=new NumContext(),voff=new NumContext(),shoff=new NumContext(),svoff=new NumContext(),symIndex=new NumContext();
        NumContext wdiff=new NumContext(),hdiff=new NumContext(),hAbs=new NumContext(),vAbs=new NumContext(),commentLen=new NumContext(),commentOctet=new NumContext();
        int[] offsetType={0}, direct=new int[1024], refine=new int[2048], flag={0};

        int rtype=decodeNum(zp,record,0,11), initial=0;
        if(rtype==9){initial=decodeNum(zp,inherit,0,262142);rtype=decodeNum(zp,record,0,11);}
        int imageW=decodeNum(zp,imageSize,0,262142); if(imageW==0)imageW=200;
        int imageH=decodeNum(zp,imageSize,0,262142); if(imageH==0)imageH=200;
        if(zp.decode(flag))throw new DjvuException("JB2: неверный флаг заголовка");
        long pixelLong=(long)imageW*imageH;
        if(imageW<=0||imageH<=0||pixelLong>64L*1024*1024)throw new DjvuException("JB2: слишком большая страница "+imageW+"x"+imageH);
        int pixels=(int)pixelLong;
        List<Jbm> dict=new ArrayList<>();
        if(initial>0){if(shared==null)throw new DjvuException("JB2: требуется общий Djbz-словарь");if(initial>shared.symbols.size())throw new DjvuException("JB2: размер унаследованного словаря превышает Djbz");dict.addAll(shared.symbols.subList(0,initial));}

        // 1 bit/pixel instead of byte[pixels]. A 22.8 MP mask is ~2.85 MB.
        byte[] pageBits=new byte[(pixels+7)>>>3];
        byte[] palette8=null;
        short[] palette16=null;
        if(blitToPalette!=null && blitToPalette.length>0 && paletteCount>1){
            // Store the final palette index, not the blit number. Most DjVu
            // palettes fit in 8 bits, so the 22.8 MP test page needs about
            // 22.8 MB here instead of a 91.2 MB int[]. If all actually used
            // indices fit in 8 bits, keep using byte[] even with a larger
            // declared palette. On an extremely tight heap, exact per-glyph
            // colouring is optional: fall back to palette[0] rather than fail
            // the whole page with OOM.
            int maxUsed=0;
            for(int v:blitToPalette) if(v>=0 && v<paletteCount && v>maxUsed) maxUsed=v;
            try {
                if(maxUsed<=255) palette8=new byte[pixels];
                else palette16=new short[pixels];
            } catch (OutOfMemoryError ignored) {
                palette8=null; palette16=null;
            }
        }
        int blitCount=0;
        int[] pos={-1,imageH-1,0}; // firstLeft, firstBottom, lastRight
        Baseline baseline=new Baseline();
        int guard=0;
        while(true){
            if(++guard>20_000_000)throw new DjvuException("JB2: повреждённый поток (слишком много записей)");
            rtype=decodeNum(zp,record,0,11);
            switch(rtype){
                case 1:{
                    Jbm bm=decodeDirect(zp,direct,decodeNum(zp,sw,0,262142),decodeNum(zp,sh,0,262142));
                    int[] xy=coords(zp,offsetType,hoff,voff,shoff,svoff,pos,baseline,bm.width,bm.height);blit(pageBits,palette8,palette16,blitToPalette,paletteCount,imageW,imageH,blitCount++,bm,xy[0],xy[1]);dict.add(bm.trim());break;}
                case 2:{Jbm bm=decodeDirect(zp,direct,decodeNum(zp,sw,0,262142),decodeNum(zp,sh,0,262142));dict.add(bm.trim());break;}
                case 3:{Jbm bm=decodeDirect(zp,direct,decodeNum(zp,sw,0,262142),decodeNum(zp,sh,0,262142));int[]xy=coords(zp,offsetType,hoff,voff,shoff,svoff,pos,baseline,bm.width,bm.height);blit(pageBits,palette8,palette16,blitToPalette,paletteCount,imageW,imageH,blitCount++,bm,xy[0],xy[1]);break;}
                case 4:case 5:case 6:{
                    if(dict.isEmpty())throw new DjvuException("JB2: ссылка на пустой словарь");
                    int idx=decodeNum(zp,symIndex,0,dict.size()-1); if(idx<0||idx>=dict.size())throw new DjvuException("JB2: индекс символа вне словаря");
                    Jbm mbm=dict.get(idx);int cw=mbm.width+decodeNum(zp,wdiff,-262143,262142),ch=mbm.height+decodeNum(zp,hdiff,-262143,262142);
                    Jbm cbm=decodeRef(zp,refine,cw,ch,mbm);
                    if(rtype!=5){int[]xy=coords(zp,offsetType,hoff,voff,shoff,svoff,pos,baseline,cbm.width,cbm.height);blit(pageBits,palette8,palette16,blitToPalette,paletteCount,imageW,imageH,blitCount++,cbm,xy[0],xy[1]);}
                    if(rtype!=6)dict.add(cbm.trim());break;}
                case 7:{
                    if(dict.isEmpty())throw new DjvuException("JB2: ссылка на пустой словарь");int idx=decodeNum(zp,symIndex,0,dict.size()-1);if(idx<0||idx>=dict.size())throw new DjvuException("JB2: индекс символа вне словаря");Jbm bm=dict.get(idx);int[]xy=coords(zp,offsetType,hoff,voff,shoff,svoff,pos,baseline,bm.width,bm.height);blit(pageBits,palette8,palette16,blitToPalette,paletteCount,imageW,imageH,blitCount++,bm,xy[0],xy[1]);break;}
                case 8:{
                    Jbm bm=decodeDirect(zp,direct,decodeNum(zp,sw,0,262142),decodeNum(zp,sh,0,262142));int left=decodeNum(zp,hAbs,1,imageW),top=decodeNum(zp,vAbs,1,imageH);blit(pageBits,palette8,palette16,blitToPalette,paletteCount,imageW,imageH,blitCount++,bm,left-1,top-bm.height);break;}
                case 9: break;
                case 10:{int n=decodeNum(zp,commentLen,0,262142);for(int i=0;i<n;i++)decodeNum(zp,commentOctet,0,255);break;}
                // No giant flip/copy here. Mask remembers that JB2 row 0 is bottom.
                case 11:return new Mask(imageW,imageH,pageBits,palette8,palette16,true);
                default:throw new DjvuException("JB2: неизвестный тип записи "+rtype);
            }
        }
    }

    static Dict decodeDict(byte[] data,Dict inherited) throws DjvuException {
        ZpDecoder zp=new ZpDecoder(data);
        NumContext record=new NumContext(), imageSize=new NumContext(), sw=new NumContext(), sh=new NumContext(), inherit=new NumContext(),symIndex=new NumContext(),wdiff=new NumContext(),hdiff=new NumContext(),commentLen=new NumContext(),commentOctet=new NumContext();
        int[] direct=new int[1024],refine=new int[2048],flag={0};
        int rtype=decodeNum(zp,record,0,11),initial=0;if(rtype==9){initial=decodeNum(zp,inherit,0,262142);rtype=decodeNum(zp,record,0,11);}
        decodeNum(zp,imageSize,0,262142);decodeNum(zp,imageSize,0,262142);if(zp.decode(flag))throw new DjvuException("JB2/Djbz: неверный флаг заголовка");
        List<Jbm> dict=new ArrayList<>();if(initial>0){if(inherited==null)throw new DjvuException("JB2/Djbz: нужен унаследованный словарь");if(initial>inherited.symbols.size())throw new DjvuException("JB2/Djbz: наследуется слишком много символов");dict.addAll(inherited.symbols.subList(0,initial));}
        int guard=0;
        while(true){if(++guard>10_000_000)throw new DjvuException("JB2/Djbz: повреждённый поток");rtype=decodeNum(zp,record,0,11);switch(rtype){
            case 2:{Jbm bm=decodeDirect(zp,direct,decodeNum(zp,sw,0,262142),decodeNum(zp,sh,0,262142));dict.add(bm.trim());break;}
            case 5:{if(dict.isEmpty())throw new DjvuException("JB2/Djbz: ссылка на пустой словарь");int idx=decodeNum(zp,symIndex,0,dict.size()-1);if(idx<0||idx>=dict.size())throw new DjvuException("JB2/Djbz: индекс вне словаря");Jbm mbm=dict.get(idx);Jbm cbm=decodeRef(zp,refine,mbm.width+decodeNum(zp,wdiff,-262143,262142),mbm.height+decodeNum(zp,hdiff,-262143,262142),mbm);dict.add(cbm.trim());break;}
            case 9:break;
            case 10:{int n=decodeNum(zp,commentLen,0,262142);for(int i=0;i<n;i++)decodeNum(zp,commentOctet,0,255);break;}
            case 11:return new Dict(dict);
            default:throw new DjvuException("JB2/Djbz: неожиданный тип записи "+rtype);
        }}
    }

    private static int[] coords(ZpDecoder zp,int[] offset,NumContext hoff,NumContext voff,NumContext shoff,NumContext svoff,int[] pos,Baseline base,int w,int h){
        boolean newLine=zp.decode(offset);int x,y;if(newLine){int ho=decodeNum(zp,hoff,-262143,262142),vo=decodeNum(zp,voff,-262143,262142);x=pos[0]+ho;y=pos[1]+vo-h+1;pos[0]=x;pos[1]=y;base.fill(y);}else{int ho=decodeNum(zp,shoff,-262143,262142),vo=decodeNum(zp,svoff,-262143,262142);x=pos[2]+ho;y=base.val()+vo;}base.add(y);pos[2]=x+w-1;return new int[]{x,y};
    }

    private static void setBit(byte[] bits,int i){ bits[i>>>3] |= (byte)(1 << (i&7)); }

    private static int paletteForBlit(int[] blitToPalette,int paletteCount,int blit){
        if(blitToPalette==null || paletteCount<=0 || blit<0 || blit>=blitToPalette.length) return 0;
        int p=blitToPalette[blit];
        return (p>=0 && p<paletteCount) ? p : 0;
    }

    private static void blit(byte[] pageBits,byte[] palette8,short[] palette16,int[] blitToPalette,int paletteCount,int pw,int ph,int blit,Jbm s,int x,int y){
        final int palette=paletteForBlit(blitToPalette,paletteCount,blit);
        for(int r=0;r<s.height;r++){
            int py=y+r;if(py<0||py>=ph)continue;
            for(int c=0;c<s.width;c++){
                if(s.get(r,c)==0)continue;
                int px=x+c;if(px<0||px>=pw)continue;
                int i=py*pw+px;
                setBit(pageBits,i);
                if(palette8!=null) palette8[i]=(byte)palette;
                else if(palette16!=null) palette16[i]=(short)palette;
            }
        }
    }

    private Jb2Decoder() {}
}
