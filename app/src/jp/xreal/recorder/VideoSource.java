package jp.xreal.recorder;

interface VideoSource {
    void open() throws Exception;
    void start(UvcSource.Listener listener) throws Exception;
    void stop();
    boolean isFailed();
    String getError();
    int getAccessUnits();
    int getWidth();
    int getHeight();
    int getFrameRate();
}
