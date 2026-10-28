package cn.paperforge.archive;
public final class ColorSpaceCompat {
 public static final int CS_sRGB=1000, CS_CIEXYZ=1001;
 private final int kind;
 private ColorSpaceCompat(int kind){this.kind=kind;}
 public static ColorSpaceCompat getInstance(int kind){return new ColorSpaceCompat(kind);}
 public float[] toRGB(float[] values){
  if(kind==CS_sRGB)return new float[]{Math.max(0,Math.min(1,values[0])),Math.max(0,Math.min(1,values[1])),Math.max(0,Math.min(1,values[2]))};
  return android.graphics.ColorSpace.connect(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.CIE_XYZ),android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)).transform(values.clone());
 }
}
