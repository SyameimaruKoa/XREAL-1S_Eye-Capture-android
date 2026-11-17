package jp.xreal.recorder;

public final class PrintPrepareCommands {
    public static void main(String[] args) {
        for (String command : GlassesStartupPlan.prepareCommands()) {
            System.out.println(command);
        }
        System.out.println("---COMMIT---");
        System.out.println(GlassesStartupPlan.commitCommand());
    }
}
