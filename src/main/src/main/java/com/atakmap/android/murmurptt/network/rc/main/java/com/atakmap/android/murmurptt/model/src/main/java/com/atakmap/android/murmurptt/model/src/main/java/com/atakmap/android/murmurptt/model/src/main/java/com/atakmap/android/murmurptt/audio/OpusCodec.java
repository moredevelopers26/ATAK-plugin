package com.atakmap.android.murmurptt.audio;

import android.util.Log;

/**
 * Wrapper JNI para codec Opus
 */
public class OpusCodec {
    
    private static final String TAG = "OpusCodec";
    
    private long encoder;
    private long decoder;
    private int sampleRate;
    private int channels;
    
    static {
        try {
            System.loadLibrary("opus");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "No se pudo cargar librería Opus", e);
        }
    }
    
    public OpusCodec(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.encoder = createEncoder(sampleRate, channels);
        this.decoder = createDecoder(sampleRate, channels);
    }
    
    /**
     * Codificar PCM a Opus
     */
    public byte[] encode(short[] pcmData, int frameSize) {
        if (encoder == 0) return null;
        return nativeEncode(encoder, pcmData, frameSize);
    }
    
    /**
     * Decodificar Opus a PCM
     */
    public short[] decode(byte[] opusData, int frameSize) {
        if (decoder == 0) return null;
        return nativeDecode(decoder, opusData, frameSize);
    }
    
    public void destroy() {
        if (encoder != 0) {
            destroyEncoder(encoder);
            encoder = 0;
        }
        if (decoder != 0) {
            destroyDecoder(decoder);
            decoder = 0;
        }
    }
    
    // Métodos nativos
    private native long createEncoder(int sampleRate, int channels);
    private native long createDecoder(int sampleRate, int channels);
    private native byte[] nativeEncode(long encoder, short[] pcm, int frameSize);
    private native short[] nativeDecode(long decoder, byte[] opus, int frameSize);
    private native void destroyEncoder(long encoder);
    private native void destroyDecoder(long decoder);
}
