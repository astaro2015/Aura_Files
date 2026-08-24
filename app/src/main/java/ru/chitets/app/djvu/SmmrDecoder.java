package ru.chitets.app.djvu;

import java.util.Arrays;

/** ITU-T T.6 / Group-4 MMR decoder for DjVu Smmr mask chunks; Java adaptation informed by the public standard and MIT djvu-rs reference. */
final class SmmrDecoder {
    private static final int MAX_PIXELS = 256 * 1024 * 1024;

    // code, bit count, run length
    private static final int[][] WHITE_TERM = {
        {0b00110101,8,0},{0b000111,6,1},{0b0111,4,2},{0b1000,4,3},{0b1011,4,4},{0b1100,4,5},{0b1110,4,6},{0b1111,4,7},
        {0b10011,5,8},{0b10100,5,9},{0b00111,5,10},{0b01000,5,11},{0b001000,6,12},{0b000011,6,13},{0b110100,6,14},{0b110101,6,15},
        {0b101010,6,16},{0b101011,6,17},{0b0100111,7,18},{0b0001100,7,19},{0b0001000,7,20},{0b0010111,7,21},{0b0000011,7,22},{0b0000100,7,23},
        {0b0101000,7,24},{0b0101011,7,25},{0b0010011,7,26},{0b0100100,7,27},{0b0011000,7,28},{0b00000010,8,29},{0b00000011,8,30},{0b00011010,8,31},
        {0b00011011,8,32},{0b00010010,8,33},{0b00010011,8,34},{0b00010100,8,35},{0b00010101,8,36},{0b00010110,8,37},{0b00010111,8,38},{0b00101000,8,39},
        {0b00101001,8,40},{0b00101010,8,41},{0b00101011,8,42},{0b00101100,8,43},{0b00101101,8,44},{0b00000100,8,45},{0b00000101,8,46},{0b00001010,8,47},
        {0b00001011,8,48},{0b01010010,8,49},{0b01010011,8,50},{0b01010100,8,51},{0b01010101,8,52},{0b00100100,8,53},{0b00100101,8,54},{0b01011000,8,55},
        {0b01011001,8,56},{0b01011010,8,57},{0b01011011,8,58},{0b01001010,8,59},{0b01001011,8,60},{0b00110010,8,61},{0b00110011,8,62},{0b00110100,8,63}
    };
    private static final int[][] WHITE_MAKEUP = {
        {0b11011,5,64},{0b10010,5,128},{0b010111,6,192},{0b0110111,7,256},{0b00110110,8,320},{0b00110111,8,384},{0b01100100,8,448},{0b01100101,8,512},
        {0b01101000,8,576},{0b01100111,8,640},{0b011001100,9,704},{0b011001101,9,768},{0b011010010,9,832},{0b011010011,9,896},{0b011010100,9,960},{0b011010101,9,1024},
        {0b011010110,9,1088},{0b011010111,9,1152},{0b011011000,9,1216},{0b011011001,9,1280},{0b011011010,9,1344},{0b011011011,9,1408},{0b010011000,9,1472},{0b010011001,9,1536},
        {0b010011010,9,1600},{0b011000,6,1664},{0b010011011,9,1728}
    };
    private static final int[][] BLACK_TERM = {
        {0b0000110111,10,0},{0b010,3,1},{0b11,2,2},{0b10,2,3},{0b011,3,4},{0b0011,4,5},{0b0010,4,6},{0b00011,5,7},
        {0b000101,6,8},{0b000100,6,9},{0b0000100,7,10},{0b0000101,7,11},{0b0000111,7,12},{0b00000100,8,13},{0b00000111,8,14},{0b000011000,9,15},
        {0b0000010111,10,16},{0b0000011000,10,17},{0b0000001000,10,18},{0b00001100111,11,19},{0b00001101000,11,20},{0b00001101100,11,21},{0b00000110111,11,22},{0b00000101000,11,23},
        {0b00000010111,11,24},{0b00000011000,11,25},{0b000011001010,12,26},{0b000011001011,12,27},{0b000011001100,12,28},{0b000011001101,12,29},{0b000001101000,12,30},{0b000001101001,12,31},
        {0b000001101010,12,32},{0b000001101011,12,33},{0b000011010010,12,34},{0b000011010011,12,35},{0b000011010100,12,36},{0b000011010101,12,37},{0b000011010110,12,38},{0b000011010111,12,39},
        {0b000001101100,12,40},{0b000001101101,12,41},{0b000011011010,12,42},{0b000011011011,12,43},{0b000001010100,12,44},{0b000001010101,12,45},{0b000001010110,12,46},{0b000001010111,12,47},
        {0b000001100100,12,48},{0b000001100101,12,49},{0b000001010010,12,50},{0b000001010011,12,51},{0b000000100100,12,52},{0b000000110111,12,53},{0b000000111000,12,54},{0b000000100111,12,55},
        {0b000000101000,12,56},{0b000001011000,12,57},{0b000001011001,12,58},{0b000000101011,12,59},{0b000000101100,12,60},{0b000001011010,12,61},{0b000001100110,12,62},{0b000001100111,12,63}
    };
    private static final int[][] BLACK_MAKEUP = {
        {0b0000001111,10,64},{0b000011001000,12,128},{0b000011001001,12,192},{0b000001011011,12,256},{0b000000110011,12,320},{0b000000110100,12,384},{0b000000110101,12,448},{0b0000001101100,13,512},
        {0b0000001101101,13,576},{0b0000001001010,13,640},{0b0000001001011,13,704},{0b0000001001100,13,768},{0b0000001001101,13,832},{0b0000001110010,13,896},{0b0000001110011,13,960},{0b0000001110100,13,1024},
        {0b0000001110101,13,1088},{0b0000001110110,13,1152},{0b0000001110111,13,1216},{0b0000001010010,13,1280},{0b0000001010011,13,1344},{0b0000001010100,13,1408},{0b0000001010101,13,1472},{0b0000001011010,13,1536},
        {0b0000001011011,13,1600},{0b0000001100100,13,1664},{0b0000001100101,13,1728}
    };

