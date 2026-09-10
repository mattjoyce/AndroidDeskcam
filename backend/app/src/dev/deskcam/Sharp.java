package dev.deskcam;

/**
 * How sharp a frame is, as one number.
 *
 * The variance of the Laplacian over the luma plane. A focused edge produces a large
 * second derivative and a blurred one produces almost none, so the spread of the
 * Laplacian rises to a maximum at focus and falls away either side of it. That is the
 * whole reason this lives on the phone: an agent can find the best focus by moving the
 * lens and reading a number, without a single frame crossing the network. Card 9.
 *
 * What it is not. It is a comparison and never a measurement. The value depends on what
 * is in the frame, on how much of the frame the region of interest holds, and on the
 * noise, which at high ISO is itself high-frequency detail and pushes the number up. Two
 * values are only worth comparing when everything except the focus was held still.
 */
final class Sharp {

    private Sharp() { }

    /**
     * The most Laplacian evaluations one measurement will do.
     *
     * The budget is 20 ms, because this is meant to be asked for repeatedly while a lens
     * moves. A whole 1280x960 preview frame is 1.2 million pixels; measured on a Pixel 6a
     * with the camera running, a quarter of a million samples cost 22 to 41 ms and 80
     * thousand cost about 9. The number below is that measurement and not an estimate.
     *
     * Rows are skipped to get there, never columns and never the kernel's neighbours. The
     * kernel reads the pixels either side and the rows above and below at full resolution,
     * because a kernel over subsampled pixels measures a blurrier image than the one in
     * front of the camera, and would put the peak of a focus sweep in the wrong place.
     */
    static final int MAX_SAMPLES = 80_000;

    /** Returned when the region is too small to hold the kernel. */
    static final double NOT_MEASURABLE = -1;

    /**
     * The variance of the Laplacian inside a region of a luma plane.
     *
     * The region is clipped to the plane and then inset by one pixel, because the kernel
     * needs a neighbour on every side.
     */
    static double focus(byte[] luma, int width, int height,
                        int left, int top, int regionW, int regionH) {
        if (luma == null || width < 3 || height < 3 || luma.length < width * height) {
            return NOT_MEASURABLE;
        }
        int x0 = Math.max(1, left);
        int y0 = Math.max(1, top);
        int x1 = Math.min(width - 1, left + regionW);
        int y1 = Math.min(height - 1, top + regionH);
        if (x1 - x0 < 3 || y1 - y0 < 3) return NOT_MEASURABLE;

        long area = (long) (x1 - x0) * (y1 - y0);
        int rowStep = (int) Math.max(1, (area + MAX_SAMPLES - 1) / MAX_SAMPLES);

        // Whole numbers throughout, and the two horizontal neighbours carried across the
        // loop rather than read again. A Laplacian is at most 1020 and its square 1.04
        // million, so the sample budget cannot come near overflowing a long.
        long sum = 0, sumSquares = 0, n = 0;
        for (int y = y0; y < y1; y += rowStep) {
            int row = y * width;
            int above = row - width;
            int below = row + width;
            int previous = luma[row + x0 - 1] & 0xff;
            int here = luma[row + x0] & 0xff;
            for (int x = x0; x < x1; x++) {
                int next = luma[row + x + 1] & 0xff;
                int lap = 4 * here - previous - next
                        - (luma[above + x] & 0xff) - (luma[below + x] & 0xff);
                sum += lap;
                sumSquares += (long) lap * lap;
                n++;
                previous = here;
                here = next;
            }
        }
        if (n < 2) return NOT_MEASURABLE;
        double mean = sum / (double) n;
        double variance = sumSquares / (double) n - mean * mean;
        return variance < 0 ? 0 : variance;   // only rounding can put it below zero
    }
}
