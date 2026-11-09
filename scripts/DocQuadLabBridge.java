import cn.paperforge.app.imaging.docquad.DocQuadLetterbox;
import cn.paperforge.app.imaging.docquad.DocQuadPostprocessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reuse the actual platform-free product postprocessor in the offline lab. */
public final class DocQuadLabBridge {
  public static void main(String[] args) throws Exception {
    ByteBuffer input = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0])))
        .order(ByteOrder.LITTLE_ENDIAN);
    if (input.remaining() != 5 * 64 * 64 * Float.BYTES) {
      throw new IllegalArgumentException("Expected four corner planes and one mask plane");
    }
    float[][][][] corners = new float[1][4][64][64];
    float[][][][] mask = new float[1][1][64][64];
    for (int channel = 0; channel < 5; channel++) {
      for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
          float value = input.getFloat();
          if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite model output");
          if (channel < 4) corners[0][channel][y][x] = value;
          else mask[0][0][y][x] = value;
        }
      }
    }
    var result = DocQuadPostprocessor.postprocess(corners, mask,
        DocQuadLetterbox.create(Integer.parseInt(args[1]), Integer.parseInt(args[2])),
        DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC);
    System.out.println("{\"quad\":" + Arrays.deepToString(result.chosenQuadOriginal())
        + ",\"source\":\"" + result.chosenSource() + "\",\"confidence\":"
        + result.cornerConfidence() + ",\"cornerMaskIoU\":" + result.cornerMaskIou()
        + ",\"suspicious\":" + result.suspiciousForProduct()
        + ",\"maskQuad\":" + Arrays.deepToString(result.quadFromMaskOriginal())
        + ",\"maskPenalty\":" + result.penaltyMask()
        + ",\"cornerPenalty\":" + result.penaltyCorners() + "}");
  }
}