    static Jb2Decoder.Mask decode(byte[] data) throws DjvuException {
        if (data == null || data.length < 4) throw new DjvuException("Smmr: чанк слишком короткий");
        int w,h,start; boolean inverted=false;
        if (data.length >= 8 && data[0]=='M' && data[1]=='M' && data[2]=='R') {
            int flags=data[3]&255;
            if((flags&0xfc)!=0) throw new DjvuException("Smmr: неверные флаги");
            if((flags&0x02)!=0) throw new DjvuException("Smmr: striped MMR пока не поддержан");
            inverted=(flags&1)!=0; w=((data[4]&255)<<8)|(data[5]&255); h=((data[6]&255)<<8)|(data[7]&255); start=8;
        } else {
            w=((data[0]&255)<<8)|(data[1]&255); h=((data[2]&255)<<8)|(data[3]&255); start=4;
        }
        if(w<0||h<0||(long)w*h>MAX_PIXELS) throw new DjvuException("Smmr: слишком большой bitmap "+w+"x"+h);
        int pixels=w*h; byte[] out=new byte[(pixels+7)>>>3];
        BitReader br=new BitReader(data,start);
        boolean[] white=new boolean[w], prev=white;
        for(int row=0;row<h;row++){
            boolean[] cur=br.empty()?new boolean[w]:decodeRow(br,prev,w);
            for(int x=0;x<w;x++) if(cur[x]^inverted){int i=row*w+x;out[i>>>3]|=(byte)(1<<(i&7));}
            prev=cur;
        }
        return new Jb2Decoder.Mask(w,h,out,null,null,false);
    }

