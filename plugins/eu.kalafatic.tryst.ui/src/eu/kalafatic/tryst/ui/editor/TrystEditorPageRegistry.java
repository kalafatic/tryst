package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible registry managing page descriptors for {@link TrystWorkbenchEditor}.
 */
public class TrystEditorPageRegistry {

    private static final TrystEditorPageRegistry INSTANCE = new TrystEditorPageRegistry();

    private final Map<String, TrystEditorPageDescriptor> descriptors = new ConcurrentHashMap<>();

    public static TrystEditorPageRegistry getInstance() {
        return INSTANCE;
    }

    public void registerPage(TrystEditorPageDescriptor descriptor) {
        if (descriptor != null) {
            descriptors.put(descriptor.id(), descriptor);
            TrystLog.debug(TrystEditorPageRegistry.class, "Registered editor page: " + descriptor.id() + " (" + descriptor.title() + ")");
        }
    }

    public void unregisterPage(String pageId) {
        if (pageId != null) {
            descriptors.remove(pageId);
        }
    }

    public List<TrystEditorPageDescriptor> getDescriptors() {
        List<TrystEditorPageDescriptor> list = new ArrayList<>(descriptors.values());
        Collections.sort(list);
        return Collections.unmodifiableList(list);
    }

    public void clear() {
        descriptors.clear();
    }
}
