package ru.chitets.app.djvu;

import java.util.Arrays;

/**
 * DjVu IW44 progressive wavelet decoder for BG44/FG44 chunks.
 * Pure Java translation/adaptation of the MIT clean implementation in djvu-rs.
 */
final class Iw44Decoder {
    private static final int[][] BAND_BUCKETS={{0,0},{1,1},{2,2},{3,3},{4,7},{8,11},{12,15},{16,31},{32,47},{48,63}};
    private static final int[] QUANT_LO_INIT={0x004000,0x008000,0x008000,0x010000,0x010000,0x010000,0x010000,0x010000,0x010000,0x010000,0x010000,0x010000,0x020000,0x020000,0x020000,0x020000};
    private static final int[] QUANT_HI_INIT={0,0x020000,0x020000,0x040000,0x040000,0x040000,0x080000,0x040000,0x040000,0x080000};
    private static final int ZERO=1,ACTIVE=2,NEW=4,UNK=8;
    private static final int[] ZROW=new int[1024],ZCOL=new int[1024];
    static { for(int i=0;i<1024;i++){ZROW[i]=(((i>>1)&1)*16)+(((i>>3)&1)*8)+(((i>>5)&1)*4)+(((i>>7)&1)*2)+((i>>9)&1);ZCOL[i]=((i&1)*16)+(((i>>2)&1)*8)+(((i>>4)&1)*4)+(((i>>6)&1)*2)+((i>>8)&1);} }

    private static int norm(short v){int n=((int)v+32)>>6;return Math.max(-128,Math.min(127,n));}

    private static final class IW {
        final int width,height,blockCols;
        final short[][] blocks;
        final int[] qlo=QUANT_LO_INIT.clone(),qhi=QUANT_HI_INIT.clone();
        int band;
        final int[] bucketCtx={0},coefCtx=new int[80],activateCtx=new int[16],increaseCtx={0};
        final int[][] coeffState=new int[16][16]; final int[] bucketState=new int[16]; int bbState;
        IW(int w,int h) throws DjvuException {width=w;height=h;blockCols=(w+31)/32;int rows=(h+31)/32;long n=(long)blockCols*rows;if(n>262144)throw new DjvuException("IW44: слишком много блоков");blocks=new short[(int)n][1024];}
        void slice(ZpDecoder zp){if(!nullSlice()){for(int bi=0;bi<blocks.length;bi++){prelim(bi);if(blockPass(zp)){bucketPass(zp,bi);newActive(zp,bi);}prevActive(zp,bi);}}finish();}
        boolean nullSlice(){if(band==0){boolean nul=true;for(int i=0;i<16;i++){int t=qlo[i];coeffState[0][i]=ZERO;if(t>0&&t<0x8000){coeffState[0][i]=UNK;nul=false;}}return nul;}int t=qhi[band];return !(t>0&&t<0x8000);}
        void prelim(int bi){bbState=0;int from=BAND_BUCKETS[band][0],to=BAND_BUCKETS[band][1];if(band!=0){for(int j=from,bo=0;j<=to;j++,bo++){int bs=0;for(int k=0;k<16;k++){coeffState[bo][k]=blocks[bi][(j<<4)|k]==0?UNK:ACTIVE;bs|=coeffState[bo][k];}bucketState[bo]=bs;bbState|=bs;}}else{int bs=0;for(int k=0;k<16;k++){if(coeffState[0][k]!=ZERO)coeffState[0][k]=blocks[bi][k]==0?UNK:ACTIVE;bs|=coeffState[0][k];}bucketState[0]=bs;bbState|=bs;}}
        boolean blockPass(ZpDecoder zp){int from=BAND_BUCKETS[band][0],to=BAND_BUCKETS[band][1],cnt=to-from+1;boolean mark=cnt<16||(bbState&ACTIVE)!=0||((bbState&UNK)!=0&&zp.decode(bucketCtx));if(mark)bbState|=NEW;return (bbState&NEW)!=0;}
        void bucketPass(ZpDecoder zp,int bi){int from=BAND_BUCKETS[band][0],to=BAND_BUCKETS[band][1];for(int i=from,bo=0;i<=to;i++,bo++){if((bucketState[bo]&UNK)==0)continue;int n=0;if(band!=0){int t=4*i;for(int j=t;j<t+4;j++)if(blocks[bi][j]!=0)n++;if(n==4)n=3;}if((bbState&ACTIVE)!=0)n|=4;if(zp.decode(coefCtx,n+band*8))bucketState[bo]|=NEW;}}
        void newActive(ZpDecoder zp,int bi){int from=BAND_BUCKETS[band][0],to=BAND_BUCKETS[band][1],step=qhi[band];for(int i=from,bo=0;i<=to;i++,bo++){if((bucketState[bo]&NEW)==0)continue;int shift=(bucketState[bo]&ACTIVE)!=0?8:0,np=0;for(int j=0;j<16;j++)if((coeffState[bo][j]&UNK)!=0)np++;for(int j=0;j<16;j++)if((coeffState[bo][j]&UNK)!=0){int ip=Math.min(np,7);if(zp.decode(activateCtx,shift+ip)){int sign=zp.decodeIw()?-1:1;np=0;if(band==0)step=qlo[j];int s=step,val=sign*(s+(s>>1)-(s>>3));blocks[bi][(i<<4)|j]=(short)val;}np=Math.max(0,np-1);}}}
        void prevActive(ZpDecoder zp,int bi){int from=BAND_BUCKETS[band][0],to=BAND_BUCKETS[band][1],step=qhi[band];for(int i=from,bo=0;i<=to;i++,bo++)for(int j=0;j<16;j++)if((coeffState[bo][j]&ACTIVE)!=0){if(band==0)step=qlo[j];short coef=blocks[bi][(i<<4)|j];int av=Math.abs((int)coef),s=step;boolean d;if(av<=3*s){d=zp.decode(increaseCtx);av+=s>>2;}else d=zp.decodeIw();if(d)av+=s>>1;else av+=-s+(s>>1);blocks[bi][(i<<4)|j]=(short)(coef<0?-av:av);}}
        void finish(){qhi[band]>>>=1;if(band==0)for(int i=0;i<16;i++)qlo[i]>>>=1;band++;if(band==10)band=0;}
        Bytemap bytemap(int sub){int fw=((width+31)/32)*32,fh=((height+31)/32)*32,rows=(height+31)/32;Bytemap bm=new Bytemap(fw,fh);for(int r=0;r<rows;r++)for(int c=0;c<blockCols;c++){short[] block=blocks[r*blockCols+c];int rb=r<<5,cb=c<<5;for(int i=0;i<1024;i++)bm.data[(ZROW[i]+rb)*fw+ZCOL[i]+cb]=block[i];}inverseWavelet(bm,width,height,sub);return bm;}
    }

