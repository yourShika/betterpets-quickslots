package com.terraformersmc.modmenu.api;

/**
 * Compile-only stand-in for Mod Menu's entrypoint interface, so the mod builds without a Mod Menu jar at
 * hand. It is never packaged: with Mod Menu installed the real interface is the one that gets loaded,
 * without it nothing ever asks for this class.
 */
public interface ModMenuApi {

    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> null;
    }
}
