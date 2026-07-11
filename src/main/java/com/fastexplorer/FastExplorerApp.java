package com.fastexplorer;

import com.fastexplorer.cache.CacheDatabase;
import com.fastexplorer.ui.ExplorerFrame;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;

public final class FastExplorerApp {

    private FastExplorerApp() {}

    public static void main(String[] args) {
        FlatLightLaf.setup();
        System.setProperty("flatlaf.useWindowDecorations", "true");

        Runtime.getRuntime().addShutdownHook(new Thread(CacheDatabase.getInstance()::close, "cache-shutdown"));

        SwingUtilities.invokeLater(() -> {
            ExplorerFrame frame = new ExplorerFrame();
            frame.setVisible(true);
        });
    }
}
