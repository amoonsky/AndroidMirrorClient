package com.tv.screenmirror.client;

import java.nio.ByteBuffer;

class FrameInfo {
    public ByteBuffer encodedFrame;
    public int flags;
    public long ptsUsec;

    public FrameInfo(ByteBuffer Frame, int Flags, long Pts ){
        encodedFrame = Frame;
        flags = Flags;
        ptsUsec = Pts;
    }
};
