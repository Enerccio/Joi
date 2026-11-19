package com.github.enerccio.joi.domain.animations;

import java.util.List;

/**
 * The rotation of an {@link AnimationStep}: a unit quaternion {@code [x, y, z, w]}, as three.js keeps it, and the angles
 * the dialog shows for it: degrees around X, Y and Z applied in the order Y, X, Z (three.js's {@code 'YXZ'}: first she
 * turns around, then tilts forward or back, then to the side), so that the fields and the rings of the preview agree.
 */
public final class StepRotation {

    /** Closer to no rotation than this (in 1 − |w|) is none. */
    private static final double NONE = 1e-9;

    private StepRotation() {
    }

    /** The quaternion as a unit one, null for none, for a wrong one, or for one that does not turn. */
    public static List<Double> normalized(List<Double> q) {
        if (q == null || q.size() != 4 || q.stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
            return null;
        }
        double x = q.get(0);
        double y = q.get(1);
        double z = q.get(2);
        double w = q.get(3);
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1e-9) {
            return null;
        }
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        if (1 - Math.abs(w) < NONE) {
            return null;
        }
        // the same rotation either way: keep w positive, so a stored one reads the same each time
        if (w < 0) {
            x = -x;
            y = -y;
            z = -z;
            w = -w;
        }
        return List.of(x, y, z, w);
    }

    /** The quaternion of the angles (degrees around X, Y, Z, in the order Y, X, Z); null for none. */
    public static List<Double> fromDegrees(double xDegrees, double yDegrees, double zDegrees) {
        double x = Math.toRadians(xDegrees) / 2;
        double y = Math.toRadians(yDegrees) / 2;
        double z = Math.toRadians(zDegrees) / 2;
        double c1 = Math.cos(x);
        double c2 = Math.cos(y);
        double c3 = Math.cos(z);
        double s1 = Math.sin(x);
        double s2 = Math.sin(y);
        double s3 = Math.sin(z);
        return normalized(List.of(
                s1 * c2 * c3 + c1 * s2 * s3,
                c1 * s2 * c3 - s1 * c2 * s3,
                c1 * c2 * s3 - s1 * s2 * c3,
                c1 * c2 * c3 + s1 * s2 * s3));
    }

    /** The angles of the quaternion: degrees around X, Y, Z (in the order Y, X, Z); zeros for none. */
    public static double[] toDegrees(List<Double> rotation) {
        List<Double> q = normalized(rotation);
        if (q == null) {
            return new double[]{0, 0, 0};
        }
        double x = q.get(0);
        double y = q.get(1);
        double z = q.get(2);
        double w = q.get(3);
        double x2 = x + x;
        double y2 = y + y;
        double z2 = z + z;
        double xx = x * x2;
        double xy = x * y2;
        double xz = x * z2;
        double yy = y * y2;
        double yz = y * z2;
        double zz = z * z2;
        double wx = w * x2;
        double wy = w * y2;
        double wz = w * z2;
        double m11 = 1 - (yy + zz);
        double m13 = xz + wy;
        double m21 = xy + wz;
        double m22 = 1 - (xx + zz);
        double m23 = yz - wx;
        double m31 = xz - wy;
        double m33 = 1 - (xx + yy);
        double ex = Math.asin(-Math.clamp(m23, -1, 1));
        double ey;
        double ez;
        if (Math.abs(m23) < 0.9999999) {
            ey = Math.atan2(m13, m33);
            ez = Math.atan2(m21, m22);
        } else {
            ey = Math.atan2(-m31, m11);
            ez = 0;
        }
        return new double[]{Math.toDegrees(ex), Math.toDegrees(ey), Math.toDegrees(ez)};
    }
}
