package org.enthusia.rep.storage;

public interface PluginDataStore {

    PluginDataSnapshot load();

    void save(PluginDataSnapshot snapshot);
}