    private static final class Bytemap {final short[] data;final int stride;Bytemap(int w,int h){stride=w;data=new short[w*h];}}

    private int width,height; private boolean color; private int delay; private boolean chromaHalf; private IW y,cb,cr; private int cslice;

    void decodeChunk(byte[] data) throws DjvuException {
        if(data==null||data.length<2)throw new DjvuException("IW44: чанк слишком короткий");int serial=data[0]&255,slices=data[1]&255,start;
        if(serial==0){if(data.length<9)throw new DjvuException("IW44: первый чанк короче 9 байт");int maj=data[2]&255,minor=data[3]&255;boolean gray=(maj>>>7)!=0;int w=((data[4]&255)<<8)|(data[5]&255),h=((data[6]&255)<<8)|(data[7]&255);int db=data[8]&255;if(w==0||h==0)throw new DjvuException("IW44: нулевой размер");if((long)w*h>256L*1024*1024)throw new DjvuException("IW44: слишком большое изображение");width=w;height=h;color=!gray;delay=minor>=2?(db&127):0;chromaHalf=minor>=2&&(db&0x80)==0&&color;cslice=0;y=new IW(w,h);if(color){cb=new IW(w,h);cr=new IW(w,h);}start=9;}else{if(y==null)throw new DjvuException("IW44: следующий чанк пришёл до первого");start=2;}
        if(slices==0)return;
        // Some real-world DjVu files contain empty/truncated progressive IW44
        // refinement chunks (header + slice count, but fewer than two ZP bytes).
        // DjVu readers are expected to keep the image decoded so far rather than
        // make the whole page unreadable. Treat such a chunk as a no-op.
        if(data.length-start<2)return;
        byte[] z=new byte[data.length-start];System.arraycopy(data,start,z,0,z.length);ZpDecoder zp=new ZpDecoder(z);for(int i=0;i<slices;i++){cslice++;y.slice(zp);if(color&&cslice>delay){cb.slice(zp);cr.slice(zp);}}
    }

    int width(){return width;} int height(){return height;}
    RgbImage toImage(int subsample) throws DjvuException {
        if(subsample<1)throw new DjvuException("IW44: subsample < 1");if(y==null)throw new DjvuException("IW44: нет декодированного изображения");int sub=subsample,w=(width+sub-1)/sub,h=(height+sub-1)/sub;RgbImage out=new RgbImage(w,h);Bytemap yb=y.bytemap(sub);
        if(color){int csub=chromaHalf?Math.max(sub,2):sub;Bytemap cbb=cb.bytemap(csub),crb=cr.bytemap(csub);for(int row=0;row<h;row++){int oy=h-1-row;for(int col=0;col<w;col++){int sr=row*sub,sc=col*sub,yi=sr*yb.stride+sc;int chr=chromaHalf?(sr&~1):sr,chc=chromaHalf?(sc&~1):sc,ci=chr*cbb.stride+chc;int yy=norm(yb.data[yi]),bb=norm(cbb.data[ci]),rr=norm(crb.data[ci]);int t2=rr+(rr>>1),t3=yy+128-(bb>>2);out.setRgb(col,oy,yy+128+t2,t3-(t2>>1),t3+(bb<<1));}}}
        else{for(int row=0;row<h;row++){int oy=h-1-row;for(int col=0;col<w;col++){int v=norm(yb.data[row*sub*yb.stride+col*sub]);int g=127-v;out.setRgb(col,oy,g,g,g);}}}
        return out;
    }

