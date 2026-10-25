package ondcmcp.tools;

import java.util.*;

public final class ToolRegistry {
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry add(Tool... tools) { for (Tool t : tools) { if (byName.putIfAbsent(t.name(), t) != null) throw new IllegalStateException("duplicate tool " + t.name()); } return this; }
    public ToolRegistry addAll(Collection<Tool> tools) { tools.forEach(this::add); return this; }
    public Collection<Tool> all() { return Collections.unmodifiableCollection(byName.values()); }
    public Optional<Tool> find(String name) { return Optional.ofNullable(byName.get(name)); }
    public int size() { return byName.size(); }
}
