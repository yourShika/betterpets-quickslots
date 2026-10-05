package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/** Compile-only stand-in, see {@link ModMenuApi}. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {

    S create(Screen parent);
}
