// Adapted from MakeACopy@01bebd394b9dd6f3a692f28aea7c0638085eb4da (Apache-2.0).
// PaperForge: package changed; Lombok and runner-specific overloads removed.
/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package cn.paperforge.app.imaging.docquad;



/**
 * Small geometry utilities for deterministic quad scoring.
 *
 * <p>Coordinates are typically in 256-space (double), order TL,TR,BR,BL.
 */

public class DocQuadScore {

  public static double areaAbs(double[][] quad) {
    requireQuad(quad);
    double s = 0.0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      s += quad[i][0] * quad[j][1] - quad[j][0] * quad[i][1];
    }
    return Math.abs(0.5 * s);
  }

  public static double perimeter(double[][] quad) {
    requireQuad(quad);
    double p = 0.0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      p += dist(quad[i][0], quad[i][1], quad[j][0], quad[j][1]);
    }
    return p;
  }

  public static double edgeLengthMin(double[][] quad) {
    requireQuad(quad);
    double m = Double.POSITIVE_INFINITY;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      double d = dist(quad[i][0], quad[i][1], quad[j][0], quad[j][1]);
      if (d < m) m = d;
    }
    return m;
  }

  public static double edgeLengthMax(double[][] quad) {
    requireQuad(quad);
    double m = 0.0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      double d = dist(quad[i][0], quad[i][1], quad[j][0], quad[j][1]);
      if (d > m) m = d;
    }
    return m;
  }

  public static double aspectLike(double[][] quad) {
    double min = edgeLengthMin(quad);
    double max = edgeLengthMax(quad);
    return max / Math.max(min, 1e-9);
  }

  public static boolean selfIntersects(double[][] quad) {
    requireQuad(quad);
    // Quad edges: (0-1),(1-2),(2-3),(3-0)
    // Non-adjacent: (0-1) with (2-3), (1-2) with (3-0)
    return segmentsIntersect(
            quad[0][0], quad[0][1],
            quad[1][0], quad[1][1],
            quad[2][0], quad[2][1],
            quad[3][0], quad[3][1])
        || segmentsIntersect(
            quad[1][0], quad[1][1],
            quad[2][0], quad[2][1],
            quad[3][0], quad[3][1],
            quad[0][0], quad[0][1]);
  }

  public static boolean isConvex(double[][] quad) {
    requireQuad(quad);
    final double eps = 1e-9;
    int sign = 0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      int k = (i + 2) % 4;
      double cross =
          orient(
              quad[i][0], quad[i][1],
              quad[j][0], quad[j][1],
              quad[k][0], quad[k][1]);
      if (Math.abs(cross) <= eps) {
        // degenerate/collinear
        return false;
      }
      int s = cross > 0.0 ? 1 : -1;
      if (sign == 0) {
        sign = s;
      } else if (s != sign) {
        return false;
      }
    }
    return true;
  }

  /**
   * Sum of "out-of-bounds" distances (dx+dy) of all 4 points against a rectangle.
   *
   * <p>Rectangle is interpreted as an inclusive pixel frame: x in [0, w-1], y in [0, h-1]. A
   * tolerance {@code tolPx} expands the rectangle outward.
   */
  public static double oobSum(double[][] quad, double w, double h, double tolPx) {
    requireQuad(quad);
    if (!(w > 0.0) || !(h > 0.0) || !Double.isFinite(tolPx) || tolPx < 0.0) {
      throw new IllegalArgumentException("invalid bounds/tol");
    }

    double left = -tolPx;
    double top = -tolPx;
    double right = (w - 1.0) + tolPx;
    double bottom = (h - 1.0) + tolPx;

    double s = 0.0;
    for (int i = 0; i < 4; i++) {
      double x = quad[i][0];
      double y = quad[i][1];
      s += oob1d(x, left, right) + oob1d(y, top, bottom);
    }
    return s;
  }

  /**
   * Maximum of "out-of-bounds" distances (dx+dy) over all 4 points against a rectangle.
   *
   * <p>Rectangle is interpreted as an inclusive pixel frame: x in [0, w-1], y in [0, h-1]. A
   * tolerance {@code tolPx} expands the rectangle outward.
   */
  public static double oobMax(double[][] quad, double w, double h, double tolPx) {
    requireQuad(quad);
    if (!(w > 0.0) || !(h > 0.0) || !Double.isFinite(tolPx) || tolPx < 0.0) {
      throw new IllegalArgumentException("invalid bounds/tol");
    }

    double left = -tolPx;
    double top = -tolPx;
    double right = (w - 1.0) + tolPx;
    double bottom = (h - 1.0) + tolPx;

    double m = 0.0;
    for (int i = 0; i < 4; i++) {
      double x = quad[i][0];
      double y = quad[i][1];
      double v = oob1d(x, left, right) + oob1d(y, top, bottom);
      if (v > m) m = v;
    }
    return m;
  }

  private static void requireQuad(double[][] quad) {
    if (quad == null || quad.length != 4) {
      throw new IllegalArgumentException("quad must be double[4][2]");
    }
    for (int i = 0; i < 4; i++) {
      if (quad[i] == null || quad[i].length != 2) {
        throw new IllegalArgumentException("quad must be double[4][2]");
      }
    }
  }

  private static double oob1d(double v, double min, double max) {
    if (v < min) {
      return min - v;
    }
    if (v > max) {
      return v - max;
    }
    return 0.0;
  }

  private static double dist(double ax, double ay, double bx, double by) {
    return Math.hypot(bx - ax, by - ay);
  }

  private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
  }

  private static boolean onSegment(
      double ax, double ay, double bx, double by, double px, double py, double eps) {
    if (Math.abs(orient(ax, ay, bx, by, px, py)) > eps) {
      return false;
    }
    return (Math.min(ax, bx) - eps <= px && px <= Math.max(ax, bx) + eps)
        && (Math.min(ay, by) - eps <= py && py <= Math.max(ay, by) + eps);
  }

  /** Segment intersection including collinearity; endpoints count as intersection. */
  private static boolean segmentsIntersect(
      double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
    final double eps = 1e-9;
    double o1 = orient(ax, ay, bx, by, cx, cy);
    double o2 = orient(ax, ay, bx, by, dx, dy);
    double o3 = orient(cx, cy, dx, dy, ax, ay);
    double o4 = orient(cx, cy, dx, dy, bx, by);

    int s1 = sign(o1, eps);
    int s2 = sign(o2, eps);
    int s3 = sign(o3, eps);
    int s4 = sign(o4, eps);

    if (s1 == 0 && onSegment(ax, ay, bx, by, cx, cy, eps)) return true;
    if (s2 == 0 && onSegment(ax, ay, bx, by, dx, dy, eps)) return true;
    if (s3 == 0 && onSegment(cx, cy, dx, dy, ax, ay, eps)) return true;
    if (s4 == 0 && onSegment(cx, cy, dx, dy, bx, by, eps)) return true;

    return (s1 * s2 < 0) && (s3 * s4 < 0);
  }

  private static int sign(double v, double eps) {
    if (v > eps) return 1;
    if (v < -eps) return -1;
    return 0;
  }
}
