package eu.kalafatic.tryst.infrastructure.persistence;

import java.util.List;
import java.util.Map;

/**
 * Compatibility facade for the canonical JSON helper in
 * {@code eu.kalafatic.tryst.core.json.TrystJson} (single implementation shared by
 * core/domain/infrastructure).
 */
public final class TrystJson {

    private TrystJson() {
    }

    public static String writeStringMap(Map<String, String> data) {
        return eu.kalafatic.tryst.core.json.TrystJson.writeStringMap(data);
    }

    public static Map<String, String> readStringMap(String json) {
        return eu.kalafatic.tryst.core.json.TrystJson.readStringMap(json);
    }

    public static String writeStringList(List<String> values) {
        return eu.kalafatic.tryst.core.json.TrystJson.writeStringList(values);
    }

    public static List<String> readStringList(String json) {
        return eu.kalafatic.tryst.core.json.TrystJson.readStringList(json);
    }
}
