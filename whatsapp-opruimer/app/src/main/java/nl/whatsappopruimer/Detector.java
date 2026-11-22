package nl.whatsappopruimer;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.Collections;
import java.util.List;

/**
 * Herkent personen volledig op het toestel:
 * - ML Kit gezichtsdetectie (ook kleine gezichten, vanaf 5% van de beeldbreedte)
 * - MediaPipe objectdetectie (EfficientDet-Lite2, COCO-klasse "person") voor lichamen,
 *   ook van achteren, van ver weg of half in beeld.
 * Niet thread-safe: gebruik één Detector per thread.
 */
final class Detector implements AutoCloseable {

    static final int ANALYSIS_SIZE = 1024;

    static final class Result {
        boolean error;
        int faces;
        float personScore;
        int width;
        int height;
    }

    private final FaceDetector faceDetector;
    private final ObjectDetector objectDetector;

    Detector(Context context) {
        FaceDetectorOptions fo = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build();
        faceDetector = FaceDetection.getClient(fo);

        ObjectDetector.ObjectDetectorOptions oo = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(0.10f)
                .setMaxResults(5)
                .setCategoryAllowlist(Collections.singletonList("person"))
                .build();
        objectDetector = ObjectDetector.createFromOptions(context, oo);
    }

    Result analyze(ImageLoader.Loaded img) throws Exception {
        Result r = new Result();
        r.width = img.width;
        r.height = img.height;

        List<Face> faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(img.bitmap, 0)));
        r.faces = faces.size();

        MPImage mp = new BitmapImageBuilder(img.bitmap).build();
        ObjectDetectorResult od = objectDetector.detect(mp);
        float best = 0f;
        for (Detection d : od.detections()) {
            for (Category c : d.categories()) {
                if ("person".equals(c.categoryName())) best = Math.max(best, c.score());
            }
        }
        r.personScore = best;
        return r;
    }

    @Override
    public void close() {
        try { faceDetector.close(); } catch (Exception ignored) { }
        try { objectDetector.close(); } catch (Exception ignored) { }
    }
}
