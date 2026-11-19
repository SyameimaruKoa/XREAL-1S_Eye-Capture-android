package com.syameimarukoa.xreal_recorder;

interface VideoSource {
    void open() throws Exception;
    void start(UvcSource.Listener listener) throws Exception;
    void stop();
    boolean isFailed();
    /** True while waiting for the glasses link to come back after a disconnect. */
    default boolean isStalled() { return false; }
    String getError();
    int getAccessUnits();
    int getWidth();
    int getHeight();
    int getFrameRate();
}
