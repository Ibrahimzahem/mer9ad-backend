package com.hackathon.ra9edhamad.aml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the trained AML classifier ({@code aml_classifier.onnx}) and runs inference
 * in Java via the ONNX Runtime. The model is trained in Python (Google Colab) on the
 * IBM AML dataset and exported to ONNX so the Java backend can score live transfers
 * without a Python sidecar.
 *
 * <p>If the model file is missing (e.g. before training is complete), the classifier
 * degrades gracefully to "not loaded" and the {@link AmlAgent} falls back to
 * deterministic rules only — the system still works.
 */
public class AmlClassifier {

    private static final Logger log = LoggerFactory.getLogger(AmlClassifier.class);
    private static final String MODEL_PATH = "models/aml_classifier.onnx";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final boolean loaded;

    public AmlClassifier() {
        OrtEnvironment e = null;
        OrtSession s = null;
        String in = null;
        boolean ok = false;
        try (InputStream is = new ClassPathResource(MODEL_PATH).getInputStream()) {
            byte[] modelBytes = is.readAllBytes();
            e = OrtEnvironment.getEnvironment();
            s = e.createSession(modelBytes);
            in = s.getInputNames().iterator().next();
            ok = true;
            log.info("AML classifier loaded from {} ({} bytes, input={})", MODEL_PATH, modelBytes.length, in);
        } catch (Exception ex) {
            log.warn("AML classifier NOT loaded ({} will use rules-only mode): {}", MODEL_PATH, ex.getMessage());
        }
        this.env = e;
        this.session = s;
        this.inputName = in;
        this.loaded = ok;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Score a single transaction's feature vector.
     *
     * @return probability (0..1) that this transaction is laundering
     */
    public double predictLaundering(float[] features) {
        if (!loaded) {
            return -1.0; // signal "no model" to the caller
        }
        try {
            long[] shape = {1, features.length};
            FloatBuffer buf = FloatBuffer.wrap(features);
            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
                 OrtSession.Result result = session.run(Map.of(inputName, input))) {
                // RandomForest exports two outputs: label (long[]) and probabilities (float[][])
                // skl2onnx convention: output[0] = predicted label, output[1] = proba matrix
                var iter = result.iterator();
                if (!iter.hasNext()) return -1.0;
                // First output: predicted label
                var labelEntry = iter.next();
                long[] label = (long[]) labelEntry.getValue().getValue();
                if (iter.hasNext()) {
                    // Second output: probability matrix (may be float[][] or list of maps)
                    var probaEntry = iter.next();
                    Object probaValue = probaEntry.getValue().getValue();
                    if (probaValue instanceof float[][] proba) {
                        if (proba.length > 0 && proba[0].length >= 2) {
                            return proba[0][1]; // P(class=1) = laundering probability
                        }
                    } else if (probaValue instanceof Object[] arr && arr.length > 0 && arr[0] instanceof Map<?, ?> m) {
                        // zipmap format: list of {0: p0, 1: p1}
                        Object p1 = m.get(1L);
                        if (p1 instanceof Number n) return n.doubleValue();
                    }
                }
                // Fallback: read the label directly
                return label[0] == 1 ? 1.0 : 0.0;
            }
        } catch (Exception e) {
            log.warn("AML inference failed: {}", e.getMessage());
            return -1.0;
        }
    }

    public void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
    }
}
