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
 * Represents a letterbox transformation for mapping a source rectangle to a destination rectangle
 * while maintaining the aspect ratio. This class calculates the scaling factor and offset values
 * necessary to center the scaled source rectangle within the destination rectangle.
 *
 * <p>This is an immutable class.
 */
public final class DocQuadLetterbox {

  public final int srcW;
  public final int srcH;
  public final int dstW;
  public final int dstH;

  public final double scale;
  public final double offsetX;

  public final double offsetY;

  /**
   * Constructs a new DocQuadLetterbox instance with the specified source and destination
   * dimensions, scaling factor, and offsets. This is a private constructor and is used internally
   * to create an instance of the class.
   *
   * @param srcW the width of the source rectangle
   * @param srcH the height of the source rectangle
   * @param dstW the width of the destination rectangle
   * @param dstH the height of the destination rectangle
   * @param scale the scaling factor to fit the source within the destination while preserving the
   *     aspect ratio
   * @param offsetX the horizontal offset in the destination space
   * @param offsetY the vertical offset in the destination space
   */
  private DocQuadLetterbox(
      int srcW, int srcH, int dstW, int dstH, double scale, double offsetX, double offsetY) {
    this.srcW = srcW;
    this.srcH = srcH;
    this.dstW = dstW;
    this.dstH = dstH;
    this.scale = scale;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
  }

  /**
   * Creates a new {@code DocQuadLetterbox} instance by calculating the appropriate scaling factor
   * and offsets required to fit the source rectangle within the destination rectangle while
   * preserving the aspect ratio.
   *
   * @param srcW the width of the source rectangle, must be > 0
   * @param srcH the height of the source rectangle, must be > 0
   * @param dstW the width of the destination rectangle, must be > 0
   * @param dstH the height of the destination rectangle, must be > 0
   * @return a {@code DocQuadLetterbox} object containing the computed scale, offsets, and original
   *     dimensions
   * @throws IllegalArgumentException if any of the width or height parameters are less than or
   *     equal to 0
   */
  public static DocQuadLetterbox create(int srcW, int srcH, int dstW, int dstH) {
    if (srcW <= 0 || srcH <= 0) {
      throw new IllegalArgumentException("srcW/srcH must be > 0");
    }
    if (dstW <= 0 || dstH <= 0) {
      throw new IllegalArgumentException("dstW/dstH must be > 0");
    }

    double s = Math.min((double) dstW / (double) srcW, (double) dstH / (double) srcH);
    double newW = (double) srcW * s;
    double newH = (double) srcH * s;
    double ox = ((double) dstW - newW) / 2.0;
    double oy = ((double) dstH - newH) / 2.0;
    return new DocQuadLetterbox(srcW, srcH, dstW, dstH, s, ox, oy);
  }

  /**
   * Creates a new {@code DocQuadLetterbox} instance with a destination rectangle of fixed
   * dimensions (256x256) by calculating the appropriate scaling factor and offsets required to fit
   * the source rectangle while preserving the aspect ratio.
   *
   * @param srcW the width of the source rectangle, must be > 0
   * @param srcH the height of the source rectangle, must be > 0
   * @return a {@code DocQuadLetterbox} object containing the computed scale, offsets, and original
   *     dimensions
   * @throws IllegalArgumentException if any of the source width or height parameters are less than
   *     or equal to 0
   */
  public static DocQuadLetterbox create(int srcW, int srcH) {
    return create(srcW, srcH, 256, 256);
  }

  /**
   * Transforms the given 2D point coordinates (x, y) from the source space to the destination space
   * using the computed scaling factor and offsets.
   *
   * @param x the x-coordinate in the source space
   * @param y the y-coordinate in the source space
   * @return an array containing the transformed x and y coordinates in the destination space, where
   *     the first element is the transformed x-coordinate and the second element is the transformed
   *     y-coordinate
   */
  public double[] forward(double x, double y) {
    return new double[] {x * scale + offsetX, y * scale + offsetY};
  }

  /**
   * Transforms the given 2D point coordinates (x, y) from the destination space back to the source
   * space using the inverse of the computed scaling factor and offsets.
   *
   * @param x the x-coordinate in the destination space
   * @param y the y-coordinate in the destination space
   * @return an array containing the transformed x and y coordinates in the source space, where the
   *     first element is the transformed x-coordinate and the second element is the transformed
   *     y-coordinate
   */
  public double[] inverse(double x, double y) {
    return new double[] {(x - offsetX) / scale, (y - offsetY) / scale};
  }
}