    private static void inverseWavelet(Bytemap bm,int width,int height,int subsample){
        int stride=bm.stride;short[] d=bm.data;int sDegree=4,s=16;int[] st0=new int[Math.max(1,width)],st1=new int[Math.max(1,width)],st2=new int[Math.max(1,width)];
        while(s>=subsample){int sd=sDegree;
            // column pass
            int kmax=(height-1)>>sd,border=Math.max(0,kmax-3),numCols=(width+s-1)/s;Arrays.fill(st0,0,numCols,0);Arrays.fill(st1,0,numCols,0);if(kmax>=1){int off=(1<<sd)*stride,ci=0;for(int col=0;col<width;col+=s)st2[ci++]=d[off+col];}else Arrays.fill(st2,0,numCols,0);
            for(int k=0;k<=kmax;k+=2){int ko=(k<<sd)*stride;boolean hn=k+3<=kmax;int n3o=hn?((k+3)<<sd)*stride:0,ci=0;for(int col=0;col<width;col+=s,ci++){int p3=st0[ci],p1=st1[ci],n1=st2[ci],n3=hn?d[n3o+col]:0,a=p1+n1,c=p3+n3,idx=ko+col;d[idx]=(short)(d[idx]-(((a<<3)+a-c+16)>>5));st0[ci]=p1;st1[ci]=n1;st2[ci]=n3;}}
            if(kmax>=1){int km1=0,ko=(1<<sd)*stride,ci=0;if(2<=kmax){int kp1=(2<<sd)*stride;for(int col=0;col<width;col+=s,ci++){int p=d[km1+col],n=d[kp1+col],idx=ko+col;d[idx]=(short)(d[idx]+((p+n+1)>>1));st0[ci]=p;st1[ci]=n;}}else{for(int col=0;col<width;col+=s,ci++){int p=d[col],idx=ko+col;d[idx]=(short)(d[idx]+p);st0[ci]=p;st1[ci]=0;}}
                if(border>=3){int off=(4<<sd)*stride;ci=0;for(int col=0;col<width;col+=s,ci++)st2[ci]=d[off+col];}
                int k=3;while(k<=border){int koff=(k<<sd)*stride,n3off=((k+3)<<sd)*stride;ci=0;for(int col=0;col<width;col+=s,ci++){int p3=st0[ci],p1=st1[ci],n1=st2[ci],n3=d[n3off+col],a=p1+n1,idx=koff+col;d[idx]=(short)(d[idx]+(((a<<3)+a-(p3+n3)+8)>>4));st0[ci]=p1;st1[ci]=n1;st2[ci]=n3;}k+=2;}
                while(k<=kmax){int koff=(k<<sd)*stride;ci=0;if(k<kmax){for(int col=0;col<width;col+=s,ci++){int p=st1[ci],n=st2[ci],idx=koff+col;d[idx]=(short)(d[idx]+((p+n+1)>>1));st1[ci]=n;st2[ci]=0;}}else{for(int col=0;col<width;col+=s,ci++){int p=st1[ci],idx=koff+col;d[idx]=(short)(d[idx]+p);st1[ci]=st2[ci];st2[ci]=0;}}k+=2;}
            }
            // row pass
            kmax=(width-1)>>sd;border=Math.max(0,kmax-3);for(int row=0;row<height;row+=s){int off=row*stride,prev1=0,next1=0,next3=kmax>=1?d[off+(1<<sd)]:0,prev3;for(int k=0;k<=kmax;k+=2){prev3=prev1;prev1=next1;next1=next3;next3=k+3<=kmax?d[off+((k+3)<<sd)]:0;int a=prev1+next1,c=prev3+next3,idx=off+(k<<sd);d[idx]=(short)(d[idx]-(((a<<3)+a-c+16)>>5));}
                if(kmax>=1){int k=1;prev1=d[off+((k-1)<<sd)];if(k<kmax){next1=d[off+((k+1)<<sd)];int idx=off+(k<<sd);d[idx]=(short)(d[idx]+((prev1+next1+1)>>1));}else{int idx=off+(k<<sd);d[idx]=(short)(d[idx]+prev1);}next3=border>=3?d[off+((k+3)<<sd)]:0;k=3;while(k<=border){prev3=prev1;prev1=next1;next1=next3;next3=d[off+((k+3)<<sd)];int a=prev1+next1,idx=off+(k<<sd);d[idx]=(short)(d[idx]+(((a<<3)+a-(prev3+next3)+8)>>4));k+=2;}while(k<=kmax){prev1=next1;next1=next3;next3=0;int idx=off+(k<<sd);if(k<kmax)d[idx]=(short)(d[idx]+((prev1+next1+1)>>1));else d[idx]=(short)(d[idx]+prev1);k+=2;}}
            }
            s>>=1;sDegree=Math.max(0,sDegree-1);
        }
    }
}
