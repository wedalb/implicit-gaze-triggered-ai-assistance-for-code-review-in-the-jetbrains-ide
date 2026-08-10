package io.github.gazehighlighter;

import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.util.List;

/**
 * Affine correction from raw webcam-tracker screen coordinates to actual screen coordinates.
 *
 * <p>UnitEye's generic pre-trained model is coarse and tends to be systematically off (biased
 * toward the webcam, off-center, scaled wrong for the monitor) rather than purely noisy. A
 * per-user affine fit — collected via {@link GazeCalibrationDialog}'s on-screen target points —
 * corrects that bias far more cheaply than retraining the model:
 *
 * <pre>
 *   corrected.x = a * raw.x + b * raw.y + c
 *   corrected.y = d * raw.x + e * raw.y + f
 * </pre>
 *
 * Both rows are independent ordinary-least-squares fits of the same design matrix
 * {@code [raw.x, raw.y, 1]} against the target's x and y respectively, so {@link #fit} builds
 * the 3x3 normal-equations matrix once and solves it twice.
 */
public final class GazeCalibration {

    public static final GazeCalibration IDENTITY = new GazeCalibration(1, 0, 0, 0, 1, 0);

    public final double a, b, c, d, e, f;

    public GazeCalibration(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c;
        this.d = d; this.e = e; this.f = f;
    }

    public Point apply(int rawX, int rawY) {
        int x = (int) Math.round(a * rawX + b * rawY + c);
        int y = (int) Math.round(d * rawX + e * rawY + f);
        return new Point(x, y);
    }

    /** One (raw tracker point, on-screen target point) correspondence collected during calibration. */
    public static final class Sample {
        public final double rawX, rawY, targetX, targetY;
        public Sample(double rawX, double rawY, double targetX, double targetY) {
            this.rawX = rawX; this.rawY = rawY; this.targetX = targetX; this.targetY = targetY;
        }
    }

    /**
     * Least-squares affine fit from {@code samples}. Needs at least 3 non-degenerate points
     * (in practice a 9-point grid); returns {@code null} if the system is singular (e.g. all
     * points collinear or too few valid samples came in).
     */
    public static @Nullable GazeCalibration fit(List<Sample> samples) {
        if (samples.size() < 3) return null;

        // Normal-equations matrix M = X^T X for design rows [rawX, rawY, 1], shared by both fits.
        double sxx = 0, sxy = 0, sx = 0, syy = 0, sy = 0, n = samples.size();
        double sxtx = 0, sytx = 0, stx = 0;   // X^T targetX
        double sxty = 0, syty = 0, sty = 0;   // X^T targetY

        for (Sample s : samples) {
            sxx += s.rawX * s.rawX;
            sxy += s.rawX * s.rawY;
            sx  += s.rawX;
            syy += s.rawY * s.rawY;
            sy  += s.rawY;

            sxtx += s.rawX * s.targetX;
            sytx += s.rawY * s.targetX;
            stx  += s.targetX;

            sxty += s.rawX * s.targetY;
            syty += s.rawY * s.targetY;
            sty  += s.targetY;
        }

        double[][] m = {
            { sxx, sxy, sx },
            { sxy, syy, sy },
            { sx,  sy,  n  },
        };

        double[] rowX = solve3x3(m, new double[]{ sxtx, sytx, stx });
        double[] rowY = solve3x3(m, new double[]{ sxty, syty, sty });
        if (rowX == null || rowY == null) return null;

        return new GazeCalibration(rowX[0], rowX[1], rowX[2], rowY[0], rowY[1], rowY[2]);
    }

    /** Solves {@code m * x = rhs} for a 3x3 system via Gaussian elimination with partial pivoting. */
    private static double @Nullable [] solve3x3(double[][] m, double[] rhs) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, a[i], 0, 3);
            a[i][3] = rhs[i];
        }

        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++)
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-9) return null;   // singular

            double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;

            for (int row = 0; row < 3; row++) {
                if (row == col) continue;
                double factor = a[row][col] / a[col][col];
                for (int k = col; k < 4; k++) a[row][k] -= factor * a[col][k];
            }
        }

        return new double[]{ a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2] };
    }
}
