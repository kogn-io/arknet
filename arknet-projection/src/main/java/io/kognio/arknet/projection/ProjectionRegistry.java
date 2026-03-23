package io.kognio.arknet.projection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectionRegistry {

    private final Map<String, Projection> projections = new LinkedHashMap<>();

    public ProjectionRegistry() {
        register(new ContextMapProjectionAdapter());
    }

    public void register(Projection projection) {
        projections.put(projection.name(), projection);
    }

    public Projection get(String name) {
        return projections.get(name);
    }

    public List<String> available() {
        return projections.values().stream()
                .map(p -> "%s -- %s".formatted(p.name(), p.description()))
                .toList();
    }
}
