package edu.kit.kastel.tva.eebc.verifier.analysis;

import edu.kit.kastel.tva.eebc.typesystem.HardwareModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The registry of named hardware models. Its entries become the options of the {@code hardwareModel} setting in the
 * Self-Description, in registration order.
 * <p>
 * To add a hardware model, register it in {@link #standard()}.
 */
public final class HardwareModels {
    /** Id of the default hardware model. */
    public static final String UNIT = "unit";

    private final Map<String, Entry> entries;

    /**
     * One hardware model.
     *
     * @param id      the option id sent by WebCorC as the {@code hardwareModel} setting value
     * @param label   the option label shown to users
     * @param factory creates a fresh model for each analysis
     */
    public record Entry(String id, String label, Supplier<HardwareModel> factory) {
    }

    private HardwareModels(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (map.putIfAbsent(entry.id(), entry) != null) {
                throw new IllegalArgumentException("duplicate hardware model id '" + entry.id() + "'");
            }
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    /** @return the registry the Verifier ships with; register new hardware models here */
    public static HardwareModels standard() {
        return new HardwareModels(List.of(
                new Entry(UNIT, "Unit cost (every operation costs 1)", UnitHardwareModel::new)
        ));
    }

    /** @return a copy of this registry with one more entry appended */
    public HardwareModels with(Entry entry) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.add(entry);
        return new HardwareModels(list);
    }

    /** @return all entries, in registration order */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** @return the entry with the given id, if any */
    public Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** @return the id of the default hardware model ({@link #UNIT}) */
    public String defaultId() {
        return UNIT;
    }
}