    private static boolean[] decodeRow(BitReader br, boolean[] prev, int ncols) throws DjvuException {
        boolean[] cur=new boolean[ncols]; long a0=-1; boolean color=false; int guard=0;
        while(a0<ncols){
            if(++guard>ncols*8+1024) throw new DjvuException("Smmr: повреждённая строка");
            int idx0=(int)Math.max(0,a0), b1=findB1(prev,a0,color), b2=findB2(prev,Math.min(b1,Math.max(0,ncols-1)));
            Peek p=br.peek32(); if(p.avail==0) break;
            if(p.match(0b0001,4)){br.consume(4);int end=Math.min(b2,ncols);Arrays.fill(cur,idx0,end,color);a0=end;continue;}
            if(p.match(0b001,3)){
                br.consume(3);int r1=!color?run(br,WHITE_MAKEUP,WHITE_TERM):run(br,BLACK_MAKEUP,BLACK_TERM);int r2=!color?run(br,BLACK_MAKEUP,BLACK_TERM):run(br,WHITE_MAKEUP,WHITE_TERM);
                int e1=Math.min(ncols,idx0+r1);Arrays.fill(cur,idx0,e1,color);int e2=Math.min(ncols,e1+r2);Arrays.fill(cur,e1,e2,!color);a0=e2;continue;
            }
            int v;
            if(p.match(0b0000011,7)){br.consume(7);v=3;} else if(p.match(0b0000010,7)){br.consume(7);v=-3;}
            else if(p.match(0b000011,6)){br.consume(6);v=2;} else if(p.match(0b000010,6)){br.consume(6);v=-2;}
            else if(p.match(0b011,3)){br.consume(3);v=1;} else if(p.match(0b010,3)){br.consume(3);v=-1;}
            else if(p.match(1,1)){br.consume(1);v=0;} else break;
            int a1=Math.max(0,Math.min(ncols,b1+v)); Arrays.fill(cur,idx0,Math.min(a1,ncols),color);a0=a1;color=!color;
        }
        Arrays.fill(cur,(int)Math.max(0,Math.min(ncols,a0)),ncols,color);return cur;
    }

    private static int findB1(boolean[] prev,long a0,boolean color){boolean target=!color;int start=(int)Math.max(0,a0+1);for(int i=start;i<prev.length;i++){boolean left=i==0?false:prev[i-1];if(prev[i]==target&&left!=target)return i;}return prev.length;}
    private static int findB2(boolean[] prev,int b1){int start=b1+1;if(start>=prev.length)return prev.length;boolean c=prev[b1];for(int i=start;i<prev.length;i++)if(prev[i]!=c)return i;return prev.length;}

    private static int run(BitReader br,int[][] makeup,int[][] term)throws DjvuException{int total=0;for(;;){Peek p=br.peek32();if(p.avail==0)throw new DjvuException("Smmr: неожиданный конец G4-потока");boolean m=false;for(int[] e:makeup)if(p.match(e[0],e[1])){br.consume(e[1]);total+=e[2];m=true;break;}Peek q=br.peek32();for(int[] e:term)if(q.match(e[0],e[1])){br.consume(e[1]);return total+e[2];}if(!m)throw new DjvuException("Smmr: неверный Huffman-код");}}

    private static final class Peek{final int bits,avail;Peek(int b,int a){bits=b;avail=a;}boolean match(int code,int n){return n<=avail&&((bits>>>(avail-n))&((1<<n)-1))==code;}}
    private static final class BitReader{
        final byte[] d;int pos,rem=8;BitReader(byte[] d,int pos){this.d=d;this.pos=pos;}
        Peek peek32(){long val=0;int avail=0,p=pos,r=rem;while(avail<32&&p<d.length){int take=Math.min(32-avail,r),shift=r-take,mask=(1<<take)-1,b=((d[p]&255)>>>shift)&mask;val=(val<<take)|b;avail+=take;if(take==r){p++;r=8;}else r-=take;}return new Peek((int)val,avail);}
        void consume(int n){while(n>0&&pos<d.length){int take=Math.min(n,rem);rem-=take;n-=take;if(rem==0){pos++;rem=8;}}}
        boolean empty(){return pos>=d.length;}
    }
    private SmmrDecoder(){}
}
