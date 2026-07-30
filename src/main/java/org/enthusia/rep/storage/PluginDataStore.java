package org.enthusia.rep.storage;

public interface PluginDataStore {

    PluginDataSnapshot load();

    boolean save(PluginDataSnapshot snapshot);
}
