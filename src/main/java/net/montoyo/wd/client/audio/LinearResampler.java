package net.montoyo.wd.client.audio;

/** Streaming linear-interpolation resampler for mono float audio; keeps state between blocks. */
public final class LinearResampler {
    private final double step; // input samples per output sample
    private float[] out = new float[1200];
    private float prev;
    private double frac;

    public LinearResampler(int inRate, int outRate) {
        this.step = inRate / (double) outRate;
    }

    /** Resamples {@code in[0..count)}; the result is in {@link #output()} and stays valid until the next call. */
    public int process(float[] in, int count) {
        if (count == 0) return 0;
        int maxOut = (int) Math.ceil((count + 1) / step) + 2;
        if (out.length < maxOut) out = new float[maxOut];

        int n = 0;
        double p = frac; // position in [prev, in[0..count-1]]: 0 = prev, k = in[k-1]
        while (p < count) {
            int i = (int) p;
            float t = (float) (p - i);
            float a = i == 0 ? prev : in[i - 1];
            float b = in[i];
            out[n++] = a + (b - a) * t;
            p += step;
        }
        frac = p - count;
        prev = in[count - 1];
        return n;
    }

    public float[] output() {
        return out;
    }
}
