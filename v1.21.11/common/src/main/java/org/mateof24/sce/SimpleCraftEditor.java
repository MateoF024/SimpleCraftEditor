package org.mateof24.sce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimpleCraftEditor {
    public static final String MOD_ID = "sce";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simple Craft Editor");

    private SimpleCraftEditor() {
    }

    public static void init() {
        LOGGER.info("Initializing Simple Craft Editor common.");
    }
}
